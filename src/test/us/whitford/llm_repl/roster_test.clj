(ns us.whitford.llm-repl.roster-test
  "D6's config-chain parse tables ⊕ D7's formal-config locks: EOF-assert
   (the trailing-form silent-drop class — memories/
   config-trailing-form-silent-drop), malli validation (closed ⊕ :ext,
   humanized errors), per-section merge semantics, and the RATIFIED uniform
   prompt-stack chain (session > model > provider > root for ALL THREE
   layers). File io rides temp files; the effective config rides with-redefs
   of roster/config — no test ever reads the machine's real chain."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [us.whitford.llm-repl.roster :as roster]))

(defn- tmp-edn [content]
  (let [f (java.io.File/createTempFile "llm-repl-test" ".edn")]
    (.deleteOnExit f)
    (spit f content)
    f))

;; ── read-edn-file (EOF-assert) ──────────────────────────────────────────────

(deftest read-edn-file-parses-one-map
  (is (= {:a 1} (roster/read-edn-file (tmp-edn "{:a 1}")))))

(deftest read-edn-file-missing-and-empty-are-nil
  (is (nil? (roster/read-edn-file (io/file "/nonexistent/nope.edn"))))
  (is (nil? (roster/read-edn-file (tmp-edn "")))))

(deftest read-edn-file-trailing-forms-fail-loud
  ;; the live 40-minute mystery: a stray } closed the map early and the rest
  ;; of the file silently vanished — now it throws, NAMING the trailing form
  (let [f (tmp-edn "{:a 1} :preamble \"orphaned\"")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"top-level forms"
                          (roster/read-edn-file f)))
    (try (roster/read-edn-file f)
         (catch clojure.lang.ExceptionInfo e
           (is (= [:preamble "orphaned"] (:extra-forms (ex-data e))))
           (is (some? (:file (ex-data e))) "the throw names the file")))))

(deftest read-edn-file-malformed-fails-loud-naming-the-file
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unreadable"
                        (roster/read-edn-file (tmp-edn "{:a")))))

;; ── validate-config (closed ⊕ :ext, humanized) ──────────────────────────────

(deftest validate-config-accepts-the-builtins
  (is (= roster/builtin-defaults (roster/validate-config roster/builtin-defaults))))

(deftest validate-config-closed-catches-typos
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"config invalid"
                        (roster/validate-config {:default-modle :oops}))))

(deftest validate-config-ext-is-the-escape-hatch
  (is (roster/validate-config {:ext {:anima/anything [:goes :here]}})))

(deftest validate-config-humanizes-bad-values
  (try (roster/validate-config {:preamble 42})
       (is false "should have thrown")
       (catch clojure.lang.ExceptionInfo e
         (is (contains? (:errors (ex-data e)) :preamble)
             "errors keyed by config path"))))

(deftest validate-config-prompt-value-shapes
  (testing "every prompt layer takes string | {:file path} | false | nil"
    (is (roster/validate-config {:system-prompt "voice"}))
    (is (roster/validate-config {:orientation {:file "~/prompts/orient.λ"}}))
    (is (roster/validate-config {:preamble false}))
    (is (roster/validate-config {:preamble nil}))))

;; ── load-config (chain merge ⊕ validation at the chokepoint) ────────────────

(deftest load-config-merges-per-section-later-wins
  (let [weak   (tmp-edn "{:models {:a {:model/provider :local}} :default-model :a}")
        strong (tmp-edn "{:models {:b {:model/provider :local}} :default-model :b}")]
    (with-redefs [roster/config-sources (fn [] [weak strong])]
      (let [cfg (roster/load-config)]
        (is (contains? (:models cfg) :a) "map sections merge per key")
        (is (contains? (:models cfg) :b))
        (is (contains? (:models cfg) :qwen36-35b-a3b) "builtins survive underneath")
        (is (= :b (:default-model cfg)) "scalars: later wins")))))

(deftest load-config-validates-the-merged-result
  (let [bad (tmp-edn "{:not-a-real-key 1}")]
    (with-redefs [roster/config-sources (fn [] [bad])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"config invalid"
                            (roster/load-config))))))

;; ── the RATIFIED prompt-stack chain ─────────────────────────────────────────

(def chain-cfg
  {:models    {:m {:model/provider      :p
                   :model/system-prompt "model-sys"}}
   :providers {:p {:provider/orientation "prov-orient"}}
   :preamble      "root-pre"
   :system-prompt "root-sys"
   :orientation   "root-orient"})

(deftest prompt-chain-resolution
  (with-redefs [roster/config (fn [] chain-cfg)]
    (testing "session layer wins"
      (is (= "sess-sys" (roster/resolve-system-prompt {:model :m :system "sess-sys"})))
      (is (= "sess-or" (roster/resolve-orientation {:model :m :orientation "sess-or"})))
      (is (= "sess-pre" (roster/resolve-preamble {:model :m :preamble "sess-pre"}))))
    (testing "model layer"
      (is (= "model-sys" (roster/resolve-system-prompt {:model :m}))))
    (testing "provider layer"
      (is (= "prov-orient" (roster/resolve-orientation {:model :m}))))
    (testing "root layer"
      (is (= "root-pre" (roster/resolve-preamble {:model :m})))
      (is (= "root-sys" (roster/resolve-system-prompt {:model :unknown})))
      (is (= "root-orient" (roster/resolve-orientation {:model :unknown}))))))

(deftest prompt-chain-explicit-none-stops
  (with-redefs [roster/config (fn [] chain-cfg)]
    (is (nil? (roster/resolve-system-prompt {:model :m :system false})))
    (is (nil? (roster/resolve-system-prompt {:model :m :system nil}))
        "present-nil ≡ explicitly none — uniform with the preamble semantics")
    (is (nil? (roster/resolve-orientation {:model :m :orientation ""})))))

(deftest prompt-chain-file-values-render
  ;; the anima shape: a whole layer replaced by a nucleus prompt FILE
  (let [f (tmp-edn "λ prompt(x). lambda-notation prompt text\n")]
    (with-redefs [roster/config (fn [] {:system-prompt {:file (.getPath f)}})]
      (is (= "λ prompt(x). lambda-notation prompt text"
             (roster/resolve-system-prompt {:model :m}))
          "slurped ∧ trailing-whitespace trimmed"))))
