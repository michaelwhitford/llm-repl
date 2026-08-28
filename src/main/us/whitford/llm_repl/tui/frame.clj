(ns us.whitford.llm-repl.tui.frame
  "The TUI's PURE half (v0.3.0 step 6 — D5: the testable cut is FILE
   topology, not an in-file comment divider). Everything here is a pure
   function of (registry-snapshot ⊕ ui-state ⊕ theme ⊕ w/h) → strings, or
   (editor-state ⊕ key) → editor-state′, or (byte-reader) → logical key.
   Headless tests exercise all of it without a terminal (D6 names this
   suite); `tui.term` is the ONLY io consumer.

   Built on escapement's PURE terminal primitives (escapement.tui.theme ∧
   escapement.tui.compositor — both bb/SCI-safe, zero coupling to
   escapement's runner). The byte→key decoder parses CSI params FULLY, which
   buys BRACKETED PASTE (ESC[200~ … ESC[201~): a multi-line paste lands as
   ONE turn — a capability escapement's (private) decoder doesn't have.

   Screen (wide ≥70 cols — narrow falls back to single pane ⊕ sessions strip):
     ┌ tree ─────┐┌ llm-repl · slug · model · nREPL :port ──── ⇅ n/m ┐
     │ the map   ││ tape pane: user/assistant turns, wrapped          │
     └───────────┘└───────────────────────────────────────────────────┘
     scratch[2]> input buffer_"
  (:require
   [clojure.string :as str]
   [escapement.tui.compositor :as cmp]
   [escapement.tui.theme :as theme]
   [us.whitford.llm-repl.registry :as registry]))

;; ── text shaping ──────────────────────────────────────────────────────────────

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

;; ── pane content ──────────────────────────────────────────────────────────────

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

(defn loading-lines
  "The tape pane when the view holds NO tape for the focused session YET
   (`:tape` nil ≡ not-fetched, distinct from `[]` ≡ open with no turns). Lives
   for the round trip between a focus change and its `registry/view` answer.
   The alternatives were both lies: keep painting the PREVIOUS session's
   messages under the new title, or flash the welcome banner at a session
   that has a long history."
  [w]
  [(theme/sgr-wrap theme/debug-color (cmp/truncate-display "  …   loading" w))])

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

;; ── chrome ────────────────────────────────────────────────────────────────────

(defn title-line
  [{:keys [slug nrepl-port]} index]
  (let [model (get-in index [slug :model])]
    (str "llm-repl · " slug " · " model " · nREPL :" nrepl-port)))

;; ── the fork tree (left pane) ─────────────────────────────────────────────────

(defn- children-of
  "Children of `slug`, ordered by branch point then name — the fork tree's
   edges, inverted from :forked-from/:forked-at. Reads the INDEX (edges are
   registry-wide; message bodies never were part of this)."
  [index slug]
  (->> index
       (filter (fn [[_ s]] (= slug (:forked-from s))))
       (sort-by (fn [[k s]] [(or (:forked-at s) 0) (str k)]))
       (map key)))

(defn dfs-order
  "Every session in depth-first tree order (roots sorted by name) — the order
   tab-cycling walks, so MOVEMENT ON SCREEN ≡ movement in the tree."
  [index]
  (let [roots (->> index (remove (fn [[_ s]] (:forked-from s))) (map key) (sort-by str))
        walk  (fn walk [slug]
                (cons slug (mapcat walk (children-of index slug))))]
    (vec (mapcat walk roots))))

(defn short-name
  "Child display name: strip the `parent-` prefix ab! children carry
   (scratch-nucleus under scratch → nucleus). The INVERSE of the api ns's
   `variant-slug` (D5's cross-ns naming convention: the two MUST agree —
   the frame-test round-trip `short-name ∘ variant-slug` is the lock, so
   this pure surface never has to require the api layer). Public for the
   twin suite (memories/bb-jvm-private-var-twin-trap)."
  [slug parent]
  (let [n (name slug)
        p (some-> parent name (str "-"))]
    (if (and p (str/starts-with? n p) (> (count n) (count p)))
      (subs n (count p))
      n)))

(defn tree-lines
  "The fork forest as glyph-drawn lines for the tree pane; `current`
   highlighted (reverse video). Node ≡ name·depth, edge ≡ @branch-point.

   `_theme` is unused HERE (this pane paints with the raw `cmp/` attribute
   constants, not themed roles) but stays in the signature: every pane fn
   takes (…, theme, w) so `render` calls them uniformly. Note the shadow —
   `theme` is also the escapement ns alias this file uses, so an unused
   param of that name reads as used until the linter says otherwise."
  [index current _theme w]
  (let [walk (fn walk [slug parent prefix last?]
               (let [s     (get index slug)
                     label (str (short-name slug parent)
                                "·" (:depth s)
                                (when (:forked-at s) (str " @" (:forked-at s))))
                     conn  (cond (nil? parent) "" last? "└ " :else "├ ")
                     line  (str prefix conn
                                (if (= slug current)
                                  (str cmp/reverse-on-s " " label " " theme/reset-attrs-s)
                                  label))
                     kids  (vec (children-of index slug))
                     kid-prefix (str prefix (cond (nil? parent) "" last? "  " :else "│ "))]
                 (into [(cmp/truncate-display line w)]
                       (mapcat (fn [i k]
                                 (walk k slug kid-prefix (= i (dec (count kids)))))
                               (range (count kids)) kids))))
        roots (->> index (remove (fn [[_ s]] (:forked-from s))) (map key) (sort-by str))]
    (vec (mapcat #(walk % nil "" true) roots))))

(defn sessions-line
  "The registry index strip: every tape as slug·depth (↰parent when forked),
   current session in reverse video."
  [index current theme w]
  (->> (sort-by (comp str key) index)
       (map (fn [[slug {:keys [depth forked-from forked-at]}]]
              (let [cell (str (name slug) "·" depth
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
  [{:keys [slug]} index {:keys [buffer cursor]} w]
  (let [depth  (get-in index [slug :depth] 0)
        prompt (str (name slug) "[" depth "]> ")
        pw     (cmp/display-width prompt)
        shown  (str/replace buffer "\n" "⏎")
        avail  (max 1 (- w pw 1))
        ;; slide the view so the caret is always visible
        start  (max 0 (- cursor avail))
        view   (subs shown start (min (count shown) (+ start avail)))]
    {:text       (cmp/truncate-display (str prompt view) w)
     :cursor-col (+ pw (- cursor start) 1)}))

;; ── the frame ─────────────────────────────────────────────────────────────────

(def tree-pane-w
  "Left tree-pane width (borders incl). Below `two-pane-threshold` total
   columns the layout falls back to single-pane ⊕ sessions strip."
  26)

(def two-pane-threshold 70)

(defn frame
  "The WHOLE screen as one ANSI string ⊕ caret position — a pure function of
   (index ⊕ focused tape ⊕ ui-state ⊕ theme ⊕ term-w/h). Wide ≥70 cols: left
   tree pane (the map you move on — tab walks DFS, the highlight follows) ⊕
   right tape pane (where you are). Narrow: single pane ⊕ sessions strip.
   The impl half (tui.term) only emits this; headless tests assert on it
   directly.

   TWO arguments where there was one registry, because that is what the
   screen actually is: `index` ≡ every session as edges ∧ counts (the tree,
   the strip, the title's model), `tape` ≡ the message bodies of the FOCUSED
   session ONLY. The wire sends exactly this pair, from one server-side read
   (client/view). `tape` nil ≡ not fetched yet → `loading-lines`; `[]` ≡ open
   with no turns → `welcome-lines`. Keeping the distinction is what stops a
   Tab from painting the previous session's messages under the new title."
  [index tape {:keys [slug scroll events pending input overlay] :as state} theme term-w term-h]
  (let [two?    (>= term-w two-pane-threshold)
        box-h   (max 4 (- term-h (if two? 1 2)))
        inner-h (- box-h 2)
        tree-w  (if two? tree-pane-w 0)
        tape-w  (- term-w tree-w)
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
          (let [tl (if (nil? tape)
                     (loading-lines (- tape-w 2))
                     (let [tl (tape-lines tape (some? pending) theme (- tape-w 2))]
                       (if (seq tl) tl (welcome-lines (- tape-w 2)))))]
            (visible-window tl inner-h scroll)))
        buf     (StringBuilder.)]
    (when two?
      (let [tree-iw (- tree-w 2)
            ;; footer: the last few events, VERY short (dim, truncated) —
            ;; an index of what happened, never the payload. Events are DATA
            ;; maps now (registry ns, D3); `event-line` renders the one true
            ;; line (tolerant of a stale/cached plain string too).
            evs     (mapv #(theme/sgr-wrap theme/debug-color
                                           (cmp/truncate-display (registry/event-line %) tree-iw))
                          (take-last 5 events))
            ev-h    (if (seq evs) (inc (count evs)) 0)
            tree-h  (max 1 (- inner-h ev-h))
            tl      (tree-lines index slug theme tree-iw)
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
                                (title-line state index))
                       :scroll scroll
                       :theme theme
                       :body-lines (vec lines)})
    ;; narrow fallback: sessions strip ONLY — events live in the tree pane's
    ;; footer (left panel bottom), and narrow mode has no tree pane. No
    ;; bottom-footer event display (ratified: the footer receipt home is the
    ;; tree pane).
    (when-not two?
      (.append buf (cmp/move-to-s (inc box-h) 1))
      (.append buf (sessions-line index slug theme term-w)))
    (let [input-row (+ box-h (if two? 1 2))
          il        (input-line state index input term-w)]
      (.append buf (cmp/move-to-s input-row 1))
      (.append buf (:text il))
      {:s           (str buf)
       :cursor-row  input-row
       :cursor-col  (:cursor-col il)
       :scroll-used scroll-used})))

;; ── byte→logical-key decoder ──────────────────────────────────────────────────

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

;; ── line editor ───────────────────────────────────────────────────────────────

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
