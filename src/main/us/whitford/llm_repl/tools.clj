(ns us.whitford.llm-repl.tools
  "The self-eval tool (λ tool): the model driven BY this repl becomes a client
   OF it. One tool, `:clojure/eval` — evaluate Clojure IN THE HOSTING PROCESS,
   which closes the equal-clients thesis: humans (TUI), editors (nREPL), and
   now the model itself all drive the same runtime.

   Rides escapement's PUBLIC tools layer (`escapement.tools.protocol`): Tool
   protocol + registry + `dispatch` (malli-gated, throw-caught, error-as-data)
   + `tool->anthropic-tool-def` (malli → JSON schema on the wire). We write
   ONLY the executor; validation/lookup/def-shaping is upstream contract.

   EXECUTOR SEMANTICS (all bb-runtime-verified before writing — λ assert):
   - per-form `read` ⊕ `eval` in the host runtime: full power, NO sandbox.
     The model gets exactly what any attached nREPL client gets — inspect
     its own tape, fork itself, bounce probes off its own history. Trust
     model ≡ equal client; observability (receipts), not restriction, is
     the guard.
   - `*out*` captured per call (a println here must never reach a surface
     raw — same rule as the TUI's alt screen; output travels IN the result).
   - future ⊕ timed deref ⊕ best-effort future-cancel: a runaway eval
     times out as data (the thread may linger — bb has no hard kill; the
     timeout bounds the MODEL's wait, not the host's CPU).
   - each value `pr-str`ed, the whole echo TRUNCATED to a char budget:
     tool results feed straight back into the context window (λ context:
     sip, don't gulp).
   - errors as data {:result … :is-error true} — the model reads the
     message and corrects (λ mirror), it never sees a throw.

   The description follows anima's tools.clj idiom: state WHERE the model
   is and its BOOTSTRAP MOVE — never enumerate the surface (the manual seam
   `(help)`/`(manual)` is the source of truth; enumeration would drift).

   NO require of core — core requires US (registry + loop); tools stays
   core-free the same way tui does (the wire layer composes them)."
  (:require
   [clojure.string :as str]
   [escapement.tools.protocol :as tp]
   [malli.core :as m]))

(def default-timeout-ms
  "How long one eval may run before the tool answers :timeout as data.
   Generous — the model may legitimately drive a completion via eval!
   (a nested completion takes model-seconds, not milliseconds)."
  120000)

(def default-result-budget
  "Max chars of a tool result fed back into the context window (value ⊕
   captured output combined). Truncation is marked, never silent."
  4000)

(defn truncate-result
  "Clip `s` to `n` chars with an explicit marker — the model should KNOW it
   is looking at a prefix (an unmarked clip reads as a complete value and
   teaches wrong facts)."
  [s n]
  (if (> (count s) n)
    (str (subs s 0 n) "\n…[truncated at " n " chars — print less, or bind smaller]")
    s))

(defn eval-code
  "Evaluate `code` (a string of Clojure forms) in the host runtime.
   Returns {:result <string> :is-error <bool>} — the tool_result contract.
   EVERY top-level form echoes its value as a `=> v` line, interleaved with
   captured *out* in temporal order — nREPL's exact shape (one `value` frame
   PER form, `out` frames as they happen; see
   memories/nrepl-streams-out-and-values-per-form). Copied, not invented:
   before this, only the LAST form's value returned and the model burned
   rounds re-asking for values it had already computed non-finally.
   `(ns foo)` persists ACROSS forms within one call but never leaks out
   (binding [*ns* *ns*] ≡ load-string's discipline). Errors and timeouts
   carry everything echoed so far — partial values included. Pure-ish: the
   WORLD may change (that is the point); the return shape never throws."
  ([code] (eval-code code {}))
  ([code {:keys [timeout-ms budget]
          :or   {timeout-ms default-timeout-ms
                 budget     default-result-budget}}]
   (let [sw  (java.io.StringWriter.)
         fut (future
               (try
                 (with-open [rdr (java.io.PushbackReader.
                                  (java.io.StringReader. code))]
                   (binding [*out* sw, *ns* *ns*]
                     (loop [n 0]
                       (let [form (read {:eof ::eof :read-cond :allow} rdr)]
                         (if (= ::eof form)
                           {:forms n}
                           (let [v (eval form)]
                             (.write sw (str "=> " (pr-str v) "\n"))
                             (recur (inc n))))))))
                 (catch Throwable t
                   {:error (or (ex-message t) (str (class t)))})))
         res (deref fut timeout-ms ::timeout)
         out (str sw)]
     (cond
       (= ::timeout res)
       (do (future-cancel fut)
           {:result   (str "eval timed out after " timeout-ms "ms"
                           (when (seq out) (str "\npartial output:\n" out)))
            :is-error true})

       (:error res)
       {:result   (truncate-result
                   (str "eval error: " (:error res)
                        (when (seq out) (str "\noutput before error:\n" out)))
                   budget)
        :is-error true}

       (zero? (:forms res))
       {:result   "(no forms read — send Clojure forms in :code)"
        :is-error false}

       :else
       {:result   (truncate-result (str/trimr out) budget)
        :is-error false}))))

(defrecord ClojureEvalTool [opts]
  tp/Tool
  (tool-name [_] :clojure/eval)
  (description [_]
    ;; WHERE the model is + the bootstrap move (anima idiom: point at the
    ;; self-describing surface, never enumerate it — the manual cannot drift
    ;; from what (help) compiles).
    (str "Evaluate Clojure code in the live REPL process that HOSTS this "
         "conversation (you are a client of your own repl). Input: code — a "
         "string of Clojure forms; EVERY form's value returns as a `=> value` "
         "line, interleaved with any stdout, in order. Errors return as text "
         "— read and correct. First move for session commands: "
         "(require '[us.whitford.llm-repl :as repl]) then (repl/help) — "
         "fork!, bounce!, sessions-list and the rest are documented there."))
  (input-schema [_] [:map {:closed true} [:code :string]])
  (invoke [_ {:keys [code]}]
    (eval-code code opts)))

(defn clojure-eval-tool
  "Construct the self-eval tool. opts: :timeout-ms :budget (see defaults)."
  ([] (clojure-eval-tool {}))
  ([opts] (->ClojureEvalTool opts)))

(defonce ^{:doc "The tool registry — an OPEN SLOT (λ extend), twin of core's
   `manual-namespaces*`: seeded with :clojure/eval; a host registers more via
   `register-tool!` below (the GUARDED chokepoint — anima would register its
   granted app-query there). Sessions pick FROM it by keyword (config :tools)
   — what is not registered is unreachable, not forbidden. defonce so a
   reload keeps host-registered tools."}
  tool-registry*
  (tp/new-registry [(clojure-eval-tool)]))

(defn register-tool!
  "THE guarded registration chokepoint for the tool wire (D9,
   registration-guards — architecture.md § D9): validates BEFORE a tool can
   land on the model's wire, because this call's return is read by NOBODY —
   errors-as-data here would be a silent fallback that breaks the tool wire
   far from the cause. Throws are safe for every audience: a self-registering
   model reads the teaching ex-MESSAGE as its :is-error tool result
   (tools dispatch converts, test-locked upstream); a host boots loudly; an
   editor prints it (that's what a repl is).

   Guards, in order:
   - `tool` satisfies `tp/Tool` (else nothing below is even callable)
   - `(tp/tool-name tool)` is a keyword (the registry ∧ config :tools key)
   - `(tp/input-schema tool)` compiles as a malli schema (else dispatch's
     validation gate would blow up at CALL time, on the model's turn)
   - no COLLISION: a name already registered throws — silently REPLACING
     a live tool (upstream `tp/register!`'s behavior) is the failure this
     guard exists for. Re-registering an `=` tool is a no-op (reload
     idempotence); replacing on purpose is `tp/register!` directly — the
     labeled escape hatch.

   ex-message ≡ teaching text; ex-data ≡ {:errors …}. Returns the tool
   (upstream's contract). 1-arity registers into `tool-registry*`."
  ([tool] (register-tool! tool-registry* tool))
  ([registry tool]
   (letfn [(fail! [msg errors]
             (throw (ex-info (str "llm-repl: cannot register tool — " msg)
                             {:errors errors})))]
     (when-not (satisfies? tp/Tool tool)
       (fail! (str (pr-str tool) " does not satisfy escapement.tools.protocol/Tool "
                   "(implement tool-name, description, input-schema, invoke)")
              {:not-a-tool (pr-str tool)}))
     (let [nm (tp/tool-name tool)]
       (when-not (keyword? nm)
         (fail! (str "tool-name must be a keyword (the registry key ∧ what a "
                     "session's config :tools selects by), got " (pr-str nm))
                {:tool-name nm}))
       (try (m/schema (tp/input-schema tool))
            (catch Exception e
              (fail! (str "input-schema of " nm " is not a valid malli schema ("
                          (ex-message e) ") — dispatch validates every call "
                          "against it, so it must compile at registration, "
                          "not blow up on the model's turn")
                     {:tool-name nm :schema-error (ex-message e)})))
       (let [existing (tp/lookup registry nm)]
         (cond
           (nil? existing)     (tp/register! registry tool)
           (= existing tool)   tool ; reload idempotence — same tool, no-op
           :else
           (fail! (str nm " is already registered with a DIFFERENT tool — "
                       "silent replacement is the failure this guard exists "
                       "for; pick another name, or replace deliberately via "
                       "escapement.tools.protocol/register!")
                  {:tool-name nm :collision true})))))))
