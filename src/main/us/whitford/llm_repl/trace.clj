(ns us.whitford.llm-repl.trace
  "Trace durability ⊕ tape persistence — the ONE ns that owns the escapement
   capture-layer integration (design/trace-durability.md, RATIFIED
   2026-08-28). io layer: sits beside `completion`, requires escapement +
   registry, and is required by completion/api — NEVER by registry (the
   transcript/tape hooks are INJECTED into registry's tap slots at `init!`,
   an open slot: a library consumer that never inits pays nothing).

   Coordinate mapping (the fork forest IS the node tree):

     work-dir   ≡ <proj>/.llm-repl/     (config :trace :dir; under the
                                         container's /work → survives restarts)
     session-id ≡ \"main\"               (ONE escapement session per daemon)
     node-id    ≡ the session slug       KEYWORD, passed RAW — encode-node-id
                                         assumes keyword print form; a string
                                         silently loses its first char
                                         (runtime-pinned, design § build
                                         decisions)
     visit      ≡ daemon incarnation     seed-visit-counts on boot → max+1; a
                                         restart never overwrites prior traces
     turn       ≡ assistant tape index   sparse (skips user turns) — the ONE
                                         id space, already in receipts as ✓@N

   Failure posture: capture failures are `{:kind :trace}` RECEIPTS, never
   exceptions into the completion path — durability must not break evals.
   Silent no-write-while-enabled is the silent-fallback failure mode; the
   receipt is the guard. The transcript side inherits escapement's own
   never-crash-the-caller stance (`make-transcript-fn` swallows internally).

   `*capture?*` — the tapeless-driver switch (human-decided, design § build
   decisions): `bounce!`/`trampoline!` bind it false around their sends —
   non-committing completions have NO assistant tape index (N bounces off one
   prefix would collide on the same turn number), so their trace stays the
   receipt stream. Committed turns (eval!, battery, ab! arms) capture fully."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [escapement.capture :as cap]
   [escapement.protocols :as eproto]
   [escapement.storage.disk :as disk]
   [escapement.transcript :as transcript]
   [us.whitford.llm-repl.registry :as registry]))

(def ^:dynamic *capture?*
  "True (default) ≡ completion sends are blob-captured. Bound false by the
   tapeless drivers (bounce!/trampoline!) — see ns docstring. Conveys through
   futures (bb ∧ JVM both convey bindings), so a tool loop's nested eval
   inherits its driver's setting."
  true)

