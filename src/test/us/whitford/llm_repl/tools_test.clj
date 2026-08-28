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
