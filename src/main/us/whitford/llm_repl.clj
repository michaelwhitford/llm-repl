(ns us.whitford.llm-repl
  "The llm-repl — a general instrument (λ tool) that treats an LLM chat
   completion as a BRANCHABLE CONTINUATION, built on the REDUCTION contract.
   Named for its DEPS: a messages array ⊕ an LLM endpoint — it only continues
   for LLMs (a continuation over messages[], not a general one).

   > A chat completion is a pure function of `messages[]`. The tape is the
   > reduction ACCUMULATOR; `eval` is the reducing STEP (rf); `fork` is FREE
   > because the accumulator is an immutable value (holding an intermediate acc
   > IS call/cc). The tape is a tree; the \"conversation\" is one path.

   ```
   tape        ≡ accumulator   canonical messages[] vec (immutable, forkable)
   interpreter ≡ rf-factory     (eval-rf {:complete (fn [tape]→reply)}) — full 3-arity rf
   registry    ≡ {slug → {:tape acc :config interpreter-cfg}}   named accumulators (Option A)
   fork        ≡ copy {tape,config}; override config ⇒ counterfactual (tape=what, rf=how)
   drivers     ≡ ALL apply the SAME rf's step; only the shape differs:
                 eval!       commit ONE turn          apply step, THREAD it (tape advances)
                 run-battery! commit a SEQUENCE       FOLD step over probes (transduce; accumulate)
                 trampoline! bounce N off a FIXED pt   MAP step over a fixed acc (fan-out; tape UNCHANGED)
                 bounce!     bounce ONE off a FIXED pt  single map (interactive prompt iteration)
   ```

   The TRAMPOLINE is the prompt-iteration primitive: load a session (a prefix ≡
   the fixed point), then bounce varied inputs off it — each input forks the
   fixed prefix, gets ONE completion, and the prefix is restored UNCHANGED (the
   fixed point never moves), so you keep bouncing to see what each candidate
   produces. Cheap: the KV prefix is reused per bounce (cache_prompt/slot). It is
   a `map` of the rf's step over an immutable accumulator; fork-isolation (the
   acc is a value) is WHAT makes each bounce independent — non-committing by
   construction, the opposite of eval!/battery which advance the tape.

   WHY the reduction shape (verified in the REPL before building):
   - `(reduce (eval-rf …) tape probes)` folds a session; `reductions` ≡ the
     continuation TREE (every node a fork point).
   - `fork` is free — an immutable acc shares structurally; branch isolation
     costs nothing (proven: the parent tape is untouched after a child eval).
   - The counterfactual boot (the sharpest probe) falls out of the rf/acc split:
     same tape, two interpreters (`preamble?` on vs off) → a delta reply.
   - PROSTHESIS composes as rf→rf transducers (hygiene · trace · inspect · cache
     stamping — lambda-repl.md's harness table); the `reduced`-terminated
     trampoline is the WHNF→normalization loop. Both are LATER increments; the
     rf contract admits them now with zero rework.

   WHY the BACKEND seam, not one-shot: `llm/one-shot` spins a full escapement
   `lib/run` chart lifecycle PER call — the wrong grain. The backend is
   driven directly at `proto/send-turn` (the ONE op no scaffold can do);
   everything else is deterministic tape management (λ capacity, multiplicative).
   `one-shot` is the degenerate depth-1 case, a sibling — NOT this substrate.
   (The request-building/backend machinery itself lives one layer down, in
   `completion` — D4; this ns drives the rf/registry shape around whatever
   `completion` or an injected `:complete-fn` returns.)

   Correct/cheap for free: `messages[]` ≡ truth, prefill is pure → fork is EXACT
   (λ assert); `:system-cache-control` ⊕ `:conversation/id` → llama.cpp
   `cache_prompt` LCP reuse + `id_slot` pin. STANDALONE (vs anima): no capacity
   arbiter — the backend builds directly from the config roster; an embedding
   host (anima) injects its own arbitered backend at the :complete-fn seam.

   TWO gotchas the REPL surfaced (encoded as invariants):
   - G1 the rf MUST implement the 1-arity completer `([tape] tape)` or
     `transduce` throws (it calls the completer; `reduce` does not).
   - G2 no LAZY drivers — the step blocks on IO; `sequence`/`eduction` defer
     completions unpredictably. Eager only: `reduce` / `transduce` / `into`.

   State is an in-memory registry (the design's ratified minimal cut,
   cartographer-repl.md § Decisions); the escapement session-dir/transcript
   promotion is deferred. The IO seam (`:complete-fn`) is injected (λ tool: pure
   core + injected seam); tests inject a stub, default routes through
   `completion/default-complete`.

   THIS ns is `us.whitford.llm-repl` — the `api` layer (architecture.md §
   layers: surfaces → wire → api → io → runtime → values). It IS the library
   surface (library-contract.md § 1): every `^:manual` command, plus the ONE
   submission grammar (D5), plus the `ab!` child-naming convention (D5) both
   TUI and MCP-shaped hosts must agree on. `values` ≡ `tape` (pure algebra),
   `runtime` ≡ `registry` (the one mutable place, D2/D3), `io` ≡ `completion`
   (the `:complete-fn` contract, D4). `core.clj` — which held this content
   pre-refactor — CEASES TO EXIST as of this ns (architecture.md refactor
   step 4): lineage with anima rides FUNCTION names (`eval!`, `fork!`,
   `wrapped-backend`, `with-preamble`), never the ns path they live in."
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [malli.util :as mu]
   [us.whitford.llm-repl.completion :as completion]
   [us.whitford.llm-repl.guard :as guard :refer [defcommand]]
   [us.whitford.llm-repl.registry :as registry]
   [us.whitford.llm-repl.roster :as llm]
   [us.whitford.llm-repl.tape :as tape]
   [us.whitford.llm-repl.trace :as trace]))