(defonce ^{:doc "The trace runtime — nil ≡ DISABLED (every fn below no-ops).
   When live: {:store ArtifactStore  :transcript-fn (fn [ev])
               :sink transcript-sink|nil  :visit n  :session-id str
               :dir str|nil}.
   defonce: a REPL reload must not orphan an open transcript sink."}
  state*
  (atom nil))

(defn enabled? [] (some? @state*))

(defn capturing?
  "True iff a send happening NOW would be blob-captured — enabled ∧ not
   inside a tapeless-driver binding. The gate receipts consult before
   claiming an :io/ref (a ref to a blob that will never exist is a lie)."
  []
  (and *capture?* (enabled?)))

(defn- ctx
  "The escapement capture context for `slug`, or nil when disabled."
  [slug]
  (when-let [{:keys [store session-id visit]} @state*]
    {:store store :session-id session-id :node-id slug :visit visit}))

(defn- trace-fail!
  "The loud-failure receipt (never a throw — ns docstring posture). Emitting
   through event! is recursion-safe: the event tap only writes JSONL
   (never-throws upstream), it never captures."
  [op t]
  (registry/event! {:kind :trace :msg (str "✗ " op ": " (ex-message t))})
  nil)

;; ── locators (deterministic — a receipt can point at a blob pre-write) ─────

(defn ref-for
  "The locator where `kind` for (slug, current visit, turn) lives — a PURE
   path computation (escapement: locator ≡ the opaque id, path ≡ the
   address). nil when disabled. Lets receipts carry :io/ref without
   reordering receipt-then-dispatch (design § build decisions 4)."
  [slug turn kind]
  (when-let [{:keys [visit]} @state*]
    (str (cap/turn-dir slug visit turn) "/" kind ".edn")))

(defn- node-file-locator
  "nodes/<enc-slug>/<visit>/<file> — derived from escapement's own
   `seed-locator` (the one public path source that encodes a node id;
   `encode-node-id` is private upstream) so our tape.edn sits exactly where
   escapement's tree expects siblings of seed.edn."
  [slug visit file]
  (str/replace (cap/seed-locator slug visit) #"seed\.edn$" file))

;; ── capture (turn blobs) ───────────────────────────────────────────────────

(defn capture!
  "Write `data` as blob `kind` (STRING — \"response\", \"original\",
   \"tool-results/<id>\", \"rounds/<k>-response\") for (slug, turn). Returns
   {:io/ref :io/snippet} | nil (disabled, *capture?* off, or failed-loud).
   OVERWRITES on re-capture (escapement blob semantics — last wins)."
  [slug turn kind data snippet]
  (when (and *capture?* (enabled?))
    (try
      (cap/capture-blob! (ctx slug) turn kind data (str snippet))
      (catch Throwable t (trace-fail! (str kind "@" turn " " slug) t)))))

(defn request!
  "Capture the FULL wire request for (slug, turn) — FIRST-write-wins
   (escapement request semantics: a logical turn may issue several physical
   requests — fallback, continuation; only the BASE request is kept, so
   replay tunes the real prompt). Returns {:io/ref :io/snippet} | nil."
  [slug turn request snippet]
  (when (and *capture?* (enabled?))
    (try
      (cap/capture-request! (ctx slug) turn request (str snippet))
      (catch Throwable t (trace-fail! (str "request@" turn " " slug) t)))))

(defn seed!
  "Capture the replayable session seed at nodes/<slug>/<visit>/seed.edn —
   called at open! creation (config ⊕ birth metadata). Not gated on
   *capture?*: a session's existence is worth recording even when its sends
   are not (a bounce!-opened session still has a seed)."
  [slug seed]
  (when (enabled?)
    (try
      (cap/capture-seed! (ctx slug) seed)
      (catch Throwable t (trace-fail! (str "seed " slug) t)))))

;; ── tape snapshots (the persistence half — rides the mutate! tap) ──────────

(defn- write-node-file!
  [slug file data]
  (let [{:keys [store session-id visit]} @state*]
    (eproto/write-artifact! store session-id
                            (node-file-locator slug visit file)
                            (pr-str data)
                            {:transcript/node-id slug
                             :transcript/visit   visit
                             :artifact/class     :captured-io})))

(defn tape!
  "Snapshot the FULL session map (not just messages — recovery must honor
   configuration-completeness: tape ⊕ config → future) to
   nodes/<slug>/<visit>/tape.edn. Atomic overwrite (temp+rename upstream);
   latest wins — the recovery source. EDN-safe by construction: the D3
   chokepoint already asserted the session before this tap ever sees it."
  [slug session]
  (when (enabled?)
    (try
      (write-node-file! slug "tape.edn" session)
      (catch Throwable t (trace-fail! (str "tape " slug) t)))
    nil))

(defn- tombstone!
  "drop!/reset-all! marker — overwrite tape.edn with {:trace/dropped true}
   so recovery never resurrects a deliberately dropped session (design §
   build decisions 2). Prior visits' turn blobs remain: drop deletes the
   SESSION, never the history."
  [slug]
  (when (enabled?)
    (try
      (write-node-file! slug "tape.edn" {:trace/dropped true
                                         :at            (System/currentTimeMillis)})
      (catch Throwable t (trace-fail! (str "tombstone " slug) t)))
    nil))

(defn- on-mutate
  "The registry mutate-tap: diff [old new], snapshot every changed session,
   tombstone every removed one. Runs in the mutating caller's thread AFTER
   the swap — concurrent mutations may write snapshots out of order, but
   each write is atomic and 'latest known state' is the contract."
  [old new]
  (doseq [slug (into #{} (concat (keys old) (keys new)))]
    (let [o (get old slug)
          n (get new slug)]
      (cond
        (nil? n)    (tombstone! slug)
        (not= o n)  (tape! slug n)))))

;; ── failures (the ONE capture the tapeless drivers still make) ─────────────

(def ^:private failure-seq*
  "Process-local tiebreaker. Blob semantics are last-wins, and two sends can
   fail inside one millisecond (a trampoline! fan-out over a down backend
   does exactly that) — the counter keeps every failure its own file."
  (atom 0))

(defn- edn-safe
  "`x` if it round-trips pr-str → read-string, else its pr-str STRING.
   An ex-data carrying a live object (an HTTP response, a socket) prints as
   #object[…] and would poison every later `edn/read-string` of the whole
   artifact. Degrade the VALUE, never the file."
  [x]
  (try (edn/read-string (pr-str x))
       (catch Throwable _ (pr-str x))))

(defn failure!
  "Capture a FAILED send at nodes/<slug>/<visit>/failures/<ts>-<n>.edn —
   the request that produced it ⊕ the error. Returns {:io/ref …} | nil.

   NOT gated on `*capture?*`: this is the one capture the tapeless drivers
   still make (design § ratified decisions 1, AMENDED 2026-08-28). The
   receipt-only rule exists because a non-committing send has no assistant
   tape index — N bounces off one prefix would collide on the same turn
   number. A FAILED send has no tape consequence at all: it commits nothing,
   so it collides with nothing, and it is precisely the send whose payload
   you need. Gated on `enabled?` alone, exactly like `seed!`.

   Found by USING the instrument: an armed bounce! died on an HTTP 400 four
   tool-rounds deep and left a ✗ receipt that named the failure with no way
   to see what had been sent. The receipt was the trace; the trace was not
   enough."
  [slug request t]
  (when (enabled?)
    (try
      (let [{:keys [visit]} @state*
            at   (System/currentTimeMillis)
            file (str "failures/" at "-" (swap! failure-seq* inc) ".edn")]
        (write-node-file! slug file
                          {:at      at
                           :error   (ex-message t)
                           :ex-type (str (type t))
                           :ex-data (edn-safe (ex-data t))
                           :request (edn-safe request)})
        {:io/ref (node-file-locator slug visit file)})
      (catch Throwable t2 (trace-fail! (str "failure " slug) t2)))))

;; ── transcript (the event tap) ─────────────────────────────────────────────

(defn receipt!
  "The registry event-tap: every receipt → transcript JSONL. Delegates to
   the installed transcript-fn (escapement's make-transcript-fn — single
   daemon writer thread, FIFO, monotonic :seq, never throws to the caller)."
  [event]
  (when-let [{:keys [transcript-fn]} @state*]
    (transcript-fn event))
  nil)

;; ── lifecycle ──────────────────────────────────────────────────────────────

(defn install!
  "The low-level installation seam — `init!`'s tail, and THE test seam (a
   memory store here makes the whole suite filesystem-free). Installs the
   trace runtime and injects both registry taps. Options:
     :store (required)  :transcript-fn (default no-op)  :sink (owned; closed
     by close!)  :visit (default 1)  :session-id (default \"main\")  :dir"
  [{:keys [store transcript-fn sink visit session-id dir]
    :or   {transcript-fn (fn [_] nil) visit 1 session-id "main"}}]
  (reset! state* {:store store :transcript-fn transcript-fn :sink sink
                  :visit visit :session-id session-id :dir dir})
  (reset! registry/event-tap* receipt!)
  (reset! registry/mutate-tap* on-mutate)
  nil)

(defn close!
  "Retract both taps, drain ∧ close the transcript sink (5s join upstream),
   disable. Idempotent — safe as a shutdown hook AND a test fixture."
  []
  (when-let [{:keys [sink]} @state*]
    (reset! registry/event-tap* nil)
    (reset! registry/mutate-tap* nil)
    (when sink
      (try (transcript/close! sink) (catch Throwable _ nil)))
    (reset! state* nil))
  nil)

(defn init!
  "Daemon boot (the ONE production call site — main.clj :headless; --plain
   NEVER calls this, per ratification Q1). Config `:trace {:enabled? bool
   :dir str}` — absent ≡ enabled, dir \".llm-repl\". Builds the disk store at
   <dir>/main/, opens the transcript sink (append-resume: :seq continues
   across restarts), seeds visit ← max+1 (a restart never overwrites prior
   traces), writes <dir>/.gitignore (`*` — the captured request.edn carries
   the RESOLVED preamble; the licensing boundary must never leak into a repo
   via traces), installs the taps, registers a shutdown hook for close!.

   Init failure is a loud receipt, never a boot-stopping throw — a daemon
   that won't start because the trace dir is unwritable would be `failed`
   where `degraded` is owed (same posture as recovery's receipt-and-skip)."
  [config]
  (let [{on? :enabled? dir :dir :or {on? true dir ".llm-repl"}} (:trace config)]
    (when on?
      (try
        (let [work (io/file dir)
              sdir (io/file work "main")]
          (.mkdirs sdir)
          (let [gi (io/file work ".gitignore")]
            (when-not (.exists gi) (spit gi "*\n")))
          (let [store  (disk/new-artifact-store (.getPath sdir))
                counts (try (cap/seed-visit-counts store "main")
                            (catch Throwable _ {}))
                visit  (inc (reduce max 0 (vals counts)))
                sink   (transcript/open-transcript
                        {:path (.getPath (io/file sdir "transcript.jsonl"))})]
            (install! {:store store :sink sink
                       :transcript-fn (transcript/make-transcript-fn sink)
                       :visit visit :dir (.getPath work)})
            (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable close!))
            (registry/event! {:kind :trace :msg (str "on → " dir " visit " visit)})))
        (catch Throwable t
          (registry/event! {:kind :trace :msg (str "✗ init: " (ex-message t))})))))
  nil)

;; ── recovery (ratified Q2: AUTO, loud receipts, receipt-and-skip) ──────────

(defn- tape-artifacts
  "All tape.edn artifacts in the store, reduced to the LATEST visit per node:
   [{:path :node :visit} …]. Coordinates parsed from the PATH (authoritative
   enough here — metadata re-derivation differs between memory and disk
   stores; the path grammar is the one constant)."
  [store session-id]
  (->> (eproto/list-artifacts store session-id)
       (keep (fn [{:artifact/keys [path]}]
               (when-let [[_ node v] (re-matches #"nodes/([^/]+)/(\d+)/tape\.edn" (str path))]
                 {:path path :node node :visit (Long/parseLong v)})))
       (group-by :node)
       (map (fn [[_ vs]] (apply max-key :visit vs)))))

(defn recover!
  "Auto-recovery at daemon boot (ratified Q2): latest tape.edn per node →
   offer into sessions*. RECEIPT-AND-SKIP on anything bad (human-pinned):
   a corrupt snapshot emits `{:kind :recover :msg \"✗ …\"}` and is skipped —
   the daemon boots degraded, never refuses to start over one bad file.
   Tombstones (`:trace/dropped`) skip SILENTLY — intentional state, not a
   failure. A slug already live in the registry wins over its disk copy
   (recovery is additive; receipted). Each recovered session is immediately
   eval!-able — tape.edn is pure EDN, :complete-fn was never stored."
  []
  (when (enabled?)
    (let [{:keys [store session-id]} @state*
          tapes (try (tape-artifacts store session-id)
                     (catch Throwable t (trace-fail! "recover-list" t) nil))]
      (doseq [{:keys [path]} tapes]
        (let [content (try (eproto/read-artifact store session-id path)
                           (catch Throwable t (trace-fail! (str "recover-read " path) t) nil))
              session (when content
                        (try (edn/read-string content)
                             (catch Throwable t
                               (registry/event!
                                {:kind :recover
                                 :msg  (str "✗ skipped " path ": " (ex-message t))})
                               nil)))]
          (cond
            (nil? session) nil                       ; failed loud above (or empty)
            (:trace/dropped session) nil             ; tombstone — intentional

            (not (and (map? session)
                      (keyword? (:slug session))
                      (vector? (:tape session))))
            (registry/event! {:kind :recover
                              :msg  (str "✗ skipped " path ": not a session map")})

            :else
            (let [slug    (:slug session)
                  [old _] (registry/mutate!
                           (fn [reg]
                             (if (contains? reg slug) reg (assoc reg slug session))))]
              (if (contains? old slug)
                (registry/event! {:kind :recover :slug slug
                                  :msg "already live — disk copy ignored"})
                (registry/event! {:kind :recover :slug slug
                                  :msg (str "@" (count (:tape session)))}))))))
      nil)))
