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
   tape        ≡ accumulator   canonical chat-memory vec (immutable, forkable)
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
   `lib/run` chart lifecycle PER call — the wrong grain. This ns drives the
   model directly at `proto/send-turn` (the ONE op no scaffold can do);
   everything else is deterministic tape management (λ capacity, multiplicative).
   `one-shot` is the degenerate depth-1 case, a sibling — NOT this substrate.

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
   core + injected seam); tests inject a stub, default hits the roster."
  (:require
   [com.fulcrologic.statecharts.promise :as p]
   [escapement.llm.protocol :as proto]
   [us.whitford.llm-repl.chat-memory :as mem]
   [us.whitford.llm-repl.roster :as llm]))

;; ── registry (Option A: named accumulators carry their interpreter config) ────

(defonce ^{:doc "The continuation registry: {slug → session}. A session ≡
   {:slug :tape [canonical] :config {…} :turns :created-at :forked-from}.
   defonce so a reload keeps live tapes."}
  sessions*
  (atom {}))

(def default-system
  "The minimal, constant system prompt — held constant across a preamble-on/off
   fork so the counterfactual boot isolates exactly one variable (the preamble)."
  "You are a precise assistant.")

(def default-config
  "The interpreter config an unqualified session runs — :model resolves from
   the config file's :default-model (roster/default-model, read at load).
   Domain-neutral — this REPL is a tool agents are GRANTED, NOT bound to any
   subject/observer pairing; the cartographer picks its subject, others pick
   theirs. Every knob is overridable per session (`open!`/`eval!` opts) and
   per fork."
  {:model       (llm/default-model)
   :system      default-system
   :preamble?   true
   :thinking    nil
   :temperature nil})

(def config-keys
  "The interpreter knobs a caller may set at open/eval/fork — merged into the
   session's :config (persisted; a REPL remembers its interpreter).
   :preamble ≡ a per-session boot-text override (string | {:file path} |
   false ≡ none); absent inherits model > provider > config chain
   (roster/resolve-preamble). :preamble? stays the apply-or-not boolean —
   the counterfactual knob."
  [:model :system :preamble :preamble? :thinking :temperature])

;; ── pure tape mechanics (no backend, no booted system) ────────────────────────

(defn assistant-text
  "Concatenate the `:text` content blocks of an escapement Response into the
   canonical assistant turn. Thinking/tool blocks are dropped — increment 1
   feeds back TEXT (matches chat-memory; correct for thinking-off subject
   probes; carrying thinking+signature is a later fork)."
  [response]
  (->> (:content response)
       (filter #(= :text (:type %)))
       (map :text)
       (apply str)))

(defn build-request
  "config ⊕ slug ⊕ tape → an escapement `Request` (Anthropic shape). Pure.
   `:system` boots the nucleus preamble iff `:preamble?` (λ prompt); false ∧
   blank ⇒ no system at all (a raw-prose probe). `:conversation/id` ≡ slug (KV
   slot pin + prompt-cache key); `:system-cache-control` stamps `cache_prompt`
   on the llama.cpp wire (the direct path bypasses escapement's auto-cache — we
   do it here)."
  [{:keys [model system preamble? thinking temperature] :as config} slug tape]
  (let [sys (or system default-system)
        sys (if preamble?
              (llm/with-preamble (llm/resolve-preamble config) sys)
              (not-empty sys))]
    (cond-> {:model                (name model)
             :messages             (mem/render-messages tape)
             :conversation/id      slug
             :system-cache-control {:type :ephemeral}}
      (some? sys)         (assoc :system sys)
      (some? thinking)    (assoc :thinking thinking)
      (some? temperature) (assoc :temperature temperature))))

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
     (let [t (mem/append-user tape probe)]
       (mem/append-assistant t (complete t))))))

;; ── the IO seam (injected — default ≡ the config-roster backend) ──────────────

(defn default-complete
  "config ⊕ slug → (fn [tape] → reply-text): build the request, send it at the
   backend seam through the roster backend (built from the config file), await,
   extract the assistant text. Injectable — tests pass their own :complete-fn;
   an embedding host injects its arbitered/wrapped backend here (open slot)."
  [config slug]
  (fn [tape]
    (let [backend  (llm/wrapped-backend (:model config)
                                        {:priority :priority/normal
                                         :slug     (str "repl-" (name slug))})
          response (p/await! (proto/send-turn backend (build-request config slug tape)))]
      (assistant-text response))))

;; ── lifecycle + observability ─────────────────────────────────────────────────

(defn- store! [slug sess] (swap! sessions* assoc slug sess) sess)

(defn open!
  "Get-or-create the session at `slug`, merging any config overrides from `opts`
   (config-keys) into its :config. Returns the session map (also stored)."
  ([slug] (open! slug {}))
  ([slug opts]
   (let [overrides (select-keys opts config-keys)
         existing  (get @sessions* slug)
         sess      (if existing
                     (update existing :config merge overrides)
                     {:slug       slug
                      :tape       []
                      :config     (merge default-config overrides)
                      :turns      0
                      :created-at (System/currentTimeMillis)})]
     (store! slug sess))))

(defn snapshot
  "The session map at `slug`, or nil (λ observe). `:tape` is the canonical tape."
  [slug]
  (get @sessions* slug))

