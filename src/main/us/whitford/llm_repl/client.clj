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

   The view protocol is D3's, complete (the phase-2 the old poll comment
   promised): the poll thread LONG-POLLS `registry/wait-for-event!` (parks
   server-side, wakes on the first new receipt) and checks `registry/version*`
   (a tiny number) on every wake — the full registry crosses the wire ONLY
   when the version moved. When the server predates `wait-for-event!` the
   client falls back to version-polling on an interval (D3 names this
   fallback). Either way the v0.2.0 sin — serializing every tape body several
   times a second — is gone.

   Attach-loss is a CONTRACT, not a condition to smooth over (design §
   invariants; memories/tui-dead-daemon-silent): `fetch` returns
   {:ok v} | {:err reason} — the error signal is DATA, never collapsed to
   nil — and `max-failures` consecutive failed cycles flip `status` to
   {:attach :lost}. The wire layer sees it on the next notify and fails loud
   (teardown ⊕ message ⊕ exit). A dead daemon must never render as a live
   one; the one thing the poll thread still never does is crash.

   Focus (use!) is a LOCAL-surface concern and stays in the wire layer, never
   sent across.

   The protocol stays an OPEN SLOT (λ extend): one impl today, but a future
   transport or an in-process variant plugs in without touching the TUI."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [us.whitford.llm-repl.net :as net]))