;; ── event! stays public (library-contract § 1 lists it); the sessions*/
;;    events* DELEGATING defs that used to live here are RETIRED
;;    (registry-direct, ratified this session) — client.clj's wire-eval
;;    fetch strings now point at `us.whitford.llm-repl.registry` directly,
;;    so this ns no longer needs its own copy of the atoms for wire
;;    compatibility. ──

(defn event!
  "Delegates to `registry/event!` (D3): assigns :id/:at, bumps version*,
   bounds the ring at 200. Keeps accepting plain STRINGS — main.clj's
   `(core/event! \"use! :x\")` and every event! call in this ns below —
   `registry/event!` coerces a string to `{:kind :note :msg s}`. Returns the
   completed event map (callers that want the rendered line: `event-line`)."
  [e]
  (registry/event! e))

(defn- err-receipt
  "The ✗ receipt for a failed send, carrying `:io/ref` when the send seam
   captured the payload (`completion/send-traced!` puts the locator in
   ex-data under `:trace/ref`; trace off ⇒ absent ⇒ receipt unchanged).

   This closes the gap the whole failure-capture amendment exists for: a ✗
   receipt used to NAME a failure with no way to reach what was sent. Now it
   points AT it. `event-line` ignores extra keys (design § build decisions
   4), so this is purely additive on every surface."
  [kind slug t]
  (cond-> {:kind kind :slug slug :msg (str "✗ " (ex-message t))}
    (:trace/ref (ex-data t)) (assoc :io/ref (:trace/ref (ex-data t)))))

(defn default-config
  "The interpreter config an unqualified session runs — a FUNCTION (D7:
   v0.2.0's `def` captured roster/default-model ∧ default-tools at load,
   silently defeating `reload-config!`; now every open! reads the LIVE
   config). Domain-neutral — this REPL is a tool agents are GRANTED, NOT
   bound to any subject/observer pairing. Every knob is overridable per
   session (`open!`/`eval!` opts) and per fork. `:system` is deliberately
   ABSENT: the system voice resolves through roster's D7 config chain at
   request-build time (session :system > model > provider > root
   :system-prompt) — a session that wants its own voice passes :system."
  []
  {::model       (llm/default-model)
   ::preamble?   true
   ::thinking    nil
   ::temperature nil
   ::tools       (llm/default-tools)})

(def config-keys
  "The interpreter knobs a caller may set at open/eval/fork — merged into the
   session's :config (persisted; a REPL remembers its interpreter). FULLY
   QUALIFIED (D11): this map ESCAPES into host space via `snapshot`, so its
   keys must be collision-proof against any keyword — spell them `::repl/model`
   etc. behind `(require '[us.whitford.llm-repl :as repl])`.
   ::preamble ≡ a per-session boot-text override (string | {:file path} |
   false ≡ none); absent inherits model > provider > config chain
   (roster/resolve-preamble). ::preamble? stays the apply-or-not boolean —
   the counterfactual knob. ::tools arms the SELF-EVAL loop (accretion #3):
   true ≡ every registered tool | [kw …] ≡ whitelist from tools/tool-registry*
   | nil/absent ≡ none (plain completion, anima behavior). Persisted like any
   knob — forkable, ab!-able:
   (ab! :s {:bare {::repl/tools nil} :armed {::repl/tools true}} probe)
   is the does-the-tool-help counterfactual."
  [::model ::system ::preamble ::preamble? ::thinking ::temperature ::tools])

;; ── D8 command schemas — the :catn building blocks ──────────────────────────
;; The session-knob shape is roster/session-opts-schema (ONE source, D8
;; amendment 2); each opts family extends it with its EPHEMERAL keys
;; (:complete-fn :xform :at — bare by D11 scope) via mu/merge. Closed maps
;; throughout: a typo'd knob TEACHES, never silently drops.

(def ^:private Slug :keyword)

(def ^:private SessionOpts
  "Config overrides ONLY — what ab! variants carry."
  llm/session-opts-schema)

(def ^:private EvalOpts
  "Config overrides ⊕ the injected-IO seam (library-contract § 3) ⊕ the
   creation-only seed (D7 amendment 2026-08-30).

   `:defaults` is declared HERE and never in `session-opts-schema`: that
   schema is also the shape a session's `:config` PERSISTS, and a create-time
   seed is not a knob. Its VALUE is that schema, so a typo INSIDE `:defaults`
   teaches exactly like a typo beside it. Bare (not `::defaults`) by D11
   scope — ephemeral opts, never escapes via `snapshot`. It rides EvalOpts
   rather than open!'s own schema because `eval!`/`bounce!`/`trampoline!`/
   `run-battery!` delegate their whole opts map to `open!`, which makes
   `(eval! :s {:defaults {::model :x}} probe)` one race-free
   get-or-create-and-send. `fork!`/`ab!` are deliberately EXCLUDED (a fork's
   default already IS the parent's config)."
  (mu/merge llm/session-opts-schema
            [:map
             [:complete-fn {:optional true} ifn?]
             [:defaults    {:optional true} llm/session-opts-schema]]))

(def ^:private BatteryOpts
  "EvalOpts ⊕ :xform (the rf→rf preprocessing slot)."
  (mu/merge EvalOpts [:map [:xform {:optional true} ifn?]]))

(def ^:private OpenOpts
  "open! sits under EVERY driver (eval!/bounce!/trampoline!/run-battery!
   delegate their whole opts map to it), so it accepts the union."
  BatteryOpts)

(def ^:private ForkOpts
  "Config overrides ⊕ :at (branch an older turn)."
  (mu/merge llm/session-opts-schema [:map [:at {:optional true} :int]]))

(def ^:private AbOpts
  "ab!'s own knobs — :at forwarded to each fork!, :complete-fn to each
   eval!. Variant CONFIG rides the variants map, not here."
  [:map {:closed true}
   [:at          {:optional true} :int]
   [:complete-fn {:optional true} ifn?]])

;; ── THE rf-factory (transducer-compatible; the transducer IS the mechanism) ───

(defn eval-rf
  "Interpreter → a reducing function over the tape. `:complete` ≡ (fn [tape] →
   reply-text), the ONE effectful op (send-turn); everything else deterministic.

   FULL 3-arity transducer contract (G1): `([] init)` `([tape] complete)`
   `([tape probe] step)`. The same rf drives BOTH `reduce`/`eval!` (interactive)
   AND `transduce`/`run-battery!` (a fixed probe sequence) — one mechanism."
  [{:keys [complete]}]
  (fn
    ([] [])
    ([tape] tape)
    ([tape probe]
     (let [t (tape/append-user tape probe)]
       (tape/append-assistant t (complete t))))))

