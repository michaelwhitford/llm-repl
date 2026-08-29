(ns hooks.defcommand
  "clj-kondo macroexpand hook for us.whitford.llm-repl.guard/defcommand.

   D8 AMENDED in-build: the ratified `:lint-as clojure.core/defn` is NOT
   enough — lint-as shows kondo ONE arity (the full argv), so every
   defaulting-arity call site (`(eval! :s \"hi\")`) flags :invalid-arity.
   This hook mirrors the real macro's arity generation (structurally, no
   malli — kondo's sci can't load it), so kondo sees the true multi-arity
   defn. Keep the arity logic in sync with guard/defcommand.")

(defmacro defcommand
  [name docstring attr-map argv & body]
  (let [args-form (:args attr-map)
        entries   (vec (rest args-form))
        optional? (fn [e] (let [s (last e)] (and (vector? s) (= :? (first s)))))
        opt-count (count (filter optional? entries))
        variadic? (boolean (some #{'&} argv))
        defaults  (:defaults attr-map {})
        gen       (fn [j]
                    (let [kept    (vec (drop-last j argv))
                          dropped (take-last j argv)]
                      `(~kept (~name ~@kept ~@(map #(get defaults % nil) dropped)))))
        ;; the full arity mirrors the REAL (or (errors …) (do body)) wrap so
        ;; kondo's type inference knows a command can return the guard's
        ;; error map (else `(:repl/error (cmd …))` lints "always false")
        full      (if variadic?
                    (let [fixed (vec (take-while #(not= '& %) argv))
                          rst   (last argv)]
                      `(~argv (or (us.whitford.llm-repl.guard/errors (var ~name) (into ~fixed ~rst))
                                  (do ~@body))))
                    `(~argv (or (us.whitford.llm-repl.guard/errors (var ~name) ~argv)
                                (do ~@body))))]
    ;; the :args form rides the expansion as a plain expression so kondo
    ;; SEES the schema-var usages (Slug, EvalOpts, …) — without it every
    ;; schema referenced only in an attr-map lints as unused-private-var
    `(do ~args-form
         (defn ~name ~docstring
           ~@(concat (when-not variadic? (map gen (range opt-count 0 -1)))
                     [full])))))
