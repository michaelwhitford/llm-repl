(ns us.whitford.llm-repl.completion
  "The `io` layer's completion seam (v0.3.0 architecture §§ layers, D4): the
   `:complete-fn` CONTRACT — `config ⊕ slug → (tape → reply-text)` — as its
   own named ns. This IS the anima injection point (library-contract § 3):
   an embedding host passes its own arbitered backend at this signature;
   `default-complete` below is only the STANDALONE default (roster-built,
   tool loop when `:tools` is armed). completion depends DOWNWARD only
   (tape, registry, roster, tools) — it must never require `core` (that
   would be an upward dep; core requires completion, step 3's whole point).

   Two paths share one shape:

     plain-complete  build-request → send → assistant text. The tool-less
                     path (anima's default-complete verbatim — lineage).
     tool-complete   plain-complete's tool-bearing sibling: a chart-free
                     loop at function grain (escapement's own tool loop
                     lives inside a chart invocation processor — the grain
                     core already rejected; the PATTERN is copied, not
                     called). Loop: send(request ⊕ :tools) → tool_use
                     blocks? → dispatch each → append assistant(content) ⊕
                     user(tool_results) to LOOP-LOCAL messages → resend …
                     until a text-only response ∨ the bounce budget. The
                     TAPE only ever sees user_turn ⊕ final_text — the inner
                     exchange is loop-local (shape stable → prefix cacheable
                     → compaction untouched; the rf contract and all four
                     drivers in `core` are unaware this loop exists). Every
                     dispatch emits a ⚡ receipt (equal clients at both
                     layers — an attached surface watches the model work).
                     Honest caveat: a tool turn BENDS `messages[] ≡ truth`
                     (evals are effectful; replay from the tape alone won't
                     reproduce them — the receipt stream is the trace;
                     payload persistence is the deferred trace-durability
                     fork, architecture.md § placeholders).

   D4 amendments (2026-08-27 live A/B, qwen3.6-35b — landed here):
     (a) slug-aware orientation — `tools-system` is now a `{slug}` TEMPLATE,
         `with-tools-system` substitutes it (see that def's docstring for
         the WHY).
     (b) the bounce budget is structural AND visible — every tool_result
         carries the TRUE remaining-dispatch count, and the budget-boundary
         final request STRIPS `:tools` (unreachable > forbidden).
     (c) a blank final assistant text (a thinking model returning zero
         content blocks) is never a silent `\"\"` — it emits a `:kind
         :error` receipt and returns `empty-completion-marker`, a loud tape
         entry instead."
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts.promise :as p]
   [escapement.llm.protocol :as proto]
   [escapement.tools.protocol :as tp]
   [us.whitford.llm-repl.registry :as registry]
   [us.whitford.llm-repl.roster :as llm]
   [us.whitford.llm-repl.tape :as tape]
   [us.whitford.llm-repl.tools :as tools]))

;; ── pure tape mechanics (no backend, no booted system) ─────────────────────

;; default-system (the baked "You are a precise assistant.") is DEAD (D7):
;; the system voice now resolves through roster's config chain —
;; `resolve-system-prompt` — with that same text as builtin-defaults
;; :system-prompt, the bottom of the chain. Held-constant-across-forks still
;; holds: the chain is deterministic for a given config ⊕ session.