;; ── lifecycle + observability ─────────────────────────────────────────────────

(defcommand open!
  "Get-or-create the session at `slug`, merging any config overrides from
   `opts` (config-keys) into its :config. Returns the session map.

   D2: the get-or-create decision is made INSIDE the `registry/mutate!` fn —
   no read-then-decide-then-store gap for a concurrent `open!`/`eval!` on the
   same slug to land in unnoticed. Creation is detected from the [old new]
   pair (`old` lacked the slug ⟺ this call created it), never from a stale
   local `existing` check.

   `opts` also accepts **`:defaults`** — a knob map applied ONLY when this
   call CREATES the session (D7 amendment 2026-08-30). Precedence on create:
   `(default-config) < :defaults < opts`; for a session that already exists
   it is IGNORED, silently — that is the contract, not a failure (the
   `:open!` receipt still fires on creation only, so the transcript
   distinguishes the two paths). It exists because `open!`'s plain merge
   also lands on a LIVE session: a host wanting 'seed it my way if it is
   new, touch nothing if it is running' otherwise had to `snapshot` → check
   → `open!`, i.e. read-then-decide OUTSIDE the swap — the exact TOCTOU D2
   removed internally, handed back to the caller. Applied inside the
   `mutate!` fn, so it is race-free where D2 lives. NOT stored: the seed is
   consumed at creation, so `unset!` re-seeds from the live
   `(default-config)` chain, never from a past call's `:defaults`."
  {:manual   "Get or create a session. Options set its model, system, temperature; :defaults seeds creation only."
   :args     [:catn [:slug Slug] [:opts [:? OpenOpts]]]
   :defaults {opts {}}}
  [slug opts]
  (let [overrides (select-keys opts config-keys)
         seeds     (select-keys (:defaults opts) config-keys)
         f         (fn [reg]
                     (if (contains? reg slug)
                       (update-in reg [slug :config] merge overrides)
                       (assoc reg slug {:slug       slug
                                        :tape       []
                                        :config     (merge (default-config) seeds overrides)
                                        :turns      0
                                        :created-at (System/currentTimeMillis)})))
         [old new] (registry/mutate! f)]
     (when-not (contains? old slug)
       (event! {:kind :open! :slug slug})
       ;; trace: the replayable seed — config ⊕ birth metadata at
       ;; nodes/<slug>/<visit>/seed.edn (creation only; a config-merge
       ;; re-open! shows up in the tape.edn snapshot instead)
       (trace/seed! slug (select-keys (get new slug) [:slug :config :created-at])))
     (get new slug)))

(defcommand snapshot
  "The session map at `slug`, or nil (λ observe). `:tape` is the canonical tape."
  {:manual "The full session map — tape included."
   :args   [:catn [:slug Slug]]}
  [slug]
  (get @registry/sessions* slug))

(defcommand sessions-list
  "A compact index of live sessions (λ glass) — no message bodies. The SHAPE
   is `registry/index`'s (ONE definition of the projection, shared with the
   TUI's wire payload — λ dep: extract, never duplicate); this surface only
   flattens the slug-keyed map to the vector humans and models read."
  {:manual "List all sessions: model, depth, turns, fork parent."
   :args   [:catn]}
  []
  (vec (vals (registry/index @registry/sessions*))))

