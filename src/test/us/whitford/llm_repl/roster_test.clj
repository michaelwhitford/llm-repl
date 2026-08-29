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
   [clojure.java.shell :as shell]
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

;; ── D10: the source is data, init! is the read, require is inert ────────────
;; (memories/ambient-config-leaks-into-embedding-hosts — anima, the first
;; consumer, inherited the operator's :tools true at require)

(defmacro ^:private with-config-state
  "Save ∧ restore config* around a body that init!s — no D10 test may leak
   its source into the rest of the suite."
  [& body]
  `(let [saved# @roster/config*]
     (try ~@body (finally (reset! roster/config* saved#)))))

(deftest init-map-folds-over-builtins
  (with-config-state
    (roster/init! {:map {:tools true :default-model :gemma-4-31b-it}})
    (let [cfg (roster/config)]
      (is (true? (:tools cfg)))
      (is (= :gemma-4-31b-it (:default-model cfg)) "scalars replace")
      (is (contains? (:models cfg) :qwen36-35b-a3b)
          "builtins survive underneath — every source FOLDS, never replaces"))))

(deftest init-files-reads-the-given-chain
  (let [f (tmp-edn "{:default-model :from-file}")]
    (with-config-state
      (roster/init! {:files [f]})
      (is (= :from-file (:default-model (roster/config)))))))

(deftest init-fn-source-is-live-at-reload
  (with-config-state
    (let [n (atom 0)]
      (roster/init! {:fn (fn [] {:default-model (if (= 1 (swap! n inc))
                                                  :first-read :second-read)})})
      (is (= :first-read (:default-model (roster/config))))
      (roster/reload-config!)
      (is (= :second-read (:default-model (roster/config)))
          "{:fn} re-invokes at reload — a host's live source stays live"))))

(deftest init-throws-loud-and-installs-nothing
  (with-config-state
    (roster/init! {:map {:tools true}})
    (let [before @roster/config*]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"config invalid"
                            (roster/init! {:map {:not-a-real-key 1}}))
          "invalid merge → the ONE validate-config throws, humanized")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown config source shape"
                            (roster/init! {:oops 1}))
          "unknown shape teaches the four")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-map"
                            (roster/init! {:fn (fn [] 42)}))
          "a source producing valid-EDN-but-not-a-map is named, not merged")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unreadable"
                            (roster/init! {:files [(tmp-edn "{:a")]}))
          "an unreadable file throws at init!, on the caller's stack")
      (is (= before @roster/config*) "a failed init! installs NOTHING"))))

(deftest init-replaces-atomically-and-reports
  (with-config-state
    (roster/init! {:builtin true})
    (let [r (roster/init! {:map {:tools true}})]
      (is (= {:map {:tools true}} (:source r)))
      (is (= {:builtin true} (:replaced r))
          "re-init at any time REPLACES (ratified: no read-tracking) and
           reports the source it displaced"))))

(deftest reload-refolds-from-current-source-never-the-chain
  ;; THE leak-hole lock: after a host's init! {:map …}, reload must not
  ;; resurrect the operator's file chain the host never asked for
  (let [operator-file (tmp-edn "{:tools true}")]
    (with-config-state
      (with-redefs [roster/config-sources (fn [] [operator-file])]
        (roster/init! {:map {:default-model :host-model}})
        (roster/reload-config!)
        (is (nil? (:tools (roster/config)))
            "the operator's file stayed unread — reload rides the SOURCE")
        (is (= :host-model (:default-model (roster/config))))))))

(deftest reload-files-rereads-disk
  (let [f (tmp-edn "{:default-model :v1}")]
    (with-config-state
      (roster/init! {:files [f]})
      (is (= :v1 (:default-model (roster/config))))
      (spit f "{:default-model :v2}")
      (roster/reload-config!)
      (is (= :v2 (:default-model (roster/config)))
          "{:files} re-reads the captured paths — the operator seam survives"))))

(deftest require-is-inert-the-anima-regression
  ;; the ticket's raison d'être, probed in a FRESH process: requiring roster
  ;; with a poison LLM_REPL_CONFIG in the env must neither throw nor read it
  ;; — builtins govern until init!. Guarded: skips when bb isn't on PATH.
  (let [poison (tmp-edn "{:tools true :garbage")   ; malformed ON PURPOSE
        probe  (str "(require '[us.whitford.llm-repl.roster :as r]) "
                    "(print (pr-str [(= (r/config) r/builtin-defaults) "
                    "(:source @r/config*)]))")
        res    (try (shell/sh "bb" "-e" probe
                              :env (assoc (into {} (System/getenv))
                                          "LLM_REPL_CONFIG" (.getPath poison)))
                    (catch java.io.IOException _ nil))]
    (if res
      (do (is (zero? (:exit res))
              (str "require did ambient IO and blew up on the poison: " (:err res)))
          (is (= "[true {:builtin true}]" (:out res))
              "fresh process: builtins govern, source untouched, nothing read"))
      (is true "bb not on PATH — subprocess probe skipped"))))
