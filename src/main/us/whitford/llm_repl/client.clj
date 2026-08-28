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

   The payload is a PROJECTION, not the registry (2026-08-28): the poll used
   to fetch `@sessions*` whole — every tape body, on every version bump (99KB
   at 50 sessions, 594KB at 300). `registry/view` answers with the compact
   index ⊕ the FOCUSED session's tape, from one deref: 27× smaller at a
   constant ratio, and atomic by construction. Audit ∧ measurements ≡
   knowledge/tui-design-rules.md.

   THREE sockets, and the count is forced by the server, not by taste:
   babashka.nrepl runs ONE thread per CONNECTION and serializes that
   connection's messages regardless of session (measured — memories/
   nrepl-concurrency-is-per-socket), so every channel that must make progress
   while another is busy needs a socket of its own. Poll parks in
   `wait-for-event!`; submit blocks for a whole completion; focus fetches
   would wait behind either. Hence pconn ∧ sconn ∧ fconn. Under bb, sockets
   ARE the multiplex.

   Focus (use!, Tab) is a LOCAL-surface concern — it never becomes registry
   state (each attached client looks where it likes). It is held HERE, in the
   client, purely as the parameter of the next fetch.

   The protocol stays an OPEN SLOT (λ extend): one impl today, but a future
   transport or an in-process variant plugs in without touching the TUI."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [us.whitford.llm-repl.net :as net]))

(defprotocol CoreClient
  (view [_]
    "A deref-able yielding the VIEW the frame renders:
     `{:index {slug → edges ∧ counts} :slug <focused> :tape [messages]}`.

     One deref, one consistent picture — the index (every session, no message
     bodies) and the FOCUSED session's tape arrive from ONE server-side
     `registry/view` call and live in ONE atom, so a repaint can never catch
     the tree at version N and the tape pane at N−1 (split the PAYLOAD, never
     the round-trip). `:tape` is nil when the current focus has no fetched
     tape YET — distinct from `[]` ≡ an open session with no turns.")
  (focus! [_ slug]
    "Point the view at `slug`: record it (so subsequent poll fetches carry
     its tape) and fetch that tape NOW. Blocking — the caller may wrap it in
     a future, same convention as `prose!`. Fires the notify callback when
     the tape lands, so the surface repaints without waiting for the next
     registry change.")
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

;; ── the view cache (projection ⊕ focus race guard) ──────────────────────────

(defn view-code
  "The ONE fetch string for the view payload — `registry/view` evaluated with
   the client's current focus. Registry-direct (fully qualified, no alias
   dependence — `open-conn` only guarantees the ns is LOADED). Public for the
   twin suite (memories/bb-jvm-private-var-twin-trap); INTERNAL surface."
  [focus]
  (str "(us.whitford.llm-repl.registry/view " (pr-str focus) ")"))

(defn apply-view
  "Fold a fetched `{:index :slug :tape}` into the view `cache`, given the
   focus that is CURRENT at apply time — PURE, and the whole race guard.

   Two fetchers write this cache (the poll thread and `focus!`) and focus can
   change between a fetch being issued and its answer landing. The rule:

     index — ALWAYS applied (registry-wide, focus-independent)
     tape  — applied only when it was fetched FOR the current focus
             else keep what we hold if that was for the current focus,
             else nil ≡ NOT-A-TAPE (we have nothing for this pane yet)

   So a poll answer that was issued for the session you just Tab'd away from
   updates the tree and CANNOT paint its tape under the new title, and a
   focus answer that arrives after you Tab'd twice is discarded the same way.
   nil is the honest render (a placeholder), never someone else's messages."
  [cache {:keys [index slug tape]} focus]
  {:index index
   :slug  focus
   :tape  (cond
            (= slug focus)         tape
            (= (:slug cache) focus) (:tape cache)
            :else                   nil)})

;; ── the poll cycle (D3) ─────────────────────────────────────────────────────

(defn poll-cycle!
  "ONE synchronous view-refresh cycle — the poll thread just loops this while
   running (public for the twin suite: tests drive it with a stubbed
   `:fetch-fn`; still INTERNAL surface per library-contract).

   `env` (the loop's fixed dependencies):
     :fetch-fn   (fn [code] → {:ok v} | {:err reason})  — the wire
     :view-cache atom, the frame's view deref-able ({:index :slug :tape})
     :focus*     atom, the surface's current focus slug (read at fetch time,
                 re-read at apply time — `apply-view` owns the race)
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
   `:long-poll-ms`) → on wake check `version*` (tiny) → the VIEW crosses the
   wire ONLY if the version moved ([version-poll mode] the event delta rides
   `events-since` the same way). Any fetch error → failure path: increment
   :failures, back off; at `:max-failures` flip `status*` to :lost, fire `cb`
   once (the wake-up), and return :lost? true — the loop stops, the wire
   layer fails loud. Any success resets :failures.

   Returns the next `state`; :sleep-ms tells the loop how long to nap before
   the next cycle (nil ≡ none — in long-poll mode the server park IS the
   pacing), :lost? tells it to stop."
  [{:keys [fetch-fn view-cache focus* ev-cache status* cb
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
                               (fetch-fn (view-code (some-> focus* deref))))
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
                  ;; focus re-read HERE, not reused from the fetch: it may
                  ;; have moved while the answer was in flight (apply-view)
                  (swap! view-cache apply-view (:ok reg-r) (some-> focus* deref)))
                (when changed? (cb))
                (assoc state
                       :failures     0
                       :last-version ver
                       :since-id     (apply max (or since-id 0) (keep :id evs))
                       :sleep-ms     (when (= :version-poll mode) interval-ms))))))))))