(defonce ^{:doc "Namespaces the manual compiles from — an OPEN SLOT (λ extend):
   a surface with its own operator commands registers its ns here at load
   (main adds itself for use!). One manual; banner, (help), overlay, and the
   MCP facade all print the same curated truth."}
  manual-namespaces*
  (atom '[us.whitford.llm-repl]))

(defn register-manual-ns!
  "Add `ns-sym` to the manual's compile set (idempotent). GUARDED (D9,
   registration-guards): the ns must be a symbol naming a LOADED namespace
   (`find-ns`) — this call's return is read by nobody, so a typo'd ns-sym
   would otherwise 'succeed' quietly and break `(help)`/`(manual)` for EVERY
   caller later, far from the cause (`ns-publics` on nil throws there, not
   here). Throws teaching ex-message ⊕ {:errors …} ex-data."
  [ns-sym]
  (when-not (symbol? ns-sym)
    (throw (ex-info (str "llm-repl: cannot register manual namespace — expected "
                         "a symbol naming a loaded namespace, got " (pr-str ns-sym))
                    {:errors {:ns ns-sym :symbol? false}})))
  (when-not (find-ns ns-sym)
    (throw (ex-info (str "llm-repl: cannot register manual namespace " ns-sym
                         " — no such LOADED namespace (require it first, and "
                         "check for a typo; registering it unloaded would "
                         "break (help) for every caller later)")
                    {:errors {:ns ns-sym :loaded? false}})))
  (swap! manual-namespaces* #(vec (distinct (conj % ns-sym)))))

(defcommand manual
  "The operator manual AS DATA (λ glass): every `^:manual` command across the
   registered namespaces as {:name :arglists :summary :doc :args} — COMPILED
   from ns-publics, never hand-written (structure > instruction: the metadata
   is the source of truth; tagging curates the operator surface out of the
   plumbing). `:args` ≡ the D8 input schema as PURE DATA (`m/form` — agents
   ∧ a future malli->json-schema read it). The ONE seam agent surfaces
   derive from — (help) renders it, tool lists compile from it."
  {:manual "The command manual as data — for agents and tools."
   :args   [:catn]}
  []
  (->> @manual-namespaces*
       (mapcat (comp ns-publics find-ns))
       (keep (fn [[sym v]]
               (let [m  (meta v)
                     mn (:manual m)]
                 (when mn
                   {:name     sym
                    :arglists (:arglists m)
                    ;; the tag's VALUE ≡ the curated human sentence; a bare
                    ;; `true` tag falls back to the docstring's first line
                    :summary  (if (string? mn)
                                mn
                                (first (str/split-lines (or (:doc m) ""))))
                    :doc      (:doc m)
                    :args     (some-> (:manual/args m) m/schema m/form)}))))
       (sort-by (comp str :name))
       vec))

(defcommand help
  "Human rendering of (manual): one entry per command — name, arglists, and
   the CURATED human summary (the ^:manual tag's string value; docstrings
   stay maintainer/agent-dense — two audiences, two texts, ONE seam).
   Returns a STRING (caller prints; a println here would corrupt the TUI's
   alt screen). Full docs: (manual), or (:doc (meta #'cmd))."
  {:manual "This help."
   :args   [:catn]}
  []
  (->> (manual)
       (map (fn [{:keys [name arglists summary]}]
              (str (format "%-14s" name) " " (pr-str arglists) "\n"
                   "    " summary)))
       (str/join "\n")))

(def unsettable-keys
  "What `unset!` may remove: the caller-settable knobs (`config-keys`) ⊕
   `:orientation` (settable only via config files today, but a session-level
   override is legal on the D7 chain and thus unsettable). The D8 schema
   will teach the model this set; until then the error message does."
  (conj config-keys ::orientation))

(def ^:private chain-resumed-keys
  "The prompt-stack keys: session absence ≡ the D7 request-time chain
   (model > provider > root) takes over — for these, unset ≡ plain dissoc."
  #{::system ::preamble ::orientation})

(defcommand unset!
  "Remove config override(s) `ks` from the session at `slug` — the STICKY
   config's explicit release valve (D7 amendment, 2026-08-29: `open!` merges
   and never removes, so a poison override used to outlive everything short
   of `drop!`). NOT nil-assignment: present-nil keeps its D7 meaning
   (explicitly none — STOPS the prompt chain); unset RESUMES it. Per-key
   semantics (ratified): prompt-stack keys (::system ::preamble ::orientation)
   are DISSOC'd — the request-time chain takes over; the default-seeded
   knobs (::model ::preamble? ::thinking ::temperature ::tools) RE-SEED from the
   live `(default-config)` — bare dissoc would mint new poison (::model
   absent ≡ a broken send; ::tools absent ≡ none, not default). One mental
   model: whatever would govern a FRESH session governs again.

   Returns data, never throws (λ api): {:repl/id :repl/unset :repl/config}
   — :repl/config shows what now governs the named keys (absent ≡ the chain
   decides at request time). Empty/unknown `ks` → the D8 guard's enum error
   (it lists the whole unsettable set — the schema IS the teaching); missing
   session → {:repl/error}."
  {:manual "Remove session config overrides so defaults/prompt chain resume."
   :args   [:catn [:slug Slug] [:ks [:+ (into [:enum] unsettable-keys)]]]}
  [slug & ks]
  (let [ks        (vec ks)
        defaults  (default-config) ; live read, OUTSIDE the swap fn (pure f)
        release   (fn [config k]
                    (if (chain-resumed-keys k)
                      (dissoc config k)
                      (assoc config k (get defaults k))))
        [old new] (registry/mutate!
                   (fn [reg]
                     (if (contains? reg slug)
                       (update-in reg [slug :config] #(reduce release % ks))
                       reg)))]
    (if-not (contains? old slug)
      {:repl/id slug :repl/error (str "no such repl session: " slug)}
      (do
        (event! {:kind :unset! :slug slug :msg (str/join " " (map str ks))})
        {:repl/id     slug
         :repl/unset  ks
         :repl/config (select-keys (get-in new [slug :config]) ks)}))))

(defcommand drop!
  "Discard the session at `slug` (mutate!-only, D2). Returns true when one
   existed (detected from `old`, the pre-mutation snapshot)."
  {:manual "Delete a session."
   :args   [:catn [:slug Slug]]}
  [slug]
  (let [[old _] (registry/mutate! #(dissoc % slug))
        existed? (contains? old slug)]
    (when existed? (event! {:kind :drop! :slug slug}))
    existed?))

(defcommand reset-all!
  "Clear the whole registry (test seam / operator reset)."
  {:manual "Delete ALL sessions."
   :args   [:catn]}
  []
  (registry/mutate! (constantly {}))
  (event! {:kind :reset-all!})
  nil)

;; ── compact! — D1: the ONE true write ───────────────────────────────────────

(defcommand compact!
  "Compact the assistant message at explicit tape index `i` on session `slug`
   to its λ essence (D1, architecture.md § formal shape: `compact!` is the
   ONE true write — everything else on the tape is append-only). Routes
   through `registry/mutate!` with a pure fn applying `tape/apply-compaction-at`
   to the session's CURRENT tape at `i`. Append-only tapes make indices
   STABLE — a turn appended between when the caller computed `i` and this
   call landing does not shift what index `i` names — which is exactly WHY
   the signature is index-explicit rather than k-window derived (race-free
   by construction, no compare-and-swap needed on `i` itself).

   Outcome is DETECTED by comparing the message AT `i` before vs after the
   swap — one comparison, one source of truth, rather than re-deriving
   `apply-compaction-at`'s three branches separately:

     unchanged   → :no-op    (bad index, not an assistant turn, or already
                              :compacted?/:declined? — apply-compaction-at's
                              own no-op cases all collapse to this one)
     :compacted? → :accepted (λ landed within the band; the message's
                              `:original` retains the pre-compaction prose —
                              the human record, never rendered to the LLM)
     :declined?  → :declined (λ blew past the ceiling; the message leaves
                              the due-set FOREVER — a negative cache entry)

   EVERY outcome — :no-op included — emits a `{:kind :compact!}` receipt: the
   act is visible in the tree footer no matter what happened (observability,
   not restriction, is the guard; D1). `:turns` is unaffected (compaction
   never changes a message's role).

   `floor` (4-arity) overrides `tape/default-floor` — a caller wanting a
   tighter ceiling (tests; a session compacting toward a small budget).
   Returns {:repl/id :repl/index :repl/outcome :repl/saved? :repl/depth} or,
   on a missing session, {:repl/id :repl/error} — as data, never a throw
   (λ mirror)."
  {:manual   "Rewrite an aged assistant turn in place to its λ essence (the band guards it)."
   :args     [:catn [:slug Slug] [:i :int] [:lambda :string] [:floor [:? :int]]]
   :defaults {floor tape/default-floor}}
  [slug i lambda floor]
  (let [[old new] (registry/mutate!
                    (fn [reg]
                      (if (contains? reg slug)
                        (update-in reg [slug :tape] tape/apply-compaction-at i lambda floor)
                        reg)))]
     (if-not (contains? new slug)
       (do (event! {:kind :compact! :slug slug :msg (str "@" i " ✗ no such session")})
           {:repl/id slug :repl/error (str "no such repl session: " slug)})
       (let [old-msg (get-in old [slug :tape i])
             new-msg (get-in new [slug :tape i])
             tape'   (get-in new [slug :tape])
             depth   (count tape')]
         (cond
           (= old-msg new-msg)
           (do (event! {:kind :compact! :slug slug :msg (str "@" i " no-op")})
               {:repl/id slug :repl/index i :repl/outcome :no-op :repl/depth depth})

           (:compacted? new-msg)
           (let [saved (- (count (:original new-msg)) (count (:text new-msg)))]
             ;; trace: the durable twin of :original (ratified Q4) — the
             ;; on-tape copy dies with the registry; this blob is the
             ;; arm-diff ground truth against silent confabulation
             (trace/capture! slug i "original" old-msg (:original new-msg))
             (event! {:kind :compact! :slug slug :msg (str "@" i " −" saved "ch")})
             {:repl/id     slug
              :repl/index  i
              :repl/outcome :accepted
              :repl/saved  saved
              :repl/depth  depth})

           :else
           (do (event! {:kind :compact! :slug slug :msg (str "@" i " declined (past ceiling)")})
               {:repl/id slug :repl/index i :repl/outcome :declined :repl/depth depth}))))))

;; ── drivers ────────────────────────────────────────────────────────────────────

(defn- reply-metadata
  "Shared driver return: the assistant turns ADDED to `before` (exact regardless
   of any preprocessing xform), depth, turns."
  [slug before tape']
  (let [added   (subvec tape' (count before))
        replies (->> added (filter #(= :assistant (:role %))) (mapv :text))]
    {:repl/id      slug
     :repl/depth   (count tape')
     :repl/added   (count replies)
     :repl/replies replies}))

(defcommand eval!
  "Run ONE completion on the session's tape. Ensures the session (creating
   with `opts` overrides), then the D2 shape (architecture.md § D2):

     swap!(append-user)  →  complete(snapshot)  →  swap!(append-assistant)

   1. `registry/mutate!` appends the user turn — persist-FIRST, retry-safe: a
      completion throw below leaves this turn on the tape for a retry to
      continue from. The tape it lands on (`snapshot`) is what the completion
      answers.
   2. `complete` runs OUTSIDE any swap (an oracle query — D_formal-shape — must
      never run inside a pure swap fn).
   3. `registry/mutate!` appends the assistant reply onto whatever tape is
      CURRENT at that moment — not `snapshot`. `:turns` is DERIVED inside the
      swap fn from the resulting tape (never read-stale-and-add). If the
      current tape at step 3 differs from `snapshot` (`old`'s tape ≠ the tape
      `complete` actually answered), something else appended between steps 1
      and 3 — append anyway (interleave, never clobber) and emit a
      {:kind :raced} receipt: visible, never silent, no locks.

   The session vanishing mid-completion (a concurrent `drop!`) is handled the
   same way, not thrown: each `mutate!` fn no-ops on a missing slug, and the
   driver reports {:repl/error …} with a loud event instead of an NPE.

   Returns {:repl/id :repl/reply :repl/depth :repl/turns} or, on send failure
   or a vanished session, {:repl/id :repl/error} — as DATA, never a throw
   (λ mirror).

   opts: config overrides (config-keys, persisted) ⊕ :complete-fn (injected IO;
   default `completion/default-complete`)."
  {:manual   "Chat: send text to a session; the reply is appended to its tape."
   :args     [:catn [:slug Slug] [:text :string] [:opts [:? EvalOpts]]]
   :defaults {opts {}}}
  [slug text opts]
  (let [sess           (open! slug opts)
         complete       ((get opts :complete-fn completion/default-complete) (:config sess) slug)
         [_ after-user] (registry/mutate!
                         (fn [reg]
                           (if (contains? reg slug)
                             (update-in reg [slug :tape] tape/append-user text)
                             reg)))]
     (if-not (contains? after-user slug)
       (do (event! {:kind :eval! :slug slug :msg "✗ session gone"})
           {:repl/id slug :repl/error "session no longer exists"})
       (let [snapshot (:tape (get after-user slug))]
         (event! {:kind :eval! :slug slug :msg "…"})
         (try
           (let [reply      (complete snapshot)
                 [old new]  (registry/mutate!
                             (fn [reg]
                               (if (contains? reg slug)
                                 (update reg slug
                                         (fn [s]
                                           (let [tape' (tape/append-assistant (:tape s) reply)]
                                             (assoc s
                                                    :tape  tape'
                                                    :turns (count (tape/assistant-indices tape'))))))
                                 reg)))]
             (if-not (contains? new slug)
               (do (event! {:kind :eval! :slug slug :msg "✗ session gone mid-completion"})
                   {:repl/id slug :repl/error "session dropped mid-completion"})
               (let [final (get new slug)
                     tape' (:tape final)]
                 (when (not= (:tape (get old slug)) snapshot)
                   (event! {:kind :raced :slug slug
                            :msg  "reply answered a stale prefix — appended, not clobbered"}))
                 ;; :io/ref rides ✓ only when OUR completion path captured
                 ;; (an injected :complete-fn bypasses the capture seam — a
                 ;; ref to a blob that never landed would be a lie). Turn ≡
                 ;; (count snapshot): the index the capture used, race-exact.
                 (event! (cond-> {:kind :eval! :slug slug :msg (str "✓@" (count tape'))}
                           (and (trace/capturing?) (nil? (:complete-fn opts)))
                           (assoc :io/ref (trace/ref-for slug (count snapshot) "response"))))
                 {:repl/id    slug
                  :repl/reply (:text (last tape'))
                  :repl/depth (count tape')
                  :repl/turns (:turns final)})))
           (catch Throwable t
             (event! (err-receipt :eval! slug t))
             {:repl/id    slug
              :repl/error (str "send failed: " (ex-message t))}))))))

(defcommand run-battery!
  "Fold a FIXED probe sequence over the session's tape via `transduce` (the
   transducer driver — G2: eager, never lazy). `:xform` (default identity)
   preprocesses/instruments the probe stream (rf→rf prosthesis lands here in a
   later increment). Batteries ≡ the cartographer's starting corpus (verbum's
   vocab-propagation / mode-coloring / three-room / socket-test are probe
   sequences). All-or-nothing on a mid-battery failure (loud; λ antifragile) —
   a fault-tolerant variant is a later fork.

   opts: config overrides ⊕ :xform ⊕ :complete-fn."
  {:manual   "Run a fixed probe sequence, appending every turn to the tape."
   :args     [:catn [:slug Slug] [:probes [:sequential :string]] [:opts [:? BatteryOpts]]]
   :defaults {opts {}}}
  [slug probes opts]
  (let [sess      (open! slug opts)
         before    (:tape sess)
         complete  ((get opts :complete-fn completion/default-complete) (:config sess) slug)
         rf        (eval-rf {:complete complete})
         _         (event! {:kind :battery! :slug slug :msg (str (count probes) "…")})
         ;; the fold stays local (G2: eager, transduce over the STARTING
         ;; snapshot) — completions never run inside a swap fn. Only the
         ;; final store is a mutate!, and it APPENDS the added portion onto
         ;; whatever tape is CURRENT, not `before` (D2, same shape as eval!).
         tape'     (transduce (get opts :xform identity) rf before (vec probes))
         added     (subvec tape' (count before))
         [old new] (registry/mutate!
                    (fn [reg]
                      (if (contains? reg slug)
                        (update reg slug
                                (fn [s]
                                  (let [tape'' (into (:tape s) added)]
                                    (assoc s
                                           :tape  tape''
                                           :turns (count (tape/assistant-indices tape''))))))
                        reg)))]
     (if-not (contains? new slug)
       (do (event! {:kind :battery! :slug slug :msg "✗ session gone"})
           {:repl/id slug :repl/error "session no longer exists"})
       (let [final (get new slug)]
         ;; raced ⟺ the tape had already moved (some other write landed)
         ;; between the fold's snapshot and this final append — append
         ;; anyway (interleave, never clobber), never silent
         (when (not= (:tape (get old slug)) before)
           (event! {:kind :raced :slug slug :msg "battery! appended onto a moved tape"}))
         ;; all-or-nothing: a mid-battery throw leaves the start receipt
         ;; dangling — the missing ✓ IS the signal (loud; λ antifragile)
         (event! {:kind :battery! :slug slug :msg (str (count (tape/assistant-indices added)) "✓")})
         (assoc (reply-metadata slug before (:tape final)) :repl/turns (:turns final))))))

(defn- bounce-output
  "Apply the rf's step to a FIXED prefix and read the assistant text — the
   trampoline's bounce (fork the immutable prefix, complete, read, DISCARD the
   growth). λ converge: the SAME `step` as eval!/battery, applied map-style."
  [step prefix input]
  (:text (last (step prefix input))))

(defcommand bounce!
  "Bounce ONE input off the session's FIXED tape (the fixed point): complete once
   from the prefix, return the output, leave the tape UNCHANGED. Non-committing —
   unlike eval!, the fixed point does not move, so you can keep bouncing varied
   inputs (interactive prompt iteration; the KV prefix is reused). Returns
   {:repl/id :repl/input :repl/output :repl/depth} or {:repl/id :repl/error}.
   opts: config overrides ⊕ :complete-fn."
  {:manual   "Try ONE input against a session without changing its tape."
   :args     [:catn [:slug Slug] [:text :string] [:opts [:? EvalOpts]]]
   :defaults {opts {}}}
  [slug text opts]
  (let [sess     (open! slug opts)
         complete ((get opts :complete-fn completion/default-complete) (:config sess) slug)
         step     (eval-rf {:complete complete})]
     (event! {:kind :bounce! :slug slug :msg "…"})
     (try
       ;; tapeless: no assistant tape index exists for this send — N bounces
       ;; off one prefix would collide on the same turn number. Receipt-only
       ;; (human-decided, design § build decisions 1).
       (let [out (binding [trace/*capture?* false]
                   (bounce-output step (:tape sess) text))]
         (event! {:kind :bounce! :slug slug :msg "✓"})
         {:repl/id     slug
          :repl/input  text
          :repl/output out
          :repl/depth  (count (:tape sess))})   ; the FIXED depth — unchanged
       (catch Throwable t
         (event! (err-receipt :bounce! slug t))
         {:repl/id slug :repl/error (str "send failed: " (ex-message t))}))))

(defcommand trampoline!
  "Bounce a vector of VARIED inputs off the session's FIXED tape — fan-out from
   the fixed point (`map`, not `fold`: inputs never accumulate into each other;
   fork-isolation from the immutable acc gives per-bounce independence). The tape
   is UNCHANGED. Returns
     {:repl/id :repl/depth :repl/bounces [{:input :output} | {:input :error} …]}
   PER-BOUNCE error-as-data — one failed bounce does not sink the scan (bounces
   are independent, unlike battery's all-or-nothing fold). This is the fast
   prompt-iteration driver: load a context once, see what every candidate input
   produces from that same fixed point. opts: config overrides ⊕ :complete-fn."
  {:manual   "Try MANY inputs against the same fixed tape; nothing is saved."
   :args     [:catn [:slug Slug] [:inputs [:sequential :string]] [:opts [:? EvalOpts]]]
   :defaults {opts {}}}
  [slug inputs opts]
  (let [sess     (open! slug opts)
         prefix   (:tape sess)
         complete ((get opts :complete-fn completion/default-complete) (:config sess) slug)
         step     (eval-rf {:complete complete})
         _        (event! {:kind :tramp! :slug slug :msg (str (count inputs) "…")})
         ;; tapeless — receipt-only, same as bounce! (see its comment)
         bounces  (binding [trace/*capture?* false]
                    (mapv (fn [input]
                            (try {:input input :output (bounce-output step prefix input)}
                                 (catch Throwable t
                                   (cond-> {:input input
                                            :error (str "send failed: " (ex-message t))}
                                     ;; a fan-out over a down backend fails N
                                     ;; times; each failure kept its own file
                                     (:trace/ref (ex-data t))
                                     (assoc :io/ref (:trace/ref (ex-data t)))))))
                          (vec inputs)))
         errs     (count (filter :error bounces))]
     (event! {:kind :tramp! :slug slug
              :msg  (str (- (count bounces) errs) "✓" (when (pos? errs) (str " " errs "✗")))})
     {:repl/id      slug
      :repl/depth   (count prefix)
      :repl/bounces bounces}))

(defcommand fork!
  "Copy the tape (call/cc): a NEW session `to` carrying the same tape ∧ config as
   `from`, with any `opts` config overrides merged in — two continuations from
   one prefix (cheap for the model: shared KV prefix). Override a knob (e.g.
   {::preamble? false}) for the counterfactual boot.

   `:at` ≡ fork an OLDER turn: truncate the copy to the first N MESSAGES (the
   depth number the prompt shows — 2 per exchange). (fork! :scratch :redo
   {:at 2}) branches from scratch[2]; the parent keeps its full tape — the
   tape is a TREE, the conversation one path (standalone accretion; anima's
   fork! is the :at-less special case).

   Refuses a missing `from` or an existing `to` (no silent clobber —
   λ escalate). Returns {:repl/id :repl/from :repl/depth :repl/config} or
   {:repl/error …} as data.

   D2: BOTH existence checks move INSIDE the `registry/mutate!` fn — no
   read-check-then-write gap for a concurrent `fork!`/`drop!` to land in.
   `f` no-ops (returns `reg` unchanged) on either a missing `from` or an
   existing `to`; the caller then inspects `old` (the pre-mutation snapshot
   `mutate!` returns) to tell WHICH no-op happened and report the right
   error — `old` is exactly the map `f` saw on its one successful
   application (swap!'s contract), so checking against it post-hoc carries
   no TOCTOU window of its own."
  {:manual   "Branch a session copy. {:at N} branches from an older turn."
   :args     [:catn [:from Slug] [:to Slug] [:opts [:? ForkOpts]]]
   :defaults {opts {}}}
  [from to opts]
  (let [[old new]
         (registry/mutate!
          (fn [reg]
            (let [src (get reg from)]
              (cond
                (nil? src)         reg
                (contains? reg to) reg
                :else
                (let [copy (-> src
                               (assoc :slug to :forked-from from :created-at (System/currentTimeMillis))
                               (update :config merge (select-keys opts config-keys))
                               (cond-> (:at opts) (update :tape tape/truncate-at (:at opts))))
                      ;; the BRANCH POINT — the tree edge is (from @ forked-at
                      ;; → to); without it an :at fork's edge is lossy (tree
                      ;; views need it). :turns re-DERIVED from the copied
                      ;; tape (not copied from the parent's counter): an :at
                      ;; truncation drops assistant turns, and the tape is
                      ;; the ground truth the counter must agree with.
                      copy (assoc copy
                                  :forked-at (count (:tape copy))
                                  :turns     (count (tape/assistant-indices (:tape copy))))]
                  (assoc reg to copy))))))]
     (cond
       (nil? (get old from))
       (do (event! {:kind :fork! :slug from :msg (str "→" to " ✗ no such session")})
           {:repl/error (str "no such repl session: " from)})

       (contains? old to)
       (do (event! {:kind :fork! :slug from :msg (str "→" to " ✗ already exists")})
           {:repl/error (str "repl session already exists: " to)})

       :else
       (let [copy (get new to)]
         (event! {:kind :fork! :slug from :msg (str "→" to "@" (:forked-at copy))})
         {:repl/id     to
          :repl/from   from
          :repl/depth  (count (:tape copy))
          :repl/config (:config copy)}))))

(defn variant-slug
  "The cross-ns child-naming convention (D5): `ab!` names a variant child
   `parent-variant` — this is the ONE fn that does it. `tui.frame/short-name`
   strips that exact prefix back off for display (step 6); the two MUST
   agree, so this is the single source both sides call (or, for the TUI's
   pure display side, encode the inverse of) rather than two independently
   hand-rolled `str`/keyword dances drifting apart."
  [from vk]
  (keyword (str (name from) "-" (name vk))))

(defcommand ab!
  "Fan ONE probe across VARIED interpreters from a common parent — the DUAL of
   trampoline! (which fans varied inputs off one interpreter). ∀variant:
   fork!(from → from-variant, config overrides ⊕ :at) → eval!(probe). The
   parent never moves; the variants differ ONLY by their overrides — an N-arm
   counterfactual (the preamble/system/model/temperature is the isolated
   variable). Unlike trampoline!'s discarded bounces, the children PERSIST:
   named, comparable, re-drivable — continue any arm, fork the winner, fan
   again (progressive improvement; the tree is the experiment record).

   `variants` ≡ {variant-kw config-overrides}; child slug ≡ (variant-slug from
   variant-kw) — `from-variant` (D5, the one naming fn — see its docstring).
   opts: :at (branch an older turn) ⊕ :complete-fn (test seam, forwarded).

   ITERATED SEARCH (the generational recipe — hill-climbing, GA, prompt
   evolution). Fan gen 0 off a clean parent, score the arms, then fan gen 1
   off the WINNER **with `{:at 0}`**:

     (ab! :ga        {:terse {:system …} …} probe)          ; gen 0
     (ab! :ga-terse  {:m1    {:system …} …} probe {:at 0})   ; gen 1

   `:at 0` truncates the child's copy to zero messages, so the next
   generation answers the probe on a CLEAN tape while `:forked-from` still
   records descent. Without it each generation inherits its parent's
   conversation and you are scoring genotypes against a contaminated prefix —
   the single non-obvious move in the whole loop.

   The genotype is RECOVERABLE, never stored as a delta: the mutation is
   `(diff (get-in parent [:config :system]) (get-in child [:config :system]))`,
   so lineage lives in `:forked-from` ∧ `:config` and nowhere else. Corollary:
   `drop!`-ing a losing arm SEVERS the diff chain of everything descended from
   it — prune the population and you prune the genealogy.

   Cost shape (measured, qwen36-35b-a3b): varying `:system` is varying the
   PREFIX, so every arm pays a full prefill — ~1.1s/arm vs ~170ms for a
   trampoline! bounce that shares the tape's KV. Sequential by design. A
   20×10 search ≈ 4 min wall clock; the compute is never the bottleneck, the
   fitness function is. Details ≡ knowledge/fan-out-lineage.md.
   Sequential on purpose (local servers contend on slots; determinism > speed).
   Per-variant errors as data — one failed arm doesn't sink the fan. Returns
   {:repl/id :repl/probe :repl/variants {vk fork-error | eval-result}}.
   (STANDALONE accretion #2 — not in anima.)"
  {:manual   "Fork N config variants and send the same probe to each. Iterating? Fan the next generation off the winner with {:at 0} — clean tape, lineage kept."
   :args     [:catn [:from Slug] [:variants [:map-of :keyword SessionOpts]]
              [:probe :string] [:opts [:? AbOpts]]]
   :defaults {opts {}}}
  [from variants probe opts]
  (let [arms (into {}
                    (map (fn [[vk overrides]]
                           (let [to     (variant-slug from vk)
                                 forked (fork! from to (merge overrides (select-keys opts [:at])))]
                             [vk (if (:repl/error forked)
                                   forked
                                   (eval! to probe (select-keys opts [:complete-fn])))])))
                    variants)
         errs (count (filter :repl/error (vals arms)))]
     (event! {:kind :ab! :slug from
              :msg  (str (- (count arms) errs) "✓" (when (pos? errs) (str " " errs "✗")))})
     {:repl/id       from
      :repl/probe    probe
      :repl/variants arms}))

;; ── the ONE submission grammar — D5 ─────────────────────────────────────────

(defn parse-submission
  "The ONE submission grammar (D5, architecture.md § D5): defined ONCE here,
   consumed by BOTH main's plain loop and main's tui-submit! (the wire
   layer). A surface layers its OWN intercepts ON TOP of a `:kind` (main's
   use-form? checks whether a `:form` line is specifically `(use! …)`) rather
   than re-deriving the branching itself — two independently hand-rolled
   greps for `\"(\"` is exactly how a plain-loop/TUI grammar drift bug gets
   born.

     nil, or \":q\" once trimmed        → {:kind :quit}
     blank (after trim)                 → {:kind :noop}
     left-trimmed line starts with \"(\"  → {:kind :form :text line}
     anything else                      → {:kind :chat :text line}

   `:text` always carries the ORIGINAL `line` unmodified — callers that want
   a trimmed submission trim before calling (main's plain loop does, so its
   downstream eval/chat calls stay byte-identical to pre-grammar behavior);
   this fn only classifies."
  [line]
  (cond
    (or (nil? line) (= ":q" (some-> line str/trim))) {:kind :quit}
    (str/blank? line)                                {:kind :noop}
    (str/starts-with? (str/triml line) "(")           {:kind :form :text line}
    :else                                             {:kind :chat :text line}))
