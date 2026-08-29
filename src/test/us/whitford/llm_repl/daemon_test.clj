(ns us.whitford.llm-repl.daemon-test
  "D6's daemon parse tables: attach-target spec parsing and the spawn-cmd
   JVM guard (fail loud with instructions, never the v0.2.0 NPE — the
   babashka.config property only exists under bb). Plus the state-file
   hygiene locks (audit §3, strictness arc): atomic write, corrupt ≢ absent,
   cleanup failures surfaced, ONE .nrepl-port parse path."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [us.whitford.llm-repl.daemon :as daemon]))

(defn- tmp-project
  "A fresh throwaway project dir (with its .llm-repl state dir) under the
   system tmpdir — daemon state fns are all keyed by pdir, so tests never
   touch the real project's state."
  ^String []
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "llm-repl-daemon-test-" (System/nanoTime)))]
    (.mkdirs (io/file d ".llm-repl"))
    (.getAbsolutePath d)))

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

;; ── state-file hygiene (audit §3, strictness arc) ─────────────────────────

(deftest write-state!-read-state-roundtrip
  (testing "atomic write (temp+rename) round-trips and leaves NO temp sibling"
    (let [pdir (tmp-project)
          st   {:pid 1 :port 7899 :cwd pdir :started-at 0}]
      (daemon/write-state! pdir st)
      (is (= st (daemon/read-state pdir)))
      (is (not-any? #(str/includes? % "tmp")
                    (seq (.list (io/file pdir ".llm-repl"))))
          "the temp sibling was renamed into place, not left behind"))))

(deftest read-state-corrupt-is-never-silently-absent
  (testing "a torn daemon.edn → nil ⊕ evidence moved aside VERBATIM ⊕ one
            loud stderr line naming both paths — never corrupt ≡ absent
            silently (the silent version spawned a SECOND daemon racing the
            first)"
    (let [pdir  (tmp-project)
          torn  "{:pid 123 :port"
          _     (spit (io/file pdir ".llm-repl" "daemon.edn") torn)
          err   (java.io.StringWriter.)
          state (binding [*err* err] (daemon/read-state pdir))
          aside (io/file pdir ".llm-repl" "daemon.edn.corrupt")]
      (is (nil? state) "treated absent — next spawn gets a clean slate")
      (is (.exists aside) "evidence preserved")
      (is (= torn (slurp aside)) "preserved VERBATIM")
      (is (not (.exists (io/file pdir ".llm-repl" "daemon.edn")))
          "the corrupt original is gone (moved, not copied)")
      (is (str/includes? (str err) "corrupt daemon state") "loud")
      (is (str/includes? (str err) (.getPath aside)) "the line names the evidence path")))
  (testing "parseable-but-not-a-map ≡ corrupt too (edn/read-string on a torn
            write can still 'succeed')"
    (let [pdir (tmp-project)]
      (spit (io/file pdir ".llm-repl" "daemon.edn") "42")
      (let [err (java.io.StringWriter.)]
        (is (nil? (binding [*err* err] (daemon/read-state pdir))))
        (is (str/includes? (str err) "corrupt")))))
  (testing "absent stays plain nil — no message, no aside"
    (let [pdir (tmp-project)
          err  (java.io.StringWriter.)]
      (is (nil? (binding [*err* err] (daemon/read-state pdir))))
      (is (str/blank? (str err))))))

(deftest clean-state!-reports-not-drops
  (testing "happy path: both files deleted, nil returned"
    (let [pdir (tmp-project)]
      (daemon/write-state! pdir {:pid 1})
      (spit (io/file pdir ".nrepl-port") "7899")
      (is (nil? (daemon/clean-state! pdir)))
      (is (not (.exists (io/file pdir ".llm-repl" "daemon.edn"))))
      (is (not (.exists (io/file pdir ".nrepl-port"))))))
  (testing "a failed delete is RETURNED ({:failed [path]}) ⊕ one loud stderr
            line — the old code dropped .delete's boolean (audit §3)"
    (let [pdir (tmp-project)
          sdir (io/file pdir ".llm-repl")]
      (daemon/write-state! pdir {:pid 1})
      ;; a read-only parent forbids deleting its children; guard on
      ;; setWritable's own boolean in case the FS doesn't support it
      (when (.setWritable sdir false)
        (try
          (let [err (java.io.StringWriter.)
                ret (binding [*err* err] (daemon/clean-state! pdir))]
            (is (= [(.getPath (io/file sdir "daemon.edn"))] (:failed ret)))
            (is (str/includes? (str err) "failed to delete") "loud, not dropped"))
          (finally (.setWritable sdir true)))))))

(deftest read-port-file-is-the-one-parse-path
  (testing "valid content (with trailing newline, as nREPL writes it)"
    (let [pdir (tmp-project)]
      (spit (io/file pdir ".nrepl-port") "7899\n")
      (is (= 7899 (daemon/read-port-file pdir)))))
  (testing "absent ∨ blank → nil (no port dropped yet — spawn!'s loop keeps
            waiting, attach falls through)"
    (let [pdir (tmp-project)]
      (is (nil? (daemon/read-port-file pdir)))
      (spit (io/file pdir ".nrepl-port") "  \n")
      (is (nil? (daemon/read-port-file pdir)))))
  (testing "garbage → loud throw carrying file ∧ content as evidence (a
            .nrepl-port holding non-digits is a broken environment, not a
            missing daemon)"
    (let [pdir (tmp-project)]
      (spit (io/file pdir ".nrepl-port") "sevens")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unparseable \.nrepl-port"
                            (daemon/read-port-file pdir)))
      (try (daemon/read-port-file pdir)
           (catch clojure.lang.ExceptionInfo e
             (is (= "sevens" (:content (ex-data e)))))))))