(defprotocol CoreClient
  (registry [_]
    "A deref-able yielding the registry snapshot {slug → session} the frame renders.")
  (events [_]
    "A deref-able yielding the receipt vector the tree-pane footer renders.")
  (status [_]
    "A deref-able yielding the attach health: {:attach :ok} while the wire
     answers; {:attach :lost :reason r :at ms} once `max-failures`
     consecutive poll cycles fail. The wire layer MUST honor :lost by failing
     loud (attach contract: unreachable → message ∧ exit, never a stale
     render).")
  (ensure! [_ slug opts]
    "Open/create session `slug` (opts forwarded).")
  (prose! [_ slug text]
    "Commit ONE prose turn on `slug` (blocking; caller may wrap in a future).")
  (form [_ text]
    "Eval a clojure form. Returns a uniform display shape:
     {:value <printed-str> :out <str> :err <str?> :suppress-echo? <bool>}
     — :suppress-echo? true when the value is itself a command receipt
     (structural: it READS as a map carrying :repl/id — `command-receipt?`),
     so the wire layer doesn't double the tree receipt.")
  (help-text [_]
    "The (help) string, rendered by the wire layer into the overlay.")
  (notify! [_ cb]
    "Begin change notification: `cb` (0-arg) is called whenever the view
     changes (a tape mutation, a receipt) AND once on attach-loss (after
     `status` flips — the callback is the wake-up, `status` is the reason).")
  (shutdown! [_]
    "Tear down watches / poll thread / sockets."))

;; ── D4 — structural suppress-echo ───────────────────────────────────────────

(defn command-receipt?
  "True iff `printed` (an eval result's printed value) READS as a map carrying
   `:repl/id` — the marker every api command result carries (D4: command
   results are identified STRUCTURALLY; the client never regex-sniffs printed
   strings). The old `#\":repl/id\"` regex matched the SPELLING anywhere in
   any value — a string literal `\":repl/id\"` would suppress its own echo.
   Reading the value as EDN and testing the actual key tests the CONTRACT.
   Values that don't read (host objects, unknown tagged literals) are by
   definition not command receipts → false, never a throw.

   Public for the twin suite (called directly — memories/
   bb-jvm-private-var-twin-trap); still INTERNAL surface per library-contract."
  [printed]
  (boolean
   (and printed
        (let [v (try (edn/read-string printed)
                     (catch Throwable _ ::unreadable))]
          (and (map? v) (contains? v :repl/id))))))

;; ── RemoteCore — the nREPL-client impl (the ONLY impl) ──────────────────────

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
  "Eval `code` on `conn`'s session and read the printed value as EDN.
   Returns {:ok v} | {:err reason} — the failure is DATA the poll cycle
   counts toward attach-loss, never a nil that reads as \"no change\"
   (collapsing it was exactly how a dead daemon kept rendering as a live
   one — memories/tui-dead-daemon-silent). Still never throws: a dropped
   connection must not crash the poll thread, it must be REPORTED by it."
  [conn code]
  (try
    (let [r (net/eval-msg conn code (:session conn))]
      (if (net/ok? r)
        {:ok (edn/read-string (net/value r))}
        {:err (or (:ex r)
                  (some-> (:err r) str/trim)
                  (str "status " (:status r)))}))
    (catch Throwable t
      {:err (or (ex-message t) (str (type t)))})))

;; ── the poll cycle (D3) ─────────────────────────────────────────────────────

(defn poll-cycle!
  "ONE synchronous view-refresh cycle — the poll thread just loops this while
   running (public for the twin suite: tests drive it with a stubbed
   `:fetch-fn`; still INTERNAL surface per library-contract).

   `env` (the loop's fixed dependencies):
     :fetch-fn   (fn [code] → {:ok v} | {:err reason})  — the wire
     :reg-cache  atom, the frame's registry deref-able
     :ev-cache   atom, the frame's events deref-able (ring, `:events-cap`)
     :status*    atom, the attach-health deref-able (`status`)
     :cb         0-arg change callback
     :max-failures :backoff-ms :interval-ms :long-poll-ms :events-cap — knobs
     (defaulted; tests inject small ones)

   `state` (threaded cycle → cycle):
     :mode         :long-poll (park on wait-for-event!) | :version-poll
     :last-version last seen registry/version* value
     :since-id     highest event :id already in ev-cache
     :failures     consecutive failed cycles so far

   Cycle: [long-poll mode] park on `wait-for-event! since-id` (bounded,
   `:long-poll-ms`) → on wake check `version*` (tiny) → registry crosses the
   wire ONLY if the version moved ([version-poll mode] the event delta rides
   `events-since` the same way). Any fetch error → failure path: increment
   :failures, back off; at `:max-failures` flip `status*` to :lost, fire `cb`
   once (the wake-up), and return :lost? true — the loop stops, the wire
   layer fails loud. Any success resets :failures.

   Returns the next `state`; :sleep-ms tells the loop how long to nap before
   the next cycle (nil ≡ none — in long-poll mode the server park IS the
   pacing), :lost? tells it to stop."
  [{:keys [fetch-fn reg-cache ev-cache status* cb
           max-failures backoff-ms interval-ms long-poll-ms events-cap]
    :or   {max-failures 3 backoff-ms 300 interval-ms 150
           long-poll-ms 5000 events-cap 200}}
   {:keys [mode last-version since-id failures] :as state}]
  (let [state (dissoc state :sleep-ms :lost?)
        fail  (fn [reason]
                (let [n (inc (or failures 0))]
                  (if (>= n max-failures)
                    (do (reset! status* {:attach :lost :reason reason
                                         :at (System/currentTimeMillis)})
                        (cb)
                        (assoc state :failures n :lost? true))
                    (assoc state :failures n :sleep-ms backoff-ms))))
        wake  (when (= :long-poll mode)
                (fetch-fn (str "(us.whitford.llm-repl.registry/wait-for-event! "
                               (or since-id 0) " " long-poll-ms ")")))]
    (if (and wake (:err wake))
      (fail (:err wake))
      (let [ver-r (fetch-fn "@us.whitford.llm-repl.registry/version*")]
        (if (:err ver-r)
          (fail (:err ver-r))
          (let [ver          (:ok ver-r)
                ver-changed? (not= ver last-version)
                reg-r        (when ver-changed?
                               (fetch-fn "@us.whitford.llm-repl.registry/sessions*"))
                evd-r        (when (and (= :version-poll mode) ver-changed?)
                               (fetch-fn (str "(us.whitford.llm-repl.registry/events-since "
                                              (or since-id 0) ")")))]
            (cond
              (:err reg-r) (fail (:err reg-r))
              (:err evd-r) (fail (:err evd-r))
              :else
              (let [evs      (into (vec (:ok wake)) (:ok evd-r))
                    changed? (boolean (or (seq evs) ver-changed?))]
                (when (seq evs)
                  (swap! ev-cache #(vec (take-last events-cap (into % evs)))))
                (when ver-changed?
                  (reset! reg-cache (:ok reg-r)))
                (when changed? (cb))
                (assoc state
                       :failures     0
                       :last-version ver
                       :since-id     (apply max (or since-id 0) (keep :id evs))
                       :sleep-ms     (when (= :version-poll mode) interval-ms))))))))))

(defrecord RemoteCore [host port pconn sconn slock reg-cache ev-cache status* running? poll]
  CoreClient
  (registry [_] reg-cache)
  (events [_] ev-cache)
  (status [_] status*)
  (ensure! [_ slug opts]
    ;; bare (:refer :all on sconn, see `remote`) — no alias needed.
    (locking slock
      (net/eval-msg sconn (format "(open! %s %s)" (pr-str slug) (pr-str opts))
                    (:session sconn))))
  (prose! [_ slug text]
    ;; pr-str makes text a safe literal; bare (sconn refers the api ns).
    ;; Blocks in the CORE until the turn completes — meanwhile the poll
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
           :suppress-echo? (command-receipt? v)}
          {:err (or (:ex r) (:err r) "error") :out (:out r)}))))
  (help-text [_]
    (locking slock
      (let [r (net/eval-msg sconn "(us.whitford.llm-repl/help)" (:session sconn))]
        (if (net/ok? r) (edn/read-string (net/value r)) "help unavailable (attach lost)"))))
  (notify! [_ cb]
    ;; Re-prime the caches so the FIRST frame has data, then start the D3
    ;; cycle loop. Mode is probed ONCE: a server carrying wait-for-event!
    ;; gets the long-poll; anything older gets the version-poll fallback
    ;; (a probe failure also lands there — if the wire is truly dead the
    ;; cycles will count it toward attach-loss immediately).
    (when-let [reg (:ok (fetch pconn "@us.whitford.llm-repl.registry/sessions*"))]
      (reset! reg-cache reg))
    (when-let [ev (:ok (fetch pconn "@us.whitford.llm-repl.registry/events*"))]
      (reset! ev-cache ev))
    (cb)
    (reset! running? true)
    (let [probe (fetch pconn "(some? (resolve 'us.whitford.llm-repl.registry/wait-for-event!))")
          env   {:fetch-fn  (fn [code] (fetch pconn code))
                 :reg-cache reg-cache
                 :ev-cache  ev-cache
                 :status*   status*
                 :cb        cb}
          init  {:mode         (if (true? (:ok probe)) :long-poll :version-poll)
                 :last-version (:ok (fetch pconn "@us.whitford.llm-repl.registry/version*"))
                 :since-id     (apply max 0 (keep :id @ev-cache))
                 :failures     0}
          t     (Thread.
                 (fn []
                   (loop [state init]
                     (when @running?
                       (let [state' (poll-cycle! env state)]
                         (when-not (:lost? state')
                           (when-let [ms (:sleep-ms state')]
                             (Thread/sleep (long ms)))
                           (recur state')))))))]
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
                  (atom (or (:ok (fetch pconn "@us.whitford.llm-repl.registry/sessions*")) {}))
                  (atom (or (:ok (fetch pconn "@us.whitford.llm-repl.registry/events*")) []))
                  (atom {:attach :ok})
                  (atom false) (atom nil))))
