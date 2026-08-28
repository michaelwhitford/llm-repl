(ns us.whitford.llm-repl.tui.term
  "The TUI's IMPL half (v0.3.0 step 6 — D5: the testable cut is FILE
   topology): JLine terminal, raw mode, alt screen, ANSI emission, the render
   ticker, the input thread, signal/shutdown plumbing. Only MOVES BYTES —
   every decision about WHAT to draw or how a key edits state lives in
   `tui.frame` (pure, headless-tested); this ns is the one place a terminal
   is touched, and stays out of the test suite by construction.

   The two PROVEN patterns from escapement.tui are COPIED here, not depended
   on (that ns is hardwired to escapement's event vocabulary):

     render loop ≡ one state atom ⊕ :render-dirty flag ⊕ ~30fps daemon ticker
       — ANY thread swaps state + flips the flag; ONE thread repaints.
       The client's notify callback (wire ns) rides this: an attached nREPL
       client's eval! appears in the terminal within ~33ms, no locking.
     input       ≡ JLine raw mode ⊕ frame/key-from-bytes (pure decoder)."
  (:require
   [clojure.string :as str]
   [escapement.tui.compositor :as cmp]
   [escapement.tui.theme :as theme]
   [us.whitford.llm-repl.tui.frame :as frame])
  (:import
   (org.jline.terminal Terminal TerminalBuilder)
   (org.jline.utils NonBlockingReader)))

;; ── ANSI plumbing ─────────────────────────────────────────────────────────────

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

;; ── render loop ───────────────────────────────────────────────────────────────

(defn request-render!
  "Flip the dirty flag — cheap, non-blocking, callable from ANY thread (the
   client's notify callback, eval workers, the input thread). The ticker
   repaints."
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
          ;; frame — `frame/frame` stays pure, headless tests pass :events
          ;; directly
          s (cond-> s
              (:events-ref s) (assoc :events (vec @(:events-ref s))))
          {:keys [s cursor-row cursor-col scroll-used]} (frame/frame @(:registry s) s theme w h)]
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

;; ── view state mutators (wire layer ∧ input loop drive these) ─────────────────

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
                 (let [slugs (frame/dfs-order @registry)
                       i     (.indexOf ^clojure.lang.PersistentVector slugs slug)
                       slug' (if (seq slugs)
                               (nth slugs (mod (inc i) (count slugs)))
                               slug)]
                   (assoc s :slug slug' :scroll 0 :render-dirty true)))))

;; ── input thread ──────────────────────────────────────────────────────────────

(defn- input-loop!
  "The input thread body: raw mode, then decode→dispatch until quit.
   Loop-level keys (session/viewport/quit) here; editing keys →
   frame/edit-step. `on-submit` ≡ (fn [text]) — the wire layer decides chat
   vs form."
  [{:keys [^Terminal terminal state on-submit on-help] :as h}]
  (.enterRawMode terminal)
  (let [rdr   ^NonBlockingReader (.reader terminal)
        read! (fn [t] (if (pos? ^long t) (.read rdr (long t)) (.read rdr)))
        page  (fn [] (max 1 (- (:term-h @state) 6)))]
    (loop []
      (let [k (frame/key-from-bytes read!)]
        (cond
          (contains? #{:eof :ctrl-c :ctrl-d} k)
          (do (stop! h) (System/exit 0))

          ;; Esc: overlay-first (dismiss), else the editor's clear-buffer
          (= k :esc)
          (if (:overlay @state)
            (dismiss-overlay! state)
            (swap! state (fn [s] (-> s (assoc :input (:input (frame/edit-step (:input s) k)))
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
                           (let [{:keys [input submit]} (frame/edit-step (:input s) k)]
                             (vreset! submitted submit)
                             (assoc s :input input :render-dirty true))))
            (when-let [text @submitted]
              (on-submit text)))))
      (when-not @(:stopped? h)
        (recur)))))

;; ── boot ──────────────────────────────────────────────────────────────────────

(defn start!
  "Boot the TUI: alt screen, bracketed paste, render ticker. Returns the
   handle {:state :terminal :lock :theme :stopped?} the input loop and the
   wire layer drive. `registry` ≡ the client's registry deref-able,
   `events` ≡ the client's events deref-able (BOTH referenced, not copied —
   the frame reads them live; every client's receipts show, not just this
   surface's). Caller must check interactive-terminal? first."
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
