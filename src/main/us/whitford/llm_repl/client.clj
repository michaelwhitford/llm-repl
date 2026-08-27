(ns us.whitford.llm-repl.client
  "The core-client SEAM — the TUI drives THIS, never core directly.

   For its whole life the TUI was an embedding of core: the frame deref'd
   core/sessions* in-process, tui-submit! called core/eval! in-process. That
   made the TUI the ONE privileged surface — every other client (editors,
   models) attached over nREPL. This seam dissolves that privilege: the TUI is
   now a PURE nREPL client of a separate, persistent core (a local daemon, or a
   container). There is ONE impl — `RemoteCore` over net.clj — because there is
   no longer any in-process core to embed. 'Local' and 'remote' differ only in
   WHERE the core lives; the wire is the same. Equal-clients thesis, complete:
   humans, models, editors — and the TUI itself — all drive the same core over
   the same wire.

   RemoteCore: submissions become nREPL eval messages (the clojure half runs
   where the core lives — next to the work dir and the model; prose turns fire
   there too); the view is a cache atom a poll thread keeps fresh. The seam's
   shape keeps the frame UNCHANGED: `registry` and `events` return DEREF-ABLES
   (cache atoms) the frame derefs per-frame — tui/start! can't tell it's remote.
   Focus (use!) is a LOCAL-surface concern and stays in the wire layer, never
   sent across.

   The protocol stays an OPEN SLOT (λ extend): one impl today, but a future
   transport or an in-process variant plugs in without touching the TUI."
  (:require
   [clojure.edn :as edn]
   [us.whitford.llm-repl.net :as net]))

(defprotocol CoreClient
  (registry [_]
    "A deref-able yielding the registry snapshot {slug → session} the frame renders.")
  (events [_]
    "A deref-able yielding the receipt vector the tree-pane footer renders.")
  (ensure! [_ slug opts]
    "Open/create session `slug` (opts forwarded).")
  (prose! [_ slug text]
    "Commit ONE prose turn on `slug` (blocking; caller may wrap in a future).")
  (form [_ text]
    "Eval a clojure form. Returns a uniform display shape:
     {:value <printed-str> :out <str> :err <str?> :suppress-echo? <bool>}
     — :suppress-echo? true when the value is itself a command receipt
     (carries :repl/id), so the wire layer doesn't double the tree receipt.")
  (help-text [_]
    "The (help) string, rendered by the wire layer into the overlay.")
  (notify! [_ cb]
    "Begin change notification: `cb` (0-arg) is called whenever the view
     changes (a tape mutation, a receipt). Local ≡ add-watch; remote ≡ poll.")
  (shutdown! [_]
    "Tear down watches / poll thread / sockets."))

;; ── RemoteCore — the nREPL-client impl (the ONLY impl) ──────────────────────────

(defn- open-conn
  "Connect + clone a session + require the registry ns in it (registry-direct:
   the poll/fetch strings below read `us.whitford.llm-repl.registry`'s atoms
   fully-qualified, no alias dependence — this require only ensures the ns is
   LOADED in the remote process; harmless if already loaded). Returns a conn
   map carrying :session (the cloned id — stable *ns*, isolated *out*)."
  [host port]
  (let [conn (net/connect host port)
        sid  (net/clone-session conn)]
    (net/eval-msg conn "(require '[us.whitford.llm-repl.registry :as reg])" sid)
    (assoc conn :session sid)))

(defn- fetch
  "Eval `code` on `conn`'s session and read the printed value as EDN, or nil
   on any failure (a dropped connection must not crash the poll thread)."
  [conn code]
  (try
    (let [r (net/eval-msg conn code (:session conn))]
      (when (net/ok? r)
        (edn/read-string (net/value r))))
    (catch Throwable _ nil)))

(defrecord RemoteCore [host port pconn sconn slock reg-cache ev-cache running? poll]
  CoreClient
  (registry [_] reg-cache)
  (events [_] ev-cache)
  (ensure! [_ slug opts]
    ;; bare (:refer :all on sconn, see `remote`) — no alias needed.
    (locking slock
      (net/eval-msg sconn (format "(open! %s %s)" (pr-str slug) (pr-str opts))
                    (:session sconn))))
  (prose! [_ slug text]
    ;; pr-str makes text a safe literal; bare (sconn refers the api ns).
    ;; Blocks in the CONTAINER until the turn completes — meanwhile the poll
    ;; thread (separate socket) keeps the UI live, receipts and all.
    (locking slock
      (net/eval-msg sconn (format "(eval! %s %s)" (pr-str slug) (pr-str text))
                    (:session sconn))))
  (form [_ text]
    (locking slock
      (let [r (net/eval-msg sconn text (:session sconn))
            v (net/value r)]
        (if (net/ok? r)
          {:value          v
           :out            (:out r)
           :suppress-echo? (boolean (and v (re-find #":repl/id" v)))}
          {:err (or (:ex r) (:err r) "error") :out (:out r)}))))
  (help-text [_]
    (locking slock
      (let [r (net/eval-msg sconn "(us.whitford.llm-repl/help)" (:session sconn))]
        (if (net/ok? r) (edn/read-string (net/value r)) "help unavailable (attach lost)"))))
  (notify! [_ cb]
    ;; Prime the caches so the FIRST frame has data, then poll deltas. The
    ;; container's events* is the universal change signal (every tape mutation
    ;; emits a receipt) — but the registry can change without a new receipt
    ;; (rare), so we watch both. Phase 2 replaces this with a long-poll tail.
    (when-let [reg (fetch pconn "@us.whitford.llm-repl.registry/sessions*")] (reset! reg-cache reg))
    (when-let [ev  (fetch pconn "@us.whitford.llm-repl.registry/events*")]  (reset! ev-cache ev))
    (cb)
    (reset! running? true)
    (let [t (Thread.
             (fn []
               (while @running?
                 (let [reg (fetch pconn "@us.whitford.llm-repl.registry/sessions*")
                       ev  (fetch pconn "@us.whitford.llm-repl.registry/events*")
                       changed? (atom false)]
                   (when (and reg (not= reg @reg-cache)) (reset! reg-cache reg) (reset! changed? true))
                   (when (and ev  (not= ev  @ev-cache))  (reset! ev-cache ev)   (reset! changed? true))
                   (when @changed? (cb)))
                 (Thread/sleep 150))))]
      (.setDaemon t true)
      (.setName t "llm-repl-remote-poll")
      (.start t)
      (reset! poll t)))
  (shutdown! [_]
    (reset! running? false)
    (net/close pconn)
    (net/close sconn)))

(defn remote
  "Attach to a core at `host`:`port` over nREPL. Two sockets: a poll socket
   (view refresh) and a submit socket (evals) — so a blocking prose turn never
   stalls the repaint. Throws if the connect fails."
  [host port]
  (let [pconn (open-conn host port)
        sconn (open-conn host port)]
    ;; the submit session refers the api ns so bare (open!)/(fork!) forms
    ;; resolve — both a typed (form) AND ensure!/prose!'s own format strings
    ;; above (registry-direct: no `c` alias, this is the only api access
    ;; sconn needs)
    (net/eval-msg sconn "(require '[us.whitford.llm-repl :refer :all])" (:session sconn))
    ;; prime the view caches so the FIRST frame (and initial-slug pick) have
    ;; data before notify! spins up the poll thread
    (->RemoteCore host port pconn sconn (Object.)
                  (atom (or (fetch pconn "@us.whitford.llm-repl.registry/sessions*") {}))
                  (atom (or (fetch pconn "@us.whitford.llm-repl.registry/events*") []))
                  (atom false) (atom nil))))
