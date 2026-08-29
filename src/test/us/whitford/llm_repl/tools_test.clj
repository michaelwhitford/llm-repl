(ns us.whitford.llm-repl.tools-test
  "Regression locks for the self-eval tool executor: per-form `=> v` echo in
   nREPL's frame order (one value PER top-level form, stdout interleaved
   temporally — memories/nrepl-streams-out-and-values-per-form is the
   measurement this shape copies), ns discipline (persists within a call,
   never leaks out), errors/timeouts carrying everything echoed so far, and
   the marked truncation budget. Pure seam: eval-code returns data, never
   throws — no registry, no wire."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [escapement.tools.protocol :as tp]
   [us.whitford.llm-repl.tools :as tools]))

(deftest per-form-echo
  (testing "EVERY top-level form echoes => v, in order — not just the last"
    (is (= {:result "=> 3\n=> 42\n=> :done" :is-error false}
           (tools/eval-code "(+ 1 2) (+ 2 40) :done"))))
  (testing "stdout interleaves temporally with the value lines (nREPL shape)"
    (is (= {:result "=> 3\ntick\n=> nil\n=> :done" :is-error false}
           (tools/eval-code "(+ 1 2) (println \"tick\") :done"))))
  (testing "a single form keeps the familiar one-line shape"
    (is (= {:result "=> 2" :is-error false}
           (tools/eval-code "(+ 1 1)")))))

(deftest ns-discipline
  (testing "(ns foo) persists ACROSS forms within one call"
    (let [{:keys [result is-error]}
          (tools/eval-code "(ns tmp.tools-test-scratch) (def x 9) x")]
      (is (false? is-error))
      (is (str/ends-with? result "=> 9"))))
  (testing "…but never leaks: the NEXT call does not start in that ns"
    (let [{:keys [result]} (tools/eval-code "(str *ns*)")]
      (is (not (str/includes? result "tmp.tools-test-scratch"))))))

(deftest errors-carry-partial-echo
  (testing "reader error mid-stream: prior values are not lost"
    (let [{:keys [result is-error]} (tools/eval-code ":ok )(")]
      (is (true? is-error))
      (is (str/starts-with? result "eval error:"))
      (is (str/includes? result "=> :ok"))))
  (testing "eval error mid-stream: prior values ⊕ the message, as data"
    (let [{:keys [result is-error]}
          (tools/eval-code "(+ 1 2) (throw (ex-info \"boom\" {}))")]
      (is (true? is-error))
      (is (str/includes? result "boom"))
      (is (str/includes? result "=> 3")))))

(deftest timeout-carries-partial-echo
  (testing "timeout is data, with everything echoed before the wall"
    (let [{:keys [result is-error]}
          (tools/eval-code "(println \"pre\") (Thread/sleep 60000)"
                           {:timeout-ms 500})]
      (is (true? is-error))
      (is (str/includes? result "timed out after 500ms"))
      (is (str/includes? result "partial output"))
      (is (str/includes? result "pre"))
      (is (str/includes? result "=> nil")))))

(deftest truncation-is-marked
  (testing "over-budget results clip with an explicit marker, never silently"
    (let [{:keys [result is-error]}
          (tools/eval-code "(apply str (repeat 100 \"x\"))" {:budget 20})]
      (is (false? is-error))
      (is (str/includes? result "…[truncated at 20 chars"))))
  (testing "truncate-result is a no-op under budget"
    (is (= "abc" (tools/truncate-result "abc" 10)))))

(deftest no-forms-guard
  (testing "empty ∨ comment-only input answers as teaching text, not silence"
    (doseq [code ["" "   " ";; only a comment"]]
      (is (= {:result "(no forms read — send Clojure forms in :code)"
              :is-error false}
             (tools/eval-code code))))))

(deftest registry-dispatch-seam
  (testing "the registered :clojure/eval tool rides the same executor"
    (is (= {:result "=> 2" :is-error false}
           (tp/dispatch tools/tool-registry* :clojure/eval {:code "(+ 1 1)"})))))

;; ── register-tool! — the guarded chokepoint (D9 registration-guards) ──────
;; Every throw ≡ teaching ex-message ⊕ {:errors …} ex-data; tests run against
;; FRESH registries so the real tool-registry* is never touched.

(defrecord GuardTestTool [nm schema]
  tp/Tool
  (tool-name [_] nm)
  (description [_] "guard-test")
  (input-schema [_] schema)
  (invoke [_ _] :ok))

(deftest register-tool!-guards
  (testing "not a Tool → throws teaching text (nothing else is even callable)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not satisfy"
                          (tools/register-tool! (tp/new-registry []) 42))))
  (testing "non-keyword tool-name → throws (the registry ∧ config :tools key)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword"
                          (tools/register-tool! (tp/new-registry [])
                                                (->GuardTestTool "strname" [:map])))))
  (testing "invalid malli input-schema → throws at REGISTRATION, not on the
            model's turn (dispatch validates every call against it)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a valid malli schema"
                          (tools/register-tool! (tp/new-registry [])
                                                (->GuardTestTool :t/bad [:mapp [:x :string]]))))
    (try (tools/register-tool! (tp/new-registry [])
                               (->GuardTestTool :t/bad [:mapp [:x :string]]))
         (catch clojure.lang.ExceptionInfo e
           (is (= :t/bad (get-in (ex-data e) [:errors :tool-name]))))))
  (testing "a valid tool registers and is dispatchable (upstream contract:
            returns the tool)"
    (let [reg (tp/new-registry [])
          t   (->GuardTestTool :t/ok [:map {:closed true} [:x :string]])]
      (is (= t (tools/register-tool! reg t)))
      (is (some? (tp/lookup reg :t/ok)))))
  (testing "re-registering an = tool is a NO-OP (reload idempotence)"
    (let [reg (tp/new-registry [])]
      (tools/register-tool! reg (->GuardTestTool :t/same [:map]))
      (is (= (->GuardTestTool :t/same [:map])
             (tools/register-tool! reg (->GuardTestTool :t/same [:map]))))))
  (testing "a DIFFERENT tool under a taken name → collision throw — silent
            replacement of a live tool is the failure the guard exists for"
    (let [reg (tp/new-registry [])]
      (tools/register-tool! reg (->GuardTestTool :t/clash [:map]))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already registered"
                            (tools/register-tool! reg (->GuardTestTool :t/clash [:map [:y :int]]))))
      (try (tools/register-tool! reg (->GuardTestTool :t/clash [:map [:y :int]]))
           (catch clojure.lang.ExceptionInfo e
             (is (true? (get-in (ex-data e) [:errors :collision]))))))))
