(ns us.whitford.llm-repl.test-runner
  "The twin-runtime test entry: `bb test` and `clojure -M:run-tests` both run
   THIS -main — one suite, two runtimes (design D6)."
  (:require [clojure.test :as t]
            [us.whitford.llm-repl.core-test]
            [us.whitford.llm-repl.registry-test]
            [us.whitford.llm-repl.tape-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'us.whitford.llm-repl.tape-test
                                           'us.whitford.llm-repl.registry-test
                                           'us.whitford.llm-repl.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
