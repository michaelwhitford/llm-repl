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
  "Render a tape (canonical messages) ⊕ system events ⊕ pending flag into
   colored, wrapped body lines for the tape pane. Events (form results,
   errors) trail the tape; a pending completion shows a thinking marker."
  [tape events pending? theme w]
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
        (into (mapcat #(emit (role-label :event) theme/debug-color %)) events)
        (cond-> pending?
          (conj (str (theme/sgr-wrap theme/chart-color "  …   ")
                     (theme/paint theme :status/waiting "thinking")))))))

(defn visible-window
  "Apply scroll (lines up from the tail) to `lines`, yielding exactly the
   window that fits `h` rows ⊕ the {:pos :total} scroll indicator."
  [lines h scroll]
  (let [total  (count lines)
        scroll (max 0 (min scroll (max 0 (- total h))))
        shown  (take-last h (drop-last scroll lines))]
    {:lines shown
     :scroll {:pos (- total scroll) :total total}}))

;; ── pure: chrome ──────────────────────────────────────────────────────────────

(defn title-line
  [{:keys [slug nrepl-port]} reg]
  (let [model (get-in reg [slug :config :model])]
    (str "llm-repl · " slug " · " model " · nREPL :" nrepl-port)))

(defn sessions-line
  "The registry index strip: every tape as slug·depth (↰parent when forked),
   current session in reverse video."
  [reg current theme w]
  (->> (sort-by (comp str key) reg)
       (map (fn [[slug {:keys [tape forked-from]}]]
              (let [cell (str (name slug) "·" (count tape)
                              (when forked-from (str "↰" (name forked-from))))]
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

(defn frame
  "The WHOLE screen as one ANSI string ⊕ caret position — a pure function of
   (registry-snapshot ⊕ ui-state ⊕ theme ⊕ term-w/h). The impl half only
   emits this; headless tests assert on it directly."
  [reg {:keys [slug scroll events pending input] :as state} theme term-w term-h]
  (let [box-h   (max 4 (- term-h 2))
        inner-w (- term-w 2)
        inner-h (- box-h 2)
        tape    (get-in reg [slug :tape])
        lines   (tape-lines tape events (some? pending) theme inner-w)
        {:keys [lines scroll]} (visible-window lines inner-h scroll)
        buf     (StringBuilder.)
        _       (cmp/draw-box buf {:row 1 :col 1 :w term-w :h box-h
                                   :title (title-line state reg)
                                   :scroll scroll
                                   :theme theme
                                   :body-lines (vec lines)})
        _       (.append buf (cmp/move-to-s (inc box-h) 1))
        _       (.append buf (cmp/truncate-display (sessions-line reg slug theme term-w) term-w))
        il      (input-line state reg input term-w)
        _       (.append buf (cmp/move-to-s (+ box-h 2) 1))
        _       (.append buf (:text il))]
    {:s          (str buf)
     :cursor-row (+ box-h 2)
     :cursor-col (:cursor-col il)}))

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
          {:keys [s cursor-row cursor-col]} (frame @(:registry s) s theme w h)]
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

(defn cycle-slug!
  "Point the TUI at the next registry session (sorted order, wraps). Opens
   nothing — cycles over what exists."
  [state]
  (swap! state (fn [{:keys [registry slug] :as s}]
                 (let [slugs (vec (sort-by str (keys @registry)))
                       i     (.indexOf ^clojure.lang.PersistentVector slugs slug)
                       slug' (if (seq slugs)
                               (nth slugs (mod (inc i) (count slugs)))
                               slug)]
                   (assoc s :slug slug' :scroll 0 :render-dirty true)))))

(defn- input-loop!
  "The input thread body: raw mode, then decode→dispatch until quit.
   Loop-level keys (session/viewport/quit) here; editing keys → edit-step.
   `on-submit` ≡ (fn [text]) — the wire layer decides chat vs form."
  [{:keys [^Terminal terminal state on-submit] :as h}]
  (.enterRawMode terminal)
  (let [rdr   ^NonBlockingReader (.reader terminal)
        read! (fn [t] (if (pos? ^long t) (.read rdr (long t)) (.read rdr)))
        page  (fn [] (max 1 (- (:term-h @state) 6)))]
    (loop []
      (let [k (key-from-bytes read!)]
        (cond
          (contains? #{:eof :ctrl-c :ctrl-d} k)
          (do (stop! h) (System/exit 0))

          (= k :tab)  (cycle-slug! state)
          (= k :pgup) (swap! state #(-> % (update :scroll + (page)) (assoc :render-dirty true)))
          (= k :pgdn) (swap! state #(-> % (update :scroll (fn [n] (max 0 (- n (page))))) (assoc :render-dirty true)))

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
   half) and the wire layer drive. `registry` ≡ core's sessions* atom
   (referenced, not copied — the frame reads it live). Caller must check
   interactive-terminal? first."
  [{:keys [registry slug nrepl-port on-stop on-submit]}]
  (let [terminal (-> (TerminalBuilder/builder) (.system true) (.build))
        state    (atom {:registry     registry
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
                  :on-submit on-submit}]
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
