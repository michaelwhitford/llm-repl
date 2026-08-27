(ns us.whitford.llm-repl.core
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
   (The request-building/backend machinery itself now lives one layer down,
   in `completion` — D4, refactor step 3; this ns drives the rf/registry
   shape around whatever `completion` or an injected `:complete-fn` returns.)

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
   `completion/default-complete`."
  (:require
   [clojure.string :as str]
   [us.whitford.llm-repl.completion :as completion]
   [us.whitford.llm-repl.registry :as registry]
   [us.whitford.llm-repl.roster :as llm]
   [us.whitford.llm-repl.tape :as tape]))

;; ── registry (the runtime layer now lives in `registry` — D2/D3; this ns
;;    delegates so the WIRE-EVAL'd var names keep resolving. client.clj evals
;;    the literal strings "@c/sessions*" and "@c/events*" over nREPL, and
;;    main.clj calls `core/event!` with plain strings — both must keep working
;;    unchanged until refactor step 5 retires this ns. DO NOT rename these.) ──

(def ^{:doc "Delegates to `registry/sessions*` — the SAME atom (this `def`
   binds to the atom object itself, not a deref), so `@core/sessions*` and
   `@registry/sessions*` are one and the same live registry. The var stays
   here, unchanged in name, purely for wire compatibility (remote clients'
   `@c/sessions*` nREPL evals) until step 5 moves callers onto the api ns."}
  sessions*
  registry/sessions*)

