(ns us.whitford.llm-repl.guard
  "D8 — the command-boundary guard. A PURE FN is the mechanism (`errors`),
   the `defcommand` macro is sugar in defn's exact grammar; `defn` ⊕ an
   explicit guard line is the same seam and stays legal (open slot — a host
   may use either form).

   This is a RUNTIME boundary teaching the MODEL (λ mirror), not a dev-time
   contract: always on, errors as DATA (`{:repl/error {:command … :errors …}}`
   — tp/dispatch's corrective-data shape, live-proven on this model class),
   never a throw (λ api). NOT guardrails' job — >defn throws/logs per config,
   compiles away in production, and structurally cannot change a return
   value (memories/guardrails-is-not-a-boundary-guard).

   Macro discipline (the Kay lineage — defsc/defmutation/guardrails):
   1. expansion ≡ exactly the code you'd hand-write (`macroexpand-1` is the
      spec) — inspectable, greppable;
   2. the declarative part is DATA validated at COMPILE time — `:args` must
      compile as a malli schema at expansion, `:defaults` must cover every
      `[:? …]` param — a malformed command is UNWRITABLE, not merely wrong;
   3. plain machinery underneath — `errors` is independently tested and
      callable."
  (:require
   [malli.core :as m]
   [malli.error :as me]))

(defn errors
  "THE boundary check: validate `args` — the FLAT argument vector of a
   command call — against the `:manual/args` (`:catn`) schema on var `v`.
   ERRORS-OR-NIL polarity, named for it (memories/thinking-false-polarity):
   nil ≡ valid, proceed; invalid → `{:repl/error {:command sym :errors
   humanized}}` for the caller to RETURN (data that teaches — the model
   reads the named params and corrects). A var without `:manual/args` is
   ungoverned ⇒ nil (defn ⊕ explicit guard call remains legal without it)."
  [v args]
  (when-let [schema (:manual/args (meta v))]
    (when-let [explanation (m/explain schema (vec args))]
      {:repl/error {:command (:name (meta v))
                    :errors  (me/humanize explanation)
                    ;; :catn humanize is POSITIONAL — ride the full schema
                    ;; form so the reader sees param NAMES ∧ shapes beside
                    ;; the error (λ mirror: maximum teaching, zero prose)
                    :args    (m/form (m/schema schema))}})))

(defn- optional-entry?
  "A `:catn` child whose schema form is `[:? …]` — an optional TAIL param
   (the defaulting-arity generator keys off these)."
  [entry]
  (let [s (last entry)]
    (and (vector? s) (= :? (first s)))))

(defmacro defcommand
  "A `^:manual` command in defn's EXACT grammar — name, docstring, attr-map,
   argv, body — so readers already know the shape:

     (defcommand eval!
       \"Maintainer/agent-dense docstring…\"
       {:manual   \"Curated human summary for (help).\"
        :args     [:catn [:slug :keyword] [:text :string] [:opts [:? Opts]]]
        :defaults {opts {}}}
       [slug text opts]
       …body…)

   Expansion ≡ the hand-written form: a plain multi-arity `defn` carrying
   `:manual` ∧ `:manual/args` metadata; the FULL arity wraps the body as
   `(or (guard/errors #'name [args…]) (do …body…))`; each `[:? …]` tail
   param generates a shorter arity delegating with its DECLARED default
   (`:defaults` — REQUIRED per optional param, no implicit `{}`; D8
   amendment). Variadic argv (`[slug & ks]`) is legal — the guard sees the
   flat arg list; no arities are generated (use `[:* …]`/`[:+ …]` in the
   schema tail). Docstring, `:manual`, and `:args` are REQUIRED — a
   schema-less command is UNWRITABLE (unreachable > forbidden). `:args`
   must compile via `m/schema` AT EXPANSION — a malformed schema fails the
   build, not the first live call."
  [name docstring attr-map argv & body]
  (when-not (string? docstring)
    (throw (ex-info (str "defcommand " name ": docstring is REQUIRED (defn grammar, 2nd position)")
                    {:command name})))
  (when-not (and (map? attr-map) (:manual attr-map) (:args attr-map))
    (throw (ex-info (str "defcommand " name ": attr-map with :manual and :args is REQUIRED "
                         "— a schema-less command is unwritable (D8)")
                    {:command name :attr-map attr-map})))
  (let [args-form (:args attr-map)
        defaults  (:defaults attr-map {})
        variadic? (boolean (some #{'&} argv))
        entries   (vec (rest args-form))]
    ;; compile-time: the schema must compile (referenced vars exist at load)
    (try (m/schema (eval args-form))
         (catch Exception e
           (throw (ex-info (str "defcommand " name ": :args does not compile as a malli schema — "
                                (ex-message e))
                           {:command name :args args-form} e))))
    (when-not (= :catn (first args-form))
      (throw (ex-info (str "defcommand " name ": :args must be a [:catn …] (named params ⇒ "
                           "self-describing errors)")
                      {:command name :args args-form})))
    (let [optionals (filterv optional-entry? entries)
          opt-count (count optionals)
          fixed     (vec (take-while #(not= '& %) argv))
          rest-sym  (when variadic? (last argv))]
      (when (and variadic? (pos? opt-count))
        (throw (ex-info (str "defcommand " name ": [:? …] optionals and a variadic argv don't mix "
                             "— pick one (schema regex covers the variadic tail)")
                        {:command name})))
      (when-not (or variadic? (= (count entries) (count argv)))
        (throw (ex-info (str "defcommand " name ": :catn arity (" (count entries)
                             ") ≠ argv arity (" (count argv) ")")
                        {:command name :args args-form :argv argv})))
      (when (not= optionals (vec (take-last opt-count entries)))
        (throw (ex-info (str "defcommand " name ": [:? …] params must be TRAILING "
                             "(a defaulting arity can only drop from the end)")
                        {:command name :args args-form})))
      (doseq [p (take-last opt-count argv)]
        (when-not (contains? defaults p)
          (throw (ex-info (str "defcommand " name ": optional param " p " has no :defaults entry "
                              "— defaults are DECLARED, never implicit (D8 amendment)")
                          {:command name :defaults defaults}))))
      (let [meta-map   (-> attr-map
                           (dissoc :args :defaults)
                           (assoc :manual/args args-form))
            flat-args  (if variadic?
                         `(into [~@fixed] ~rest-sym)
                         argv)
            full-arity `(~argv
                         (or (errors (var ~name) ~flat-args)
                             (do ~@body)))
            gen-arity  (fn [j]
                         (let [kept    (vec (drop-last j argv))
                               dropped (take-last j argv)]
                           `(~kept (~name ~@kept ~@(map defaults dropped)))))
            arities    (concat (map gen-arity (range opt-count 0 -1))
                               [full-arity])]
        `(defn ~name ~docstring ~meta-map ~@arities)))))
