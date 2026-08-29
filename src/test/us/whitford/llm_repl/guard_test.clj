(ns us.whitford.llm-repl.guard-test
  "D8 locks — the mechanism, not the commands (those get the behavioral
   coverage table in llm-repl-test): `errors` polarity ∧ teaching shape,
   `defcommand`'s compile-time gates (schema must compile, defaults must be
   DECLARED, optionals trailing-only, :catn ≡ argv), generated defaulting
   arities (the declared default, never an implicit {}), variadic flat-arg
   validation, expansion ≡ plain defn (macroexpand-1 is the spec)."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [us.whitford.llm-repl.guard :as guard :refer [defcommand]]))

;; ── fixtures — the macro's own output under test ────────────────────────────

(defcommand tcmd!
  "test fixture: one optional param with a NON-{} declared default"
  {:manual   "Test."
   :args     [:catn [:slug :keyword] [:n [:? :int]]]
   :defaults {n 42}}
  [slug n]
  {:slug slug :n n})

(defcommand vcmd!
  "test fixture: variadic — guard sees the flat arg list"
  {:manual "V."
   :args   [:catn [:slug :keyword] [:ks [:+ :keyword]]]}
  [slug & ks]
  {:slug slug :ks (vec ks)})

;; ── errors: the pure mechanism ──────────────────────────────────────────────

(deftest errors-polarity
  (testing "errors-or-nil, NAMED for it (the validate-request polarity trap)"
    (is (nil? (guard/errors #'tcmd! [:s 1])) "valid → nil ≡ proceed")
    (is (some? (guard/errors #'tcmd! ["not-kw" 1])) "invalid → truthy error map"))
  (testing "an ungoverned var (no :manual/args) is nil — defn ⊕ explicit
            guard stays legal without it (open slot)"
    (is (nil? (guard/errors #'errors-polarity [:anything])))))

(deftest errors-teaching-shape
  (let [e (:repl/error (guard/errors #'tcmd! ["not-kw" :not-int]))]
    (is (= 'tcmd! (:command e)) "names the command")
    (is (vector? (:errors e)) "humanized (positional for :catn)")
    (is (= [:catn [:slug :keyword] [:n [:? :int]]] (:args e))
        "the full schema form rides along — param NAMES ∧ shapes beside the
         error (:catn humanize alone is positional)")))

;; ── the generated arities ───────────────────────────────────────────────────

(deftest defaulting-arity-uses-the-declared-default
  (is (= {:slug :s :n 42} (tcmd! :s))
      "the DECLARED default (42), never an implicit {} — D8 amendment 1")
  (is (= {:slug :s :n 7} (tcmd! :s 7)) "full arity unchanged")
  (is (= '([slug] [slug n]) (:arglists (meta #'tcmd!)))
      "both arities visible — surfaces render truth"))

(deftest guard-sits-at-the-full-arity
  (is (:repl/error (tcmd! "not-kw")) "short arity delegates INTO the guard")
  (is (:repl/error (tcmd! :s :not-int)) "full arity guarded directly"))

(deftest variadic-flat-args
  (is (= {:slug :s :ks [:a :b]} (vcmd! :s :a :b)))
  (is (:repl/error (vcmd! :s)) "[:+ …] rejects empty rest")
  (is (:repl/error (vcmd! :s "not-kw")) "rest args validated individually"))

;; ── compile-time gates (a malformed command is UNWRITABLE) ──────────────────

(defn- expand-err
  "macroexpand-1 a defcommand form, returning the ROOT-CAUSE ex-message or
   nil. TWIN TRAP: JVM `macroexpand-1` wraps a macro's throw in
   CompilerException (\"Unexpected error macroexpanding…\") while bb/sci
   rethrows it BARE — matching the top-level message passes one runtime and
   fails the other. The macro also CHAINS causes (its teaching ex-info
   carries malli's original as .getCause), so return the WHOLE chain's
   messages joined — matching root-only would overshoot past the teaching
   text; matching top-only would break on the JVM wrap."
  [form]
  (try (let [_ (macroexpand-1 form)] nil)
       (catch Throwable t
         (->> (iterate #(.getCause ^Throwable %) t)
              (take-while some?)
              (map ex-message)
              (str/join " | ")))))

(deftest compile-time-gates
  (letfn [(gate [substr form]
            (let [msg (expand-err form)]
              (is (and msg (str/includes? (str msg) substr))
                  (str "expected a throw naming " (pr-str substr) ", got: " (pr-str msg)))))]
    (testing "schema-less command"
      (gate "schema-less command is unwritable"
            '(us.whitford.llm-repl.guard/defcommand b! "d" {:manual "B."} [x] x)))
    (testing "docstring required (defn grammar)"
      (gate "docstring is REQUIRED"
            '(us.whitford.llm-repl.guard/defcommand b! {:manual "B." :args [:catn]} [] 1)))
    (testing ":args must COMPILE at expansion (build failure, not first live call)"
      (gate "does not compile as a malli schema"
            '(us.whitford.llm-repl.guard/defcommand b! "d" {:manual "B." :args [:catn [:x :not-a-schema]]} [x] x)))
    (testing "optional param without a :defaults entry"
      (gate "no :defaults entry"
            '(us.whitford.llm-repl.guard/defcommand b! "d" {:manual "B." :args [:catn [:x :int] [:o [:? :map]]]} [x o] x)))
    (testing ":catn required (named params ⇒ self-describing errors)"
      (gate ":args must be a [:catn"
            '(us.whitford.llm-repl.guard/defcommand b! "d" {:manual "B." :args [:cat :int]} [x] x)))
    (testing ":catn arity ≠ argv arity"
      (gate "≠ argv arity"
            '(us.whitford.llm-repl.guard/defcommand b! "d" {:manual "B." :args [:catn [:x :int] [:y :int]]} [x] x)))
    (testing "optionals must be TRAILING"
      (gate "must be TRAILING"
            '(us.whitford.llm-repl.guard/defcommand b! "d" {:manual "B." :args [:catn [:o [:? :map]] [:x :int]] :defaults {o {}}} [o x] x)))
    (testing "variadic ∧ [:? …] don't mix"
      (gate "don't mix"
            '(us.whitford.llm-repl.guard/defcommand b! "d" {:manual "B." :args [:catn [:o [:? :map]]] :defaults {o {}}} [& o] x)))))

;; ── expansion ≡ the hand-written form ───────────────────────────────────────

(deftest expansion-is-plain-defn
  (let [exp (macroexpand-1 '(us.whitford.llm-repl.guard/defcommand x!
                              "d" {:manual "X." :args [:catn [:s :keyword]]}
                              [s] {:ok s}))]
    (is (= 'clojure.core/defn (first exp)) "sugar over defn, nothing else")
    (is (some #{'"d"} (take 3 exp)) "docstring in defn position")))