(def ^{:doc "Delegates to `registry/events*` — same wire-compatibility reason
   as `sessions*` above (`@c/events*`)."}
  events*
  registry/events*)

(defn event!
  "Delegates to `registry/event!` (D3): assigns :id/:at, bumps version*,
   bounds the ring at 200. Keeps accepting plain STRINGS — main.clj's
   `(core/event! \"use! :x\")` and every event! call in this ns below —
   `registry/event!` coerces a string to `{:kind :note :msg s}`. Returns the
   completed event map (callers that want the rendered line: `event-line`)."
  [e]
  (registry/event! e))

(def default-config
  "The interpreter config an unqualified session runs — :model resolves from
   the config file's :default-model (roster/default-model, read at load).
   Domain-neutral — this REPL is a tool agents are GRANTED, NOT bound to any
   subject/observer pairing; the cartographer picks its subject, others pick
   theirs. Every knob is overridable per session (`open!`/`eval!` opts) and
   per fork. `:system` ≡ `completion/default-system` — the minimal, constant
   system prompt held constant across a preamble-on/off fork so the
   counterfactual boot isolates exactly one variable (the preamble)."
  {:model       (llm/default-model)
   :system      completion/default-system
   :preamble?   true
   :thinking    nil
   :temperature nil
   :tools       (llm/default-tools)})

(def config-keys
  "The interpreter knobs a caller may set at open/eval/fork — merged into the
   session's :config (persisted; a REPL remembers its interpreter).
   :preamble ≡ a per-session boot-text override (string | {:file path} |
   false ≡ none); absent inherits model > provider > config chain
   (roster/resolve-preamble). :preamble? stays the apply-or-not boolean —
   the counterfactual knob. :tools arms the SELF-EVAL loop (accretion #3):
   true ≡ every registered tool | [kw …] ≡ whitelist from tools/tool-registry*
   | nil/absent ≡ none (plain completion, anima behavior). Persisted like any
   knob — forkable, ab!-able: (ab! :s {:bare {:tools nil} :armed {:tools true}}
   probe) is the does-the-tool-help counterfactual."
  [:model :system :preamble :preamble? :thinking :temperature :tools])

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

(defn ^{:manual "Get or create a session. Options set its model, system, temperature."} open!
  "Get-or-create the session at `slug`, merging any config overrides from
   `opts` (config-keys) into its :config. Returns the session map.

   D2: the get-or-create decision is made INSIDE the `registry/mutate!` fn —
   no read-then-decide-then-store gap for a concurrent `open!`/`eval!` on the
   same slug to land in unnoticed. Creation is detected from the [old new]
   pair (`old` lacked the slug ⟺ this call created it), never from a stale
   local `existing` check."
  ([slug] (open! slug {}))
  ([slug opts]
   (let [overrides (select-keys opts config-keys)
         f         (fn [reg]
                     (if (contains? reg slug)
                       (update-in reg [slug :config] merge overrides)
                       (assoc reg slug {:slug       slug
                                        :tape       []
                                        :config     (merge default-config overrides)
                                        :turns      0
                                        :created-at (System/currentTimeMillis)})))
         [old new] (registry/mutate! f)]
     (when-not (contains? old slug)
       (event! {:kind :open! :slug slug}))
     (get new slug))))

(defn ^{:manual "The full session map — tape included."} snapshot
  "The session map at `slug`, or nil (λ observe). `:tape` is the canonical tape."
  [slug]
  (get @sessions* slug))

(defn ^{:manual "List all sessions: model, depth, turns, fork parent."} sessions-list
  "A compact index of live sessions (λ glass) — no message bodies."
  []
  (mapv (fn [[slug s]]
          {:slug        slug
           :model       (get-in s [:config :model])
           :preamble?   (get-in s [:config :preamble?])
           :depth       (count (:tape s))
           :turns       (:turns s)
           :forked-from (:forked-from s)
           :forked-at   (:forked-at s)})
        @sessions*))

(defonce ^{:doc "Namespaces the manual compiles from — an OPEN SLOT (λ extend):
   a surface with its own operator commands registers its ns here at load
   (main adds itself for use!). One manual; banner, (help), overlay, and the
   MCP facade all print the same curated truth."}
  manual-namespaces*
  (atom '[us.whitford.llm-repl.core]))

(defn register-manual-ns!
  "Add `ns-sym` to the manual's compile set (idempotent)."
  [ns-sym]
  (swap! manual-namespaces* #(vec (distinct (conj % ns-sym)))))

(defn ^{:manual "The command manual as data — for agents and tools."} manual
  "The operator manual AS DATA (λ glass): every `^:manual` command across the
   registered namespaces as {:name :arglists :summary :doc} — COMPILED from
   ns-publics, never hand-written (structure > instruction: the metadata is
   the source of truth; tagging curates the operator surface out of the
   plumbing). The ONE seam agent surfaces derive from — (help) renders it,
   the MCP facade will compile its tool list from it."
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
                    :doc      (:doc m)}))))
       (sort-by (comp str :name))
       vec))

(defn ^{:manual "This help."} help
  "Human rendering of (manual): one entry per command — name, arglists, and
   the CURATED human summary (the ^:manual tag's string value; docstrings
   stay maintainer/agent-dense — two audiences, two texts, ONE seam).
   Returns a STRING (caller prints; a println here would corrupt the TUI's
   alt screen). Full docs: (manual), or (:doc (meta #'cmd))."
  []
  (->> (manual)
       (map (fn [{:keys [name arglists summary]}]
              (str (format "%-14s" name) " " (pr-str arglists) "\n"
                   "    " summary)))
       (str/join "\n")))

(defn ^{:manual "Delete a session."} drop!
  "Discard the session at `slug` (mutate!-only, D2). Returns true when one
   existed (detected from `old`, the pre-mutation snapshot)."
  [slug]
  (let [[old _] (registry/mutate! #(dissoc % slug))
        existed? (contains? old slug)]
    (when existed? (event! {:kind :drop! :slug slug}))
    existed?))

(defn ^{:manual "Delete ALL sessions."} reset-all!
  "Clear the whole registry (test seam / operator reset)."
  []
  (registry/mutate! (constantly {}))
  (event! {:kind :reset-all!})
  nil)

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

(defn ^{:manual "Chat: send text to a session; the reply is appended to its tape."} eval!
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
  ([slug text] (eval! slug text {}))
  ([slug text opts]
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
                 (event! {:kind :eval! :slug slug :msg (str "✓@" (count tape'))})
                 {:repl/id    slug
                  :repl/reply (:text (last tape'))
                  :repl/depth (count tape')
                  :repl/turns (:turns final)})))
           (catch Throwable t
             (event! {:kind :eval! :slug slug :msg (str "✗ " (ex-message t))})
             {:repl/id    slug
              :repl/error (str "send failed: " (ex-message t))})))))))

(defn ^{:manual "Run a fixed probe sequence, appending every turn to the tape."} run-battery!
  "Fold a FIXED probe sequence over the session's tape via `transduce` (the
   transducer driver — G2: eager, never lazy). `:xform` (default identity)
   preprocesses/instruments the probe stream (rf→rf prosthesis lands here in a
   later increment). Batteries ≡ the cartographer's starting corpus (verbum's
   vocab-propagation / mode-coloring / three-room / socket-test are probe
   sequences). All-or-nothing on a mid-battery failure (loud; λ antifragile) —
   a fault-tolerant variant is a later fork.

   opts: config overrides ⊕ :xform ⊕ :complete-fn."
  ([slug probes] (run-battery! slug probes {}))
  ([slug probes opts]
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
         (assoc (reply-metadata slug before (:tape final)) :repl/turns (:turns final)))))))

(defn- bounce-output
  "Apply the rf's step to a FIXED prefix and read the assistant text — the
   trampoline's bounce (fork the immutable prefix, complete, read, DISCARD the
   growth). λ converge: the SAME `step` as eval!/battery, applied map-style."
  [step prefix input]
  (:text (last (step prefix input))))