(defn assistant-text
  "Concatenate the `:text` content blocks of an escapement Response into the
   canonical assistant turn. Thinking/tool blocks are dropped — increment 1
   feeds back TEXT (matches the tape ns's message shape; correct for
   thinking-off subject probes; carrying thinking+signature is a later
   fork). \"\" when no text blocks landed — callers wanting the loud
   empty-final treatment go through `loud-final-text`, not this fn directly."
  [response]
  (->> (:content response)
       (filter #(= :text (:type %)))
       (map :text)
       (apply str)))

(defn build-request
  "config ⊕ slug ⊕ tape → an escapement `Request` (Anthropic shape). Pure of
   all but the config chain. The system voice resolves through roster's D7
   chain (session :system > model > provider > root :system-prompt); the
   preamble layer glues on top iff `:preamble?` (λ prompt); explicitly-none ∧
   blank ⇒ no system at all (a raw-prose probe). `:conversation/id` ≡ slug (KV
   slot pin + prompt-cache key); `:system-cache-control` stamps `cache_prompt`
   on the llama.cpp wire (the direct path bypasses escapement's auto-cache — we
   do it here)."
  [{:keys [model preamble? thinking temperature] :as config} slug tape]
  (let [sys (llm/resolve-system-prompt config)
        sys (if preamble?
              (llm/with-preamble (llm/resolve-preamble config) sys)
              (not-empty (str sys)))
        ;; session knob → escapement's MODELED :thinking — humans write
        ;; {:thinking false}, the Request wants {:type :disabled} (which the
        ;; llamacpp backend wires to chat_template_kwargs enable_thinking;
        ;; raw false fails Request validation). true ≡ omit ≡ server default
        ;; (thinking models default ON); a modeled map passes through.
        thinking (case thinking
                   false {:type :disabled}
                   true  nil
                   thinking)]
    (cond-> {:model                (name model)
             :messages             (tape/render-messages tape)
             :conversation/id      slug
             :system-cache-control {:type :ephemeral}}
      (some? sys)         (assoc :system sys)
      (some? thinking)    (assoc :thinking thinking)
      (some? temperature) (assoc :temperature temperature))))

;; ── the IO seam (injected — default ≡ the config-roster backend) ───────────

(defn- session-backend
  "The roster backend for a session — the ONE construction expression, shared
   by the plain and tool paths (built INSIDE the step so a failure is caught
   by the driver's try → error-as-data, never a construction-time throw).
   Plain `defn-`, deliberately: tests `with-redefs` this to a stub backend —
   `(var ns/private-fn)` binding forms bypass the privacy check even though a
   direct qualified CALL from another ns would not; keeping this a normal
   defn- (not inlined) is what makes that seam usable."
  [config slug]
  (llm/wrapped-backend (:model config)
                       {:priority :priority/normal
                        :slug     (str "repl-" (name slug))}))

(def empty-completion-marker
  "The loud tape marker substituted for a blank final assistant text (D4
   amendment c). A thinking model can legitimately return zero content
   blocks — thinking-only, observed live via a degenerate reasoning loop.
   Never a silent `\"\"` reply: a blank tape entry reads as a normal (if
   terse) answer, while this string is unmistakably a fault. Public —
   surfaces and tests key off this exact value; `loud-final-text` also
   emits a `{:kind :error}` receipt alongside it."
  "⚠ empty completion (reasoning-only turn — no text blocks returned)")

(defn- loud-final-text
  "assistant-text of `response` — or, when blank, a `{:kind :error}` receipt
   ⊕ `empty-completion-marker` in its place (D4 amendment c). The ONE exit
   point `plain-complete` and both of `tool-complete`'s terminal branches
   route through, so a reasoning-only final is loud no matter which path
   produced it."
  [response slug]
  (let [text (assistant-text response)]
    (if (str/blank? text)
      (do (registry/event! {:kind :error :slug slug :msg "empty final (reasoning-only?)"})
          empty-completion-marker)
      text)))

(defn plain-complete
  "config ⊕ slug → (fn [tape] → reply-text): build the request, send it at the
   backend seam through the roster backend (built from the config file), await,
   extract the assistant text (loud on a reasoning-only blank — D4c). The
   tool-less path (anima's default-complete verbatim — lineage);
   `default-complete` routes here unless :tools is armed."
  [config slug]
  (fn [tape]
    (let [backend  (session-backend config slug)
          response (p/await! (proto/send-turn backend (build-request config slug tape)))]
      (loud-final-text response slug))))

;; ── the self-eval tool loop (STANDALONE accretion #3 — the model as client) ─
;;
;; The model driven BY this repl becomes a client OF it: config :tools puts
;; tool definitions (tools/tool-registry*, escapement's public tools layer) on
;; the wire; tool_use blocks round-trip through tp/dispatch INSIDE the step.
;; See the ns docstring for the loop shape and the honest tape/truth caveat.

(def tool-bounce-budget
  "Max tool round-trips inside ONE completion turn. At the boundary every
   pending call is refused with a teaching tool_result (anima's s049 move:
   make the wrong next move unreachable) AND the final request strips
   `:tools` outright (D4 amendment b — the ONLY reachable act left is the
   final text; unreachable > forbidden, replacing v0.2.0's teach-and-hope,
   where the model called tools anyway and the reply landed empty)."
  8)

(def ^:dynamic *tool-depth*
  "Re-entrancy guard. The eval tool hands the model eval! itself — an armed
   session eval!-ing an armed session would nest tool loops without bound.
   Bound (inc'd) around each tool loop; conveyed through eval-code's future
   into any nested driver call (bb futures convey bindings — live-verified),
   where default-complete sees it and routes the nested completion PLAIN.
   Depth 1 of self-reference is the feature; depth 2+ is the fork bomb."
  0)

(def ^:private tool-budget-refusal
  (str "TOOL BUDGET EXHAUSTED — this call was refused; no result. "
       "Your next response is your final one: answer in plain text from "
       "what you already have. Do not call any tool."))

;; tools-system (the orientation def) is DEAD (D7 RATIFIED): the template
;; lives in roster builtin-defaults :orientation — bottom of the config
;; chain (session :orientation > model > provider > root), fully replaceable
;; like every other prompt layer. The redef extension point becomes a CONFIG
;; key (option > detection); with-tools-system resolves at call time.

(defn with-tools-system
  "Append the environment orientation to a Request's :system (creating it
   when the session runs bare — a raw-prose probe still deserves to know
   where it lives once tools are armed). The orientation TEMPLATE resolves
   through roster's D7 chain (`resolve-orientation` — session > model >
   provider > root, replaceable wholesale by an embedding host); every
   occurrence of the literal `{slug}` is substituted with slug's `pr-str`
   rendering (`:s` prints as `\":s\"` — matches `registry/event-line`'s slug
   rendering; live A/B 2026-08-27: slug interpolation collapses
   self-location to ONE dispatch — orientation fixes LOCATION, not REACH)
   BEFORE appending. A chain that resolves to NONE (explicit false/blank)
   leaves the request untouched — an intentionally unoriented armed session.

   Applied by `tool-complete` — NEVER `build-request` — so it rides iff the
   tool defs are actually on the wire: a depth-guarded nested completion
   must not claim a tool it doesn't have, and an unarmed arm of an `ab!`
   counterfactual stays clean."
  [request config slug]
  (if-let [template (llm/resolve-orientation config)]
    (let [oriented (str/replace template "{slug}" (pr-str slug))]
      (assoc request :system
             (if-let [s (:system request)]
               (str s "\n\n" oriented)
               oriented)))
    request))

(defn session-tools
  "config :tools → the Tool records this session exposes. true ≡ all
   registered; [kw …] ≡ whitelist (unknown kw throws — caught by the driver
   as error-data, and the message lists what IS registered: λ mirror)."
  [tools-cfg]
  (cond
    (true? tools-cfg)
    (tp/all-tools tools/tool-registry*)

    (sequential? tools-cfg)
    (mapv (fn [kw]
            (or (tp/lookup tools/tool-registry* kw)
                (throw (ex-info (str "unknown tool " kw " — registered: "
                                     (mapv tp/tool-name (tp/all-tools tools/tool-registry*)))
                                {:tool kw}))))
          tools-cfg)

    :else nil))

(defn tool-wire
  "Tool records → {:defs [anthropic-tool-def …] :name->kw {wire-name kw}} —
   the defs ride Request :tools; the index routes a tool_use block's string
   name back to the registry keyword (tp encodes :clojure/eval as
   \"clojure_eval\" on the wire)."
  [ts]
  (reduce (fn [acc t]
            (let [d (tp/tool->anthropic-tool-def t)]
              (-> acc
                  (update :defs conj d)
                  (update :name->kw assoc (:name d) (tp/tool-name t)))))
          {:defs [] :name->kw {}}
          ts))

(defn- receipt-preview
  "First ~24 display chars of a tool input for the ⚡ receipt (the tree-pane
   footer is receipt-width; the code preview IS the trace index)."
  [input]
  (let [s (str/replace (str (or (:code input) (pr-str input))) #"\s+" " ")]
    (if (> (count s) 24) (str (subs s 0 24) "…") s)))

(defn- dispatch-tool!
  "One tool_use block → tp/dispatch (malli-gated, throw-caught upstream) ⊕ a
   ⚡ receipt. Unknown wire names return error-data (the model reads and
   corrects — λ mirror)."
  [slug name->kw {:keys [name input]}]
  (if-let [kw (get name->kw name)]
    (do (registry/event! {:kind :tool :slug slug :msg (str "⚡ " (receipt-preview input))})
        (tp/dispatch tools/tool-registry* kw input))
    {:result (str "no such tool: " name) :is-error true}))

(defn- tool-result-block
  "tool_use block ⊕ dispatch result → the :tool_result content block the
   next user message carries back (escapement's modeled shape — the openai
   translator expands it to a role:tool message on the wire). D4 amendment
   (b): when `remaining` is given, its dispatch count is appended to the
   content — `\"[N dispatches remain]\"` — so the model reads a TRUE budget
   (a model cannot budget inside an invisible one). The budget-refusal call
   site omits it (2-arity) — the refusal text already states the budget is
   exhausted."
  ([use dispatched] (tool-result-block use dispatched nil))
  ([{:keys [id]} {:keys [result is-error]} remaining]
   {:type       :tool_result
    :tool_use_id id
    :content    (if remaining
                  (str result "\n[" remaining " dispatches remain]")
                  result)
    :is-error   (boolean is-error)}))

(defn tool-complete
  "config ⊕ slug → (fn [tape] → reply-text) — plain-complete's tool-bearing
   sibling (see the ns docstring for the loop shape). Branches on tool_use
   block PRESENCE, not :stop-reason (robust to template drift). D4
   amendments (b, c) land in this loop: every dispatched tool_result carries
   the TRUE remaining-dispatch count; the budget-boundary final request
   strips :tools (unreachable > forbidden); a blank final text (either exit)
   is loud, never silent."
  [config slug]
  (fn [tape]
    (binding [*tool-depth* (inc *tool-depth*)]
      (let [{:keys [defs name->kw]} (tool-wire (session-tools (:tools config)))
            backend     (session-backend config slug)
            base        (-> (build-request config slug tape)
                            (with-tools-system config slug)
                            (assoc :tools defs))
            send!       (fn [messages]
                          (p/await! (proto/send-turn backend (assoc base :messages messages))))
            send-final! (fn [messages]
                          (p/await! (proto/send-turn
                                     backend
                                     (-> base (dissoc :tools) (assoc :messages messages)))))]
        (loop [messages (:messages base)
               bounce   0]
          (let [response (send! messages)
                uses     (filterv #(= :tool_use (:type %)) (:content response))]
            (cond
              (empty? uses)
              (loud-final-text response slug)

              (>= bounce tool-bounce-budget)
              (let [refusals (mapv #(tool-result-block % {:result tool-budget-refusal :is-error true})
                                   uses)
                    _        (registry/event! {:kind :tool :slug slug :msg (str "⚡ budget! " bounce "↯")})
                    final    (send-final! (conj messages
                                                {:role :assistant :content (:content response)}
                                                {:role :user :content refusals}))]
                (loud-final-text final slug))

              :else
              (let [remaining (- tool-bounce-budget bounce 1)]
                (recur (conj messages
                             {:role :assistant :content (:content response)}
                             {:role :user :content (mapv #(tool-result-block
                                                           % (dispatch-tool! slug name->kw %) remaining)
                                                         uses)})
                       (inc bounce))))))))))

(defn default-complete
  "config ⊕ slug → (fn [tape] → reply-text): THE injected-IO default — routes
   to `tool-complete` when the session arms :tools (and we are not already
   inside a tool eval — `*tool-depth*` conveys through the eval future, so a
   nested eval!/bounce! from model-written code completes PLAIN: depth 1 of
   self-reference is the feature, unbounded nesting is not), else
   `plain-complete`. Injectable — tests pass their own :complete-fn; an
   embedding host injects its arbitered/wrapped backend here instead
   (library-contract § 3 — the open slot)."
  [config slug]
  (let [plain (plain-complete config slug)]
    (if (:tools config)
      (let [tooled (tool-complete config slug)]
        (fn [tape]
          (if (pos? *tool-depth*)
            (do (registry/event! {:kind :tool :slug slug :msg "⚡ depth-guard→plain"})
                (plain tape))
            (tooled tape))))
      plain)))
