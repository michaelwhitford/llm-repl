(ns us.whitford.llm-repl.tui
  "The TUI: llm-repl's human surface, built on escapement's PURE terminal
   primitives (escapement.tui.theme ∧ escapement.tui.compositor — both
   bb/SCI-safe, zero coupling to escapement's runner) with the two PROVEN
   patterns from escapement.tui COPIED, not depended on (that ns is hardwired
   to escapement's event vocabulary; its decoder is private):

     render loop ≡ one state atom ⊕ :render-dirty flag ⊕ ~30fps daemon ticker
       — ANY thread swaps state + flips the flag; ONE thread repaints.
       The registry watch (wire ns) rides this: an attached nREPL client's
       eval! appears in the terminal within ~33ms, no locking, no push.
     input       ≡ JLine raw mode ⊕ a pure byte→logical-key decoder
       — ours parses CSI params fully, which buys BRACKETED PASTE
       (ESC[200~ … ESC[201~): a multi-line paste lands as ONE turn
       (the plain loop's known friction), a capability escapement's
       decoder doesn't have.

   Screen (single column — split view is a later increment):
     ┌ llm-repl · slug · model · nREPL :port ──────────── ⇅ n/m ┐
     │ tape pane: user/assistant turns + system events, wrapped  │
     ├───────────────────────────────────────────────────────────┤
     sessions: [scratch·2] probe·0 twin·4↰scratch
     scratch[2]> input buffer_

   PURITY SEAM (the testable cut): everything above the impl divider is a
   pure function of (registry-snapshot ⊕ ui-state ⊕ theme ⊕ w/h) → strings,
   or (editor-state ⊕ key) → editor-state′. Headless tests exercise these
   without a terminal; the impl half only moves bytes."
  (:require
   [clojure.string :as str]
   [escapement.tui.compositor :as cmp]
   [escapement.tui.theme :as theme])
  (:import
   (org.jline.terminal Terminal TerminalBuilder)
   (org.jline.utils NonBlockingReader)))

;; ── pure: text shaping ────────────────────────────────────────────────────────

(defn wrap-text
  "Greedy-wrap `s` to `w` display columns; newlines respected. Always returns
   at least one line. Column accounting via cmp/display-width so SGR escapes
   and wide glyphs don't skew the fold."
  [s w]
  (let [w (max 1 w)]
    (into []
          (mapcat (fn [line]
                    (if (<= (cmp/display-width line) w)
                      [line]
                      (loop [cs (seq line) cur "" out []]
                        (if-let [c (first cs)]
                          (let [cur' (str cur c)]
                            (if (> (cmp/display-width cur') w)
                              (recur (rest cs) (str c) (conj out cur))
                              (recur (rest cs) cur' out)))
                          (conj out cur))))))
          (str/split-lines (str s)))))

;; ── pure: pane content ────────────────────────────────────────────────────────

(def role-label {:user "you ›" :assistant "  «" :event "  ·"})

(defn tape-lines
  "Render a tape (canonical messages) ⊕ pending flag into colored, wrapped
   body lines for the tape pane. Events do NOT belong here — they are global
   UI chrome (they'd repeat in every session's pane); the tree pane's footer
   owns them. A pending completion shows a thinking marker."
  [tape pending? theme w]
  (let [label-w 6
        body-w  (max 8 (- w label-w))
        indent  (apply str (repeat label-w \space))
        emit    (fn [label sgr text]
                  (let [ls (wrap-text text body-w)]
                    (into [(str (theme/sgr-wrap theme/chart-color (cmp/truncate-display label label-w))
                                (theme/sgr-wrap sgr (first ls)))]
                          (map #(str indent (theme/sgr-wrap sgr %)))
                          (rest ls))))]
    (-> []
        (into (mapcat (fn [{:keys [role text]}]
                        (case role
                          :user      (emit (role-label :user) theme/human-color text)
                          :assistant (emit (role-label :assistant) "" text)
                          (emit "  ?" theme/debug-color (str text)))))
              tape)
        (cond-> pending?
          (conj (str (theme/sgr-wrap theme/chart-color "  …   ")
                     (theme/paint theme :status/waiting "thinking")))))))

(defn welcome-lines
  "The TUI's in-idiom banner: dim grammar hints shown in the tape pane WHILE
   THE TAPE IS EMPTY (the printed banner belongs to plain/headless — the alt
   screen eats stdout). Vanishes at the first turn. Model ∧ nREPL port live
   in the title line already; this is only what the hands need."
  [w]
  (mapv #(theme/sgr-wrap theme/debug-color (cmp/truncate-display % w))
        [""
         " type to chat — Enter sends a turn on this session"
         " (form) evaluates as clojure — (help) or ? for the manual"
         " Tab walks the session tree · PgUp/PgDn scroll · Esc clears"
         " attach: any nREPL client on the port in the title"]))

(defn visible-window
  "Apply scroll (lines up from the tail) to `lines`, yielding exactly the
   window that fits `h` rows ⊕ the {:pos :total} scroll indicator."
  [lines h scroll]
  (let [total  (count lines)
        scroll (max 0 (min scroll (max 0 (- total h))))
        shown  (take-last h (drop-last scroll lines))]
    {:lines       shown
     :scroll      {:pos (- total scroll) :total total}
     ;; the EFFECTIVE scroll after clamping — callers sync state to this or
     ;; :scroll drifts past the content and reverse keys eat phantom distance
     :scroll-used scroll}))

;; ── pure: chrome ──────────────────────────────────────────────────────────────

(defn title-line
  [{:keys [slug nrepl-port]} reg]
  (let [model (get-in reg [slug :config :model])]
    (str "llm-repl · " slug " · " model " · nREPL :" nrepl-port)))

;; ── pure: the fork tree (left pane) ───────────────────────────────────────────

(defn- children-of
  "Children of `slug`, ordered by branch point then name — the fork tree's
   edges, inverted from :forked-from/:forked-at."
  [reg slug]
  (->> reg
       (filter (fn [[_ s]] (= slug (:forked-from s))))
       (sort-by (fn [[k s]] [(or (:forked-at s) 0) (str k)]))
       (map key)))

(defn dfs-order
  "Every session in depth-first tree order (roots sorted by name) — the order
   tab-cycling walks, so MOVEMENT ON SCREEN ≡ movement in the tree."
  [reg]
  (let [roots (->> reg (remove (fn [[_ s]] (:forked-from s))) (map key) (sort-by str))
        walk  (fn walk [slug]
                (cons slug (mapcat walk (children-of reg slug))))]
    (vec (mapcat walk roots))))

(defn- short-name
  "Child display name: strip the `parent-` prefix ab! children carry
   (scratch-nucleus under scratch → nucleus)."
  [slug parent]
  (let [n (name slug)
        p (some-> parent name (str "-"))]
    (if (and p (str/starts-with? n p) (> (count n) (count p)))
      (subs n (count p))
      n)))

(defn tree-lines
  "The fork forest as glyph-drawn lines for the tree pane; `current`
   highlighted (reverse video). Node ≡ name·depth, edge ≡ @branch-point."
  [reg current theme w]
  (let [walk (fn walk [slug parent prefix last?]
               (let [s     (get reg slug)
                     label (str (short-name slug parent)
                                "·" (count (:tape s))
                                (when (:forked-at s) (str " @" (:forked-at s))))
                     conn  (cond (nil? parent) "" last? "└ " :else "├ ")
                     line  (str prefix conn
                                (if (= slug current)
                                  (str cmp/reverse-on-s " " label " " theme/reset-attrs-s)
                                  label))
                     kids  (vec (children-of reg slug))
                     kid-prefix (str prefix (cond (nil? parent) "" last? "  " :else "│ "))]
                 (into [(cmp/truncate-display line w)]
                       (mapcat (fn [i k]
                                 (walk k slug kid-prefix (= i (dec (count kids)))))
                               (range (count kids)) kids))))
        roots (->> reg (remove (fn [[_ s]] (:forked-from s))) (map key) (sort-by str))]
    (vec (mapcat #(walk % nil "" true) roots))))

(defn sessions-line
  "The registry index strip: every tape as slug·depth (↰parent when forked),
   current session in reverse video."
  [reg current theme w]
  (->> (sort-by (comp str key) reg)
       (map (fn [[slug {:keys [tape forked-from forked-at]}]]
              (let [cell (str (name slug) "·" (count tape)
                              (when forked-from
                                (str "↰" (name forked-from)
                                     (when forked-at (str "@" forked-at)))))]
                (if (= slug current)
                  (str cmp/reverse-on-s " " cell " " theme/reset-attrs-s)
                  (str " " cell " ")))))
       (str/join " ")
       (str (theme/paint theme :border-dim "sessions:") " ")
       (#(cmp/truncate-display % w))))

(defn input-line
  "The editor row: prompt ⊕ buffer (newlines shown as ⏎) ⊕ cursor column
   (1-based terminal col for the caret). Buffer view slides when the caret
   would pass the right edge."
  [{:keys [slug]} reg {:keys [buffer cursor]} w]
  (let [depth  (count (get-in reg [slug :tape]))
        prompt (str (name slug) "[" depth "]> ")
        pw     (cmp/display-width prompt)
        shown  (str/replace buffer "\n" "⏎")
        avail  (max 1 (- w pw 1))
        ;; slide the view so the caret is always visible
        start  (max 0 (- cursor avail))
        view   (subs shown start (min (count shown) (+ start avail)))]
    {:text       (cmp/truncate-display (str prompt view) w)
     :cursor-col (+ pw (- cursor start) 1)}))

;; ── pure: the frame ───────────────────────────────────────────────────────────

(def tree-pane-w
  "Left tree-pane width (borders incl). Below `two-pane-threshold` total
   columns the layout falls back to single-pane ⊕ sessions strip."
  26)

(def two-pane-threshold 70)

(defn frame
  "The WHOLE screen as one ANSI string ⊕ caret position — a pure function of
   (registry-snapshot ⊕ ui-state ⊕ theme ⊕ term-w/h). Wide ≥70 cols: left
   tree pane (the map you move on — tab walks DFS, the highlight follows) ⊕
   right tape pane (where you are). Narrow: single pane ⊕ sessions strip.
   The impl half only emits this; headless tests assert on it directly."
  [reg {:keys [slug scroll events pending input overlay] :as state} theme term-w term-h]
  (let [two?    (>= term-w two-pane-threshold)
        box-h   (max 4 (- term-h (if two? 1 2)))
        inner-h (- box-h 2)
        tree-w  (if two? tree-pane-w 0)
        tape-w  (- term-w tree-w)
        tape    (get-in reg [slug :tape])
        {:keys [lines scroll scroll-used]}
        (if overlay
          ;; overlay {:title :lines} POPS OVER the right pane — chrome, never
          ;; tape content (the view swaps; the tape is untouched). HEAD-anchored
          ;; window: a document reads top-down, the tape is tail-anchored.
          (let [ls    (vec (mapcat #(wrap-text % (- tape-w 2)) (:lines overlay)))
                total (count ls)
                sc    (max 0 (min scroll (max 0 (- total inner-h))))]
            {:lines       (vec (take inner-h (drop sc ls)))
             :scroll      {:pos (min total (+ sc inner-h)) :total total}
             :scroll-used sc})
          (let [tl (tape-lines tape (some? pending) theme (- tape-w 2))
                tl (if (seq tl) tl (welcome-lines (- tape-w 2)))]
            (visible-window tl inner-h scroll)))
        buf     (StringBuilder.)]
    (when two?
      (let [tree-iw (- tree-w 2)
            ;; footer: the last few events, VERY short (dim, truncated) —
            ;; an index of what happened, never the payload
            evs     (mapv #(theme/sgr-wrap theme/debug-color (cmp/truncate-display % tree-iw))
                          (take-last 5 events))
            ev-h    (if (seq evs) (inc (count evs)) 0)
            tree-h  (max 1 (- inner-h ev-h))
            tl      (tree-lines reg slug theme tree-iw)
            ;; window the tree around the CURRENT node (the reverse-video line)
            ci      (max 0 (.indexOf ^java.util.List
                                     (mapv #(str/includes? % cmp/reverse-on-s) tl) true))
            tl      (if (> (count tl) tree-h)
                      (let [start (max 0 (min (- (count tl) tree-h) (- ci (quot tree-h 2))))]
                        (subvec tl start (min (count tl) (+ start tree-h))))
                      tl)
            ;; pad the tree region so the footer PINS to the pane bottom
            body    (if (seq evs)
                      (-> (into [] tl)
                          (into (repeat (- tree-h (count tl)) ""))
                          (conj (theme/paint theme :border-dim (apply str (repeat tree-iw "·"))))
                          (into evs))
                      tl)]
        (cmp/draw-box buf {:row 1 :col 1 :w tree-w :h box-h
                           :title "tree" :theme theme :body-lines body})))
    ;; overlay decoration is the FRAME's contract, not each caller's: every
    ;; overlay announces itself (⧉ ≡ stacked-over) and how to leave (esc) —
    ;; callers pass a bare :title
    (cmp/draw-box buf {:row 1 :col (inc tree-w) :w tape-w :h box-h
                       :title (if overlay
                                (str "⧉ " (:title overlay) " — esc closes")
                                (title-line state reg))
                       :scroll scroll
                       :theme theme
                       :body-lines (vec lines)})
    ;; narrow fallback: sessions strip ONLY — events live in the tree pane's
    ;; footer (left panel bottom), and narrow mode has no tree pane. No
    ;; bottom-footer event display (ratified: the footer receipt home is the
    ;; tree pane).
    (when-not two?
      (.append buf (cmp/move-to-s (inc box-h) 1))
      (.append buf (sessions-line reg slug theme term-w)))
    (let [input-row (+ box-h (if two? 1 2))
          il        (input-line state reg input term-w)]
      (.append buf (cmp/move-to-s input-row 1))
      (.append buf (:text il))
      {:s           (str buf)
       :cursor-row  input-row
       :cursor-col  (:cursor-col il)
       :scroll-used scroll-used})))

;; ── pure: byte→logical-key decoder ────────────────────────────────────────────

(def esc-seq-timeout-ms
  "Wait for the byte after an ESC before concluding bare Escape. MUST be >0:
   a CSI tail that hasn't buffered yet otherwise misreads as bare ESC
   (escapement bug-history, encoded in knowledge/upstream/escapement.md)."
  50)

(defn- read-csi
  "Accumulate a CSI sequence's parameter bytes after `ESC [` up to the final
   byte (0x40-0x7E). FULL param parse — unlike escapement's decoder — which is
   what buys bracketed paste (ESC[200~ / ESC[201~). Returns [params final]."
  [read!]
  (loop [params "" n 0]
    (let [b (read! esc-seq-timeout-ms)]
      (cond
        (or (neg? b) (> n 16)) [params nil]
        (<= 0x40 b 0x7e)       [params (char b)]
        :else                  (recur (str params (char b)) (inc n))))))

(defn key-from-bytes
  "Decode ONE logical key from `read!` ≡ (fn [timeout-ms] → int): non-positive
   timeout blocks; positive waits and returns negative (EOF -1 / expired -2)
   when nothing arrives. Pure — headless tests feed byte vectors.
   Returns :eof :ctrl-c :ctrl-d :tab :backspace :enter :space :esc
           :up :down :left :right :home :end :pgup :pgdn
           :paste-start :paste-end :other | [:char c]
   Chars ≥128 pass through as [:char c] (JLine's reader yields CHARS — accents
   and unicode type fine; escapement's ASCII-only guard dropped them)."
  [read!]
  (let [c (read! 0)]
    (cond
      (= c -1) :eof
      (= c 3)  :ctrl-c
      (= c 4)  :ctrl-d
      (= c 9)  :tab
      (or (= c 8) (= c 127)) :backspace
      (or (= c 10) (= c 13)) :enter
      (= c 32) :space
      (= c 27)
      (let [b1 (read! esc-seq-timeout-ms)]
        (cond
          (neg? b1) :esc
          (= b1 91)                                          ;; CSI: ESC [
          (let [[params final] (read-csi read!)]
            (case final
              \A :up  \B :down  \C :right  \D :left  \H :home  \F :end
              \~ (case params
                   "5" :pgup  "6" :pgdn  "1" :home  "4" :end
                   "200" :paste-start  "201" :paste-end
                   :other)
              :other))
          (= b1 79)                                          ;; SS3: ESC O
          (case (int (read! esc-seq-timeout-ms))
            65 :up 66 :down 67 :right 68 :left :other)
          :else :esc))
      (>= c 32) [:char (char c)]
      :else :other)))

;; ── pure: line editor ─────────────────────────────────────────────────────────

(defn- insert-at [s i c] (str (subs s 0 i) c (subs s i)))

(defn edit-step
  "One editor transition: input-map × key → {:input input' :submit text?}.
   Pure. Enter submits (unless mid-paste, where it inserts a newline — the
   bracketed-paste win: a pasted block lands as ONE submission). Up/down walk
   history, preserving the in-progress draft. Esc clears."
  [{:keys [buffer cursor history hist-idx draft paste?] :as in} k]
  (let [ins (fn [c] {:input (-> in
                                (assoc :buffer (insert-at buffer cursor c))
                                (update :cursor + (count (str c)))
                                (assoc :hist-idx nil))})]
    (cond
      (and (vector? k) (= :char (first k))) (ins (second k))
      (= k :space) (ins " ")
      (= k :paste-start) {:input (assoc in :paste? true)}
      (= k :paste-end)   {:input (assoc in :paste? false)}
      (= k :enter)
      (cond
        paste?               (ins "\n")
        (str/blank? buffer)  {:input in}
        :else                {:input (assoc in :buffer "" :cursor 0 :hist-idx nil :draft nil
                                            :history (conj history buffer))
                              :submit buffer})
      (= k :backspace)
      {:input (if (pos? cursor)
                (-> in
                    (assoc :buffer (str (subs buffer 0 (dec cursor)) (subs buffer cursor)))
                    (update :cursor dec))
                in)}
      (= k :left)  {:input (update in :cursor #(max 0 (dec %)))}
      (= k :right) {:input (update in :cursor #(min (count buffer) (inc %)))}
      (= k :home)  {:input (assoc in :cursor 0)}
      (= k :end)   {:input (assoc in :cursor (count buffer))}
      (= k :esc)   {:input (assoc in :buffer "" :cursor 0 :hist-idx nil)}
      (= k :up)
      (let [n (count history)]
        (if (zero? n)
          {:input in}
          (let [i (if (nil? hist-idx) (dec n) (max 0 (dec hist-idx)))
                b (nth history i)]
            {:input (assoc in :hist-idx i :buffer b :cursor (count b)
                           :draft (if (nil? hist-idx) buffer draft))})))
      (= k :down)
      (cond
        (nil? hist-idx) {:input in}
        (< hist-idx (dec (count history)))
        (let [i (inc hist-idx) b (nth history i)]
          {:input (assoc in :hist-idx i :buffer b :cursor (count b))})
        :else
        (let [b (or draft "")]
          {:input (assoc in :hist-idx nil :draft nil :buffer b :cursor (count b))}))
      :else {:input in})))

;; ══ impl: terminal, render loop ═══════════════════════════════════════════════

(def ^:private esc theme/esc)
(def ^:private clear-screen-s (str (esc "2J") (esc "H")))
(def ^:private alt-screen-on-s (esc "?1049h"))
(def ^:private alt-screen-off-s (esc "?1049l"))
(def ^:private hide-cursor-s (esc "?25l"))
;; tmux terminfo cnorm ≡ \e[34h\e[?25h — send the union (escapement precedent)
(def ^:private show-cursor-s (str (esc "34h") (esc "?25h")))
(def ^:private paste-on-s (esc "?2004h"))
(def ^:private paste-off-s (esc "?2004l"))

(defn- emit! [s]
  (binding [*out* *err*]
    (print s)
    (flush)))

(defn interactive-terminal?
  "Both stdin and stdout attached to a real terminal (bb's bundled helper;
   System/console fallback under JVM — escapement's exact pattern)."
  []
  (boolean
   (if-let [tty? (try (requiring-resolve 'babashka.terminal/tty?) (catch Throwable _ nil))]
     (and (tty? :stdin) (tty? :stdout))
     (some? (System/console)))))

(defn request-render!
  "Flip the dirty flag — cheap, non-blocking, callable from ANY thread (the
   registry watch, eval workers, the input thread). The ticker repaints."
  [state]
  (swap! state assoc :render-dirty true))

(defn render-frame!
  "Repaint NOW (ticker-called; serialized by `lock`). Reads terminal size each
   frame — resize is picked up on the next paint, no signal handling needed."
  [{:keys [state ^Terminal terminal lock theme]}]
  (locking lock
    (let [w (max 40 (.getWidth terminal))
          h (max 8 (.getHeight terminal))
          s (swap! state assoc :term-w w :term-h h :render-dirty false)
          ;; the events STREAM is referenced (like :registry), deref'd per
          ;; frame — `frame` stays pure, headless tests pass :events directly
          s (cond-> s
              (:events-ref s) (assoc :events (vec @(:events-ref s))))
          {:keys [s cursor-row cursor-col scroll-used]} (frame @(:registry s) s theme w h)]
      ;; sync state to the EFFECTIVE scroll — without this, scrolling past
      ;; either end inflates :scroll invisibly and the reverse direction eats
      ;; phantom distance before the view moves (human-found: arrow-up after
      ;; bottoming out the help overlay)
      (when scroll-used
        (swap! state (fn [st] (if (= (:scroll st) scroll-used)
                                st
                                (assoc st :scroll scroll-used)))))
      (emit! (str hide-cursor-s s
                  (cmp/move-to-s cursor-row cursor-col)
                  show-cursor-s)))))

(defn- start-ticker!
  "The ONE repainting thread: ~30fps, paints only when dirty (escapement's
   coalescing pattern — producers swap!+flag at their true rate)."
  [{:keys [state stopped?] :as h}]
  (doto (Thread.
         ^Runnable
         (fn []
           (loop []
             (when-not @stopped?
               (try
                 (Thread/sleep 33)
                 (when (:render-dirty @state)
                   (render-frame! h))
                 (catch InterruptedException _ nil)
                 (catch Throwable _ nil))
               (recur))))
         "llm-repl-tui-render")
    (.setDaemon true)
    (.start)))

(defn stop!
  "Restore the terminal. Idempotent — normal exit, Ctrl-C signal, and the JVM
   shutdown hook all funnel here (escapement's belt-and-braces pattern)."
  [{:keys [stopped? on-stop]}]
  (when (compare-and-set! stopped? false true)
    (try (emit! (str theme/reset-attrs-s paste-off-s alt-screen-off-s show-cursor-s "\n"))
         (catch Throwable _ nil))
    (when on-stop (try (on-stop) (catch Throwable _ nil)))))

(defn show-overlay!
  "Pop a document {:title s :lines [s]} OVER the right pane. Content is
   INJECTED (the wire layer renders it — this ns stays core-free, and any
   future overlay — compare pane, manual pages — rides the same slot).
   Esc dismisses; PgUp/PgDn scroll (head-anchored)."
  [state overlay]
  (swap! state assoc :overlay overlay :scroll 0 :render-dirty true))

(defn dismiss-overlay!
  "Drop the overlay; the right pane returns to the tape (scroll reset)."
  [state]
  (swap! state #(-> % (dissoc :overlay) (assoc :scroll 0 :render-dirty true))))

(defn scroll-view!
  "Move the right-pane view `n` lines, dir ∈ {:up :down} — SCREEN semantics,
   constant across body kinds: the tape is TAIL-anchored (scroll+ ≡ toward
   older turns ≡ up) while an overlay is HEAD-anchored (scroll+ ≡ further
   down the document), so the sign flips per kind here, in ONE place —
   key handlers stay direction-literal."
  [state dir n]
  (swap! state (fn [s]
                 (let [sign (if (:overlay s)
                              (if (= dir :up) - +)
                              (if (= dir :up) + -))]
                   (-> s
                       (update :scroll #(max 0 (sign % n)))
                       (assoc :render-dirty true))))))

(defn cycle-slug!
  "Point the TUI at the next session in DFS TREE order (wraps) — tab movement
   tracks the tree pane's shape, so cycling FEELS like walking the tree."
  [state]
  (swap! state (fn [{:keys [registry slug] :as s}]
                 (let [slugs (dfs-order @registry)
                       i     (.indexOf ^clojure.lang.PersistentVector slugs slug)
                       slug' (if (seq slugs)
                               (nth slugs (mod (inc i) (count slugs)))
                               slug)]
                   (assoc s :slug slug' :scroll 0 :render-dirty true)))))

(defn- input-loop!
  "The input thread body: raw mode, then decode→dispatch until quit.
   Loop-level keys (session/viewport/quit) here; editing keys → edit-step.
   `on-submit` ≡ (fn [text]) — the wire layer decides chat vs form."
  [{:keys [^Terminal terminal state on-submit on-help] :as h}]
  (.enterRawMode terminal)
  (let [rdr   ^NonBlockingReader (.reader terminal)
        read! (fn [t] (if (pos? ^long t) (.read rdr (long t)) (.read rdr)))
        page  (fn [] (max 1 (- (:term-h @state) 6)))]
    (loop []
      (let [k (key-from-bytes read!)]
        (cond
          (contains? #{:eof :ctrl-c :ctrl-d} k)
          (do (stop! h) (System/exit 0))

          ;; Esc: overlay-first (dismiss), else the editor's clear-buffer
          (= k :esc)
          (if (:overlay @state)
            (dismiss-overlay! state)
            (swap! state (fn [s] (-> s (assoc :input (:input (edit-step (:input s) k)))
                                     (assoc :render-dirty true)))))

          ;; ? on an EMPTY buffer (and not mid-paste) → help overlay
          (and (= k [:char \?])
               on-help
               (str/blank? (get-in @state [:input :buffer]))
               (not (get-in @state [:input :paste?])))
          (on-help)

          (= k :tab)  (cycle-slug! state)
          (= k :pgup) (scroll-view! state :up (page))
          (= k :pgdn) (scroll-view! state :down (page))

          ;; overlay: arrows scroll LINE-BY-LINE (the editor's history walk
          ;; is meaningless under an overlay; it resumes on dismiss)
          (and (contains? #{:up :down} k) (:overlay @state))
          (scroll-view! state k 1)

          :else
          (let [submitted (volatile! nil)]
            (swap! state (fn [s]
                           (let [{:keys [input submit]} (edit-step (:input s) k)]
                             (vreset! submitted submit)
                             (assoc s :input input :render-dirty true))))
            (when-let [text @submitted]
              (on-submit text)))))
      (when-not @(:stopped? h)
        (recur)))))

(defn start!
  "Boot the TUI: alt screen, bracketed paste, render ticker. Returns the
   handle {:state :terminal :lock :theme :stopped?} the input loop (input ns
   half) and the wire layer drive. `registry` ≡ core's sessions* atom,
   `events` ≡ core's events* atom (BOTH referenced, not copied — the frame
   reads them live; every client's receipts show, not just this surface's).
   Caller must check interactive-terminal? first."
  [{:keys [registry events slug nrepl-port on-stop on-submit on-help]}]
  (let [terminal (-> (TerminalBuilder/builder) (.system true) (.build))
        state    (atom {:registry     registry
                        :events-ref   events
                        :slug         slug
                        :nrepl-port   nrepl-port
                        :scroll       0
                        :events       []
                        :pending      nil
                        :input        {:buffer "" :cursor 0 :history [] :hist-idx nil :paste? false}
                        :term-w       (.getWidth terminal)
                        :term-h       (.getHeight terminal)
                        :render-dirty true})
        h        {:state     state
                  :terminal  terminal
                  :lock      (Object.)
                  :theme     (theme/theme-for (theme/color-capability true))
                  :stopped?  (atom false)
                  :on-stop   on-stop
                  :on-submit on-submit
                  :on-help   on-help}]
    (emit! (str alt-screen-on-s clear-screen-s hide-cursor-s paste-on-s))
    (render-frame! h)
    (start-ticker! h)
    (doto (Thread. ^Runnable (fn [] (try (input-loop! h) (catch Throwable _ nil)))
                   "llm-repl-tui-input")
      (.setDaemon true)
      (.start))
    ;; Ctrl-C: JLine swallows SIGINT on system terminals — install ours after
    ;; .build so the terminal is restored (escapement's exact sequence).
    (try
      (sun.misc.Signal/handle
       (sun.misc.Signal. "INT")
       (reify sun.misc.SignalHandler
         (handle [_ _]
           (try (stop! h) (catch Throwable _ nil))
           (System/exit 130))))
      (catch Throwable _ nil))
    (try
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable (fn [] (try (stop! h) (catch Throwable _ nil)))
                                 "llm-repl-tui-shutdown"))
      (catch Throwable _ nil))
    h))
