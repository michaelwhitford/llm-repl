(ns us.whitford.llm-repl.registry
  "The `runtime` layer of the v0.3.0 architecture (mementum/knowledge/design/
   architecture.md §§ D2 D3): the ONE mutable place. Everything above this ns
   (io, api, surfaces) reads/writes the registry only through the chokepoint
   below — `mutate!` — never a bare `swap!` on `sessions*`. Zero io deps: this
   ns holds EDN values, it does not interpret them (no tape dep needed — a
   session's :tape is just a vector this ns never looks inside).

   THREE atoms, one discipline:

     sessions*  {slug → session}         the continuation registry
     events*    [receipt …]              global UI chrome (bounded ring)
     version*   n                        bumped on EVERY mutation, either atom

   D2 — every registry mutation is a `swap!` (well, `swap-vals!`, so the
   caller can see what it replaced) with a PURE fn of the CURRENT state.
   `eval!`-style read-complete-store races (last-write-wins, v0.2.0) dissolve
   because there is no read-then-later-store gap for a concurrent writer to
   land in unnoticed: the caller who lost the race sees it in `old` and
   decides (append-anyway ⊕ raced receipt — never silent, never a lock).

   D3 — the chokepoint makes the invariants STRUCTURAL, not documented:
     - EDN assert on every `mutate!` — the \"non-EDN session breaks the
       remote view silently\" failure becomes unreachable (fail loud instead).
     - `version*` — clients poll the tiny number, fetch the (possibly large)
       registry only on change.
     - events are DATA with monotonic ids — surfaces render lines
       (`event-line`), other clients get structure.
     - `wait-for-event!` — the long-poll seam, designed in rather than
       bolted on later; version-poll is every client's fallback.

   defonce throughout: a REPL reload must never orphan live tapes or the
   receipt trail — that is the whole point of holding them here instead of
   in a fn-local or a record.")

;; ── the three atoms ─────────────────────────────────────────────────────────

(defonce ^{:doc "The continuation registry: {slug → session}. A session ≡
   {:slug :tape [canonical] :config {…} :turns :created-at :forked-from
   :forked-at} — EDN, always (asserted at `mutate!`). defonce so a reload
   keeps live tapes."}
  sessions*
  (atom {}))

(defonce ^{:doc "The RECEIPT stream — global UI chrome BESIDE the session
   registry (bounded ring, last 200, of event DATA maps
   {:id :at :kind :slug :msg}). Emitted at every command seam so every
   client's activity — attached nREPL agents included — is observable by any
   surface (equal clients at BOTH layers, tape ∧ chrome). Receipts index what
   happened; payloads live at the tape's nodes (ratified design rule).
   defonce so a reload keeps the trail."}
  events*
  (atom []))

(defonce ^{:doc "Monotonic counter, bumped on EVERY mutation — sessions* AND
   events* alike (D3). Clients poll this tiny number and fetch the (possibly
   large) registry or event tail only when it moves — fixes both poll latency
   and the v0.2.0 sin of serializing every tape body several times a second."}
  version*
  (atom 0))

(defonce ^{:private true :doc "Monotonic event id source — separate from
   version* (an event's own identity must survive independent of how many
   OTHER mutations bumped version* around it; `events-since`/`wait-for-event!`
   key off this, not version*)."}
  event-id*
  (atom 0))

;; ── EDN assert ────────────────────────────────────────────────────────────

(defn- edn-leaf-violation?
  "A leaf that cannot round-trip through `pr-str`/`read-string`: a fn, any
   IDeref (atom/ref/agent/promise/delay/future), or a record (defrecord
   instances print unreadably by default and carry identity `swap!` should
   never smuggle into a session)."
  [v]
  (or (fn? v)
      (instance? clojure.lang.IDeref v)
      (record? v)))

(defn edn-violations
  "Walk `v` (a registry map, or anything) for leaves that break the EDN
   contract — `edn-leaf-violation?` — returning a seq of {:path :value} for
   every offense found (path ≡ the vector of keys/indices to reach it), or
   nil when `v` is clean (⟨seq of nothing⟩ ≡ nil — the empty case is the falsy
   case, so callers write `(when-let [v (edn-violations x)] …)`).
   Public — `mutate!` asserts with this, and tests hit it directly (pure,
   no atom deps)."
  [v]
  (letfn [(walk [path node]
            (cond
              ;; check violation BEFORE map?/sequential? — a record IS a map,
              ;; and must be flagged as itself, not recursed into
              (edn-leaf-violation? node)
              [{:path path :value node}]

              (map? node)
              (mapcat (fn [[k val]] (walk (conj path k) val)) node)

              (sequential? node)
              (mapcat (fn [[i val]] (walk (conj path i) val)) (map-indexed vector node))

              (set? node)
              (mapcat (fn [val] (walk (conj path :set) val)) node)

              :else
              []))]
    (seq (walk [] v))))

;; ── the D2 chokepoint ─────────────────────────────────────────────────────

(defn mutate!
  "THE mutation chokepoint (D2) — every registry write in the codebase routes
   through here. `f` ≡ a PURE fn of the whole CURRENT {slug → session} map
   (never a stale snapshot: `swap-vals!` hands `f` whatever the atom holds at
   the moment it actually applies, retrying `f` itself under contention like
   any `swap!`). Returns `[old new]` — callers need `old` to detect a race
   (compare the tape they completed against vs the tape that was actually
   current) and `new` to read back what landed.

   EDN-asserts the RESULT: a violation throws `ex-info` naming the offending
   paths/values (`edn-violations`) — the \"a fn/atom/record snuck into a
   session and broke the next remote fetch\" failure becomes unreachable,
   fail loud instead of silent corruption. Pinned choice (documented because
   it is a real behavioral decision, not an oversight): the swap ALREADY
   HAPPENED when the assert fires — sessions* is left holding the bad value,
   version* is NOT bumped (the assert throws before that line). The
   alternative (roll back sessions* on violation) would hide a caller's bug
   behind a silent no-op; leaving the bad value in place keeps the failure
   exactly as loud as the throw that reports it, and a human/agent inspecting
   `@sessions*` after the exception sees the actual offending shape, not a
   politely-reverted lie."
  [f]
  (let [[old new] (swap-vals! sessions* f)]
    (when-let [violations (edn-violations new)]
      (throw (ex-info (str "registry mutation produced non-EDN session data — "
                            "sessions* must stay pure data (no fn/atom/record, "
                            "ever): " (pr-str violations))
                       {:violations violations})))
    (swap! version* inc)
    [old new]))

;; ── events ────────────────────────────────────────────────────────────────

(defn event!
  "Append one event to `events*` (bounded ring, last 200) and bump `version*`.
   `e` ≡ {:kind kw :slug kw-optional :msg str}, or a plain STRING (D3
   compatibility: coerces to {:kind :note :msg s} — surfaces contribute
   receipts as bare strings same as before the registry ns existed; core's
   `event!` calls with plain strings, e.g. main.clj's `(core/event! \"use! :x\")`,
   keep working through this coercion). Assigns a monotonic `:id` and `:at`
   (epoch ms); returns the COMPLETED event map (never the bare string, even
   when `e` was one — callers that need the rendered line want `event-line`)."
  [e]
  (let [e  (if (string? e) {:kind :note :msg e} e)
        id (swap! event-id* inc)
        e' (assoc e :id id :at (System/currentTimeMillis))]
    (swap! events* #(vec (take-last 200 (conj % e'))))
    (swap! version* inc)
    e'))

(defn event-line
  "Event map → the short receipt string surfaces render (the tree-pane footer
   is ~24 display cols, dim, truncated further downstream — this just picks
   the words). `:kind :note` → bare `:msg` (surface-contributed receipts,
   unchanged shape from before events were data). Otherwise
   `\"kind slug msg\"` — kind rendered via `name` (drops the leading colon,
   keeps a trailing `!`: `:eval!` → \"eval!\"), slug rendered via `str` (KEEPS
   its colon: `:s` → \":s\"), either half elided when absent/nil, e.g.
   {:kind :eval! :slug :s :msg \"✓@6\"} → \"eval! :s ✓@6\". Tolerant of a
   plain string (returns it unchanged) — transition safety while any caller
   still has a stale/cached string entry in hand."
  [e]
  (if (string? e)
    e
    (let [{:keys [kind slug msg]} e]
      (if (= :note kind)
        (str msg)
        (->> [(some-> kind name) (some-> slug str) msg]
             (remove nil?)
             (interpose " ")
             (apply str))))))

(defn events-since
  "Events with :id strictly greater than `id`, in ring order — a pure read of
   `@events*`. Empty vector once the caller is caught up."
  [id]
  (vec (filter #(> (:id %) id) @events*)))

(def ^:private default-wait-timeout-ms 25000)

(defn wait-for-event!
  "THE D3 long-poll seam. If events past `since-id` already exist, return
   them immediately (no parking). Else park on an `add-watch` of `events*` ⊕
   a promise: the first `event!` past `since-id` delivers, the watch is
   ALWAYS removed (`finally` — a leaked watch is a slow leak on every future
   event!). Times out after `timeout-ms` (default 25s) returning `[]` — never
   blocks a client forever, and gives the version-poll fallback a bounded
   worst case. Works under bb (uses plain `add-watch`/`promise`/`deref`
   timeout, no java.util.concurrent import needed)."
  ([since-id] (wait-for-event! since-id default-wait-timeout-ms))
  ([since-id timeout-ms]
   (let [immediate (events-since since-id)]
     (if (seq immediate)
       immediate
       (let [p  (promise)
             wk (gensym "wait-for-event")]
         (add-watch events* wk
                    (fn [_ _ _ _]
                      (when-let [ev (seq (events-since since-id))]
                        (deliver p ev))))
         (try
           ;; re-check AFTER the watch is armed — closes the TOCTOU window
           ;; between the immediate check above and add-watch taking effect
           (when-let [ev (seq (events-since since-id))]
             (deliver p ev))
           (vec (or (deref p timeout-ms nil) []))
           (finally
             (remove-watch events* wk))))))))

(defn reset-events!
  "Test/operator seam: empty the event ring and rewind the id counter to 0.
   Deliberately narrow — does NOT touch sessions* or version*; a caller
   wanting a fully clean slate composes with a direct `(reset! sessions* {})`
   / `(reset! version* 0)` (both public atoms — no extra surface needed for
   what a one-liner already does)."
  []
  (reset! events* [])
  (reset! event-id* 0))