(defn sessions-list
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

(defn drop!
  "Discard the session at `slug`. Returns true when one existed."
  [slug]
  (let [existed? (contains? @sessions* slug)]
    (swap! sessions* dissoc slug)
    existed?))

(defn reset-all!
  "Clear the whole registry (test seam / operator reset)."
  []
  (reset! sessions* {}))

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

(defn eval!
  "Run ONE completion on the session's tape (interactive driver — applies the
   rf's 2-arity STEP). Ensures the session (creating with `opts` overrides),
   persists the user turn FIRST (retry-safe on failure), completes, appends the
   assistant reply. Returns
     {:repl/id :repl/reply :repl/depth :repl/turns}
   or, on send failure, {:repl/id :repl/error} — as DATA, never a throw
   (λ mirror: the tape keeps the user turn so a retry continues from it).

   opts: config overrides (config-keys, persisted) ⊕ :complete-fn (injected IO;
   default `default-complete`)."
  ([slug text] (eval! slug text {}))
  ([slug text opts]
   (let [sess       (open! slug opts)
         before     (:tape sess)
         complete   ((get opts :complete-fn default-complete) (:config sess) slug)
         rf         (eval-rf {:complete complete})]
     ;; persist the user turn first — a completion throw leaves it for retry
     (store! slug (update sess :tape mem/append-user text))
     (try
       (let [tape' (rf before text)
             done  (-> sess (assoc :tape tape') (update :turns inc))]
         (store! slug done)
         {:repl/id    slug
          :repl/reply (:text (last tape'))
          :repl/depth (count tape')
          :repl/turns (:turns done)})
       (catch Throwable t
         {:repl/id    slug
          :repl/error (str "send failed: " (ex-message t))})))))

(defn run-battery!
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
   (let [sess     (open! slug opts)
         before   (:tape sess)
         complete ((get opts :complete-fn default-complete) (:config sess) slug)
         rf       (eval-rf {:complete complete})
         tape'    (transduce (get opts :xform identity) rf before (vec probes))
         added    (count (filter #(= :assistant (:role %)) (subvec tape' (count before))))]
     (store! slug (-> sess (assoc :tape tape') (update :turns + added)))
     (assoc (reply-metadata slug before tape') :repl/turns (+ (:turns sess) added)))))

(defn- bounce-output
  "Apply the rf's step to a FIXED prefix and read the assistant text — the
   trampoline's bounce (fork the immutable prefix, complete, read, DISCARD the
   growth). λ converge: the SAME `step` as eval!/battery, applied map-style."
  [step prefix input]
  (:text (last (step prefix input))))

(defn bounce!
  "Bounce ONE input off the session's FIXED tape (the fixed point): complete once
   from the prefix, return the output, leave the tape UNCHANGED. Non-committing —
   unlike eval!, the fixed point does not move, so you can keep bouncing varied
   inputs (interactive prompt iteration; the KV prefix is reused). Returns
   {:repl/id :repl/input :repl/output :repl/depth} or {:repl/id :repl/error}.
   opts: config overrides ⊕ :complete-fn."
  ([slug text] (bounce! slug text {}))
  ([slug text opts]
   (let [sess     (open! slug opts)
         complete ((get opts :complete-fn default-complete) (:config sess) slug)
         step     (eval-rf {:complete complete})]
     (try
       {:repl/id     slug
        :repl/input  text
        :repl/output (bounce-output step (:tape sess) text)
        :repl/depth  (count (:tape sess))}      ; the FIXED depth — unchanged
       (catch Throwable t
         {:repl/id slug :repl/error (str "send failed: " (ex-message t))})))))

(defn trampoline!
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
         complete ((get opts :complete-fn default-complete) (:config sess) slug)
         step     (eval-rf {:complete complete})]
     {:repl/id      slug
      :repl/depth   (count prefix)
      :repl/bounces (mapv (fn [input]
                            (try {:input input :output (bounce-output step prefix input)}
                                 (catch Throwable t
                                   {:input input :error (str "send failed: " (ex-message t))})))
                          (vec inputs))})))

(defn fork!
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
   {:repl/error …} as data."
  ([from to] (fork! from to {}))
  ([from to opts]
   (let [src (get @sessions* from)]
     (cond
       (nil? src)                {:repl/error (str "no such repl session: " from)}
       (contains? @sessions* to) {:repl/error (str "repl session already exists: " to)}
       :else
       (let [copy (-> src
                      (assoc :slug to :forked-from from :created-at (System/currentTimeMillis))
                      (update :config merge (select-keys opts config-keys))
                      (cond-> (:at opts) (update :tape #(vec (take (:at opts) %)))))
             ;; the BRANCH POINT — the tree edge is (from @ forked-at → to);
             ;; without it an :at fork's edge is lossy (tree views need it)
             copy (assoc copy :forked-at (count (:tape copy)))]
         (store! to copy)
         {:repl/id     to
          :repl/from   from
          :repl/depth  (count (:tape copy))
          :repl/config (:config copy)})))))

(defn ab!
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
   {:repl/id    from
    :repl/probe probe
    :repl/variants
    (into {}
          (map (fn [[vk overrides]]
                 (let [to     (keyword (str (name from) "-" (name vk)))
                       forked (fork! from to (merge overrides (select-keys opts [:at])))]
                   [vk (if (:repl/error forked)
                         forked
                         (eval! to probe (select-keys opts [:complete-fn])))])))
          variants)}))
