(ns us.whitford.llm-repl.daemon-test
  "D6's daemon parse tables: attach-target spec parsing and the spawn-cmd
   JVM guard (fail loud with instructions, never the v0.2.0 NPE — the
   babashka.config property only exists under bb)."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [us.whitford.llm-repl.daemon :as daemon]))

(deftest attach-target-parse-table
  (testing "host:port"
    (is (= ["box" 7899] (daemon/attach-target "box:7899"))))
  (testing "bare port ≡ loopback"
    (is (= ["127.0.0.1" 7899] (daemon/attach-target "7899")))))

(deftest spawn-cmd-jvm-guard-fails-loud-with-instructions
  ;; JVM runtime: no babashka.config property → instructions, not an NPE
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bb start"
                        (daemon/spawn-cmd nil "/tmp/proj")))
  (try (daemon/spawn-cmd nil "/tmp/proj")
       (catch clojure.lang.ExceptionInfo e
         (is (= :jvm (:runtime (ex-data e))))
         (is (= ["bb start" "bb nrepl"] (:fix (ex-data e)))))))

(deftest spawn-cmd-shape
  (let [cmd (daemon/spawn-cmd "/proj/bb.edn" "/tmp/proj")]
    (is (str/includes? cmd "nohup bb --config /proj/bb.edn"))
    (is (str/includes? cmd "--deps-root /proj") "deps-root ≡ parent of bb.edn")
    (is (str/includes? cmd " nrepl >") "reinvokes the SAME bb.edn's nrepl task")
    (is (str/ends-with? cmd "& echo $!") "detach ⊕ pid capture")))