(defn ^{:manual "Try ONE input against a session without changing its tape."} bounce!
  "Bounce ONE input off the session's FIXED tape (the fixed point): complete once
   from the prefix, return the output, leave the tape UNCHANGED. Non-committing —
   unlike eval!, the fixed point does not move, so you can keep bouncing varied
   inputs (interactive prompt iteration; the KV prefix is reused). Returns
   {:repl/id :repl/input :repl/output :repl/depth} or {:repl/id :repl/error}.
   opts: config overrides ⊕ :complete-fn."
  ([slug text] (bounce! slug text {}))
  ([slug text opts]
   (let [sess     (open! slug opts)
         complete ((get opts :complete-fn completion/default-complete) (:config sess) slug)
         step     (eval-rf {:complete complete})]
     (event! {:kind :bounce! :slug slug :msg "…"})
     (try
       (let [out (bounce-output step (:tape sess) text)]
         (event! {:kind :bounce! :slug slug :msg "✓"})
         {:repl/id     slug
          :repl/input  text
          :repl/output out
          :repl/depth  (count (:tape sess))})   ; the FIXED depth — unchanged
       (catch Throwable t
         (event! {:kind :bounce! :slug slug :msg (str "✗ " (ex-message t))})
         {:repl/id slug :repl/error (str "send failed: " (ex-message t))})))))

(defn ^{:manual "Try MANY inputs against the same fixed tape; nothing is saved."} trampoline!
  "Bounce a vector of VARIED inputs off the session's FIXED tape — fan-out from
   the fixed point (`map`, not `fold`: inputs never accumulate into each other;
   fork-isolation from the immutable acc gives per-bounce independence). The tape
   is UNCHANGED. Returns
     {:repl/id :repl/depth :repl/bounces [{:input :output} | {:input :error} …]}
   PER-BOUNCE error-as-data — one failed bounce does not sink the scan (bounces
   are independent, unlike battery's all-or-nothing fold). This is the fast
   prompt-iteration driver: load a context once, see what every candidate input
   produces from that same fixed point. opts: config overrides ⊕ :complete-fn."
  ([slug inputs] (trampoline! slug inputs {}))
  ([slug inputs opts]
   (let [sess     (open! slug opts)
         prefix   (:tape sess)
         complete ((get opts :complete-fn completion/default-complete) (:config sess) slug)
         step     (eval-rf {:complete complete})
         _        (event! {:kind :tramp! :slug slug :msg (str (count inputs) "…")})
         bounces  (mapv (fn [input]
                          (try {:input input :output (bounce-output step prefix input)}
                               (catch Throwable t
                                 {:input input :error (str "send failed: " (ex-message t))})))
                        (vec inputs))
         errs     (count (filter :error bounces))]
     (event! {:kind :tramp! :slug slug
              :msg  (str (- (count bounces) errs) "✓" (when (pos? errs) (str " " errs "✗")))})
     {:repl/id      slug
      :repl/depth   (count prefix)
      :repl/bounces bounces})))

(defn ^{:manual "Branch a session copy. {:at N} branches from an older turn."} fork!
  "Copy the tape (call/cc): a NEW session `to` carrying the same tape ∧ config as
   `from`, with any `opts` config overrides merged in — two continuations from
   one prefix (cheap for the model: shared KV prefix). Override a knob (e.g.
   {:preamble? false}) for the counterfactual boot.

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
  ([from to] (fork! from to {}))
  ([from to opts]
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
          :repl/config (:config copy)})))))

(defn ^{:manual "Fork N config variants and send the same probe to each."} ab!
  "Fan ONE probe across VARIED interpreters from a common parent — the DUAL of
   trampoline! (which fans varied inputs off one interpreter). ∀variant:
   fork!(from → from-variant, config overrides ⊕ :at) → eval!(probe). The
   parent never moves; the variants differ ONLY by their overrides — an N-arm
   counterfactual (the preamble/system/model/temperature is the isolated
   variable). Unlike trampoline!'s discarded bounces, the children PERSIST:
   named, comparable, re-drivable — continue any arm, fork the winner, fan
   again (progressive improvement; the tree is the experiment record).

   `variants` ≡ {variant-kw config-overrides}; child slug ≡ from-variant.
   opts: :at (branch an older turn) ⊕ :complete-fn (test seam, forwarded).
   Sequential on purpose (local servers contend on slots; determinism > speed).
   Per-variant errors as data — one failed arm doesn't sink the fan. Returns
   {:repl/id :repl/probe :repl/variants {vk fork-error | eval-result}}.
   (STANDALONE accretion #2 — not in anima.)"
  ([from variants probe] (ab! from variants probe {}))
  ([from variants probe opts]
   (let [arms (into {}
                    (map (fn [[vk overrides]]
                           (let [to     (keyword (str (name from) "-" (name vk)))
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
      :repl/variants arms})))
