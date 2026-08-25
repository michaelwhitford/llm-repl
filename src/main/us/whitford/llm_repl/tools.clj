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
   - `load-string` in the host runtime: full power, NO sandbox. The model
     gets exactly what any attached nREPL client gets — inspect its own
     tape, fork itself, bounce probes off its own history. Trust model ≡
     equal client; observability (receipts), not restriction, is the guard.
   - `*out*` captured per call (a println here must never reach a surface
     raw — same rule as the TUI's alt screen; output travels IN the result).
   - future ⊕ timed deref ⊕ best-effort future-cancel: a runaway eval
     times out as data (the thread may linger — bb has no hard kill; the
     timeout bounds the MODEL's wait, not the host's CPU).
   - result `pr-str`ed and TRUNCATED to a char budget: tool results feed
     straight back into the context window (λ context: sip, don't gulp).
   - errors as data {:result … :is-error true} — the model reads the
     message and corrects (λ mirror), it never sees a throw.

   The description follows anima's tools.clj idiom: state WHERE the model
   is and its BOOTSTRAP MOVE — never enumerate the surface (the manual seam
   `(help)`/`(manual)` is the source of truth; enumeration would drift).

   NO require of core — core requires US (registry + loop); tools stays
   core-free the same way tui does (the wire layer composes them)."
  (:require
   [escapement.tools.protocol :as tp]))

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
   Captured *out* precedes the value (\"out…\\n=> value\"); errors carry any
   partial output plus the message. Pure-ish: the WORLD may change (that is
   the point); the return shape never throws."
  ([code] (eval-code code {}))
  ([code {:keys [timeout-ms budget]
          :or   {timeout-ms default-timeout-ms
                 budget     default-result-budget}}]
   (let [sw  (java.io.StringWriter.)
         fut (future
               (try
                 {:value (pr-str (binding [*out* sw] (load-string code)))}
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

       :else
       {:result   (truncate-result
                   (str (when (seq out) out) "=> " (:value res))
                   budget)
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
         "string of Clojure forms; the printed value of the last form returns, "
         "with any stdout ahead of it. Errors return as text — read and "
         "correct. First move for session commands: "
         "(require '[us.whitford.llm-repl.core :as repl]) then (repl/help) — "
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
   `escapement.tools.protocol/register!` (anima would register its granted
   app-query here). Sessions pick FROM it by keyword (config :tools) — what
   is not registered is unreachable, not forbidden. defonce so a reload
   keeps host-registered tools."}
  tool-registry*
  (tp/new-registry [(clojure-eval-tool)]))