(defrecord RemoteCore [host port pconn sconn fconn slock flock
                       view-cache ev-cache status* focus* cb* running? poll]
  CoreClient
  (view [_] view-cache)
  (events [_] ev-cache)
  (status [_] status*)
  (focus! [_ slug]
    ;; Record FIRST (so any fetch issued after this point carries the new
    ;; focus), then fetch NOW on the focus socket — the poll socket is parked
    ;; in wait-for-event! and the submit socket may be mid-completion, and
    ;; under bb a busy connection serializes everything behind it
    ;; (memories/nrepl-concurrency-is-per-socket). Without this fetch a Tab
    ;; would show a placeholder until the next registry change — up to the
    ;; long-poll bound, or forever on an idle core.
    ;; A failed focus fetch is NOT counted toward attach-loss: the poll thread
    ;; owns attach health (one counter, one contract). The pane simply stays
    ;; on its placeholder until a later fetch lands — and if the wire is truly
    ;; dead, the poll cycles are already counting it.
    (reset! focus* slug)
    (locking flock
      (when-let [v (:ok (fetch fconn (view-code slug)))]
        (swap! view-cache apply-view v @focus*)))
    (when-let [cb @cb*] (cb)))
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
    (reset! cb* cb)
    (when-let [v (:ok (fetch pconn (view-code @focus*)))]
      (swap! view-cache apply-view v @focus*))
    (when-let [ev (:ok (fetch pconn "@us.whitford.llm-repl.registry/events*"))]
      (reset! ev-cache ev))
    (cb)
    (reset! running? true)
    (let [probe (fetch pconn "(some? (resolve 'us.whitford.llm-repl.registry/wait-for-event!))")
          env   {:fetch-fn   (fn [code] (fetch pconn code))
                 :view-cache view-cache
                 :focus*     focus*
                 :ev-cache   ev-cache
                 :status*    status*
                 :cb         cb}
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
    (net/close sconn)
    (net/close fconn)))

(defn remote
  "Attach to a core at `host`:`port` over nREPL. THREE sockets, one per
   channel that must make progress independently — because babashka.nrepl
   serializes a CONNECTION's messages (measured; ns docstring):

     pconn  poll — parks in wait-for-event!, wakes on change
     sconn  submit — blocks for the duration of a prose turn
     fconn  focus — on-demand view fetches (Tab, use!), must answer while
            the other two are busy, which is the entire reason it exists

   Throws if any connect fails."
  [host port]
  (let [pconn (open-conn host port)
        sconn (open-conn host port)
        fconn (open-conn host port)]
    ;; the submit session refers the api ns so bare (open!)/(fork!) forms
    ;; resolve — both a typed (form) AND ensure!/prose!'s own format strings
    ;; above (registry-direct: no `c` alias, this is the only api access
    ;; sconn needs)
    (net/eval-msg sconn "(require '[us.whitford.llm-repl :refer :all])" (:session sconn))
    ;; prime the view caches so the FIRST frame (and the initial-slug pick,
    ;; which reads :index) have data before notify! spins up the poll thread.
    ;; Focus is still nil here — the surface has not chosen a pane yet, so the
    ;; primed view is index-only (:tape nil) and the wire layer calls focus!
    ;; the moment it picks one.
    (->RemoteCore host port pconn sconn fconn (Object.) (Object.)
                  (atom (or (:ok (fetch pconn (view-code nil)))
                            {:index {} :slug nil :tape nil}))
                  (atom (or (:ok (fetch pconn "@us.whitford.llm-repl.registry/events*")) []))
                  (atom {:attach :ok})
                  (atom nil) (atom nil)
                  (atom false) (atom nil))))
