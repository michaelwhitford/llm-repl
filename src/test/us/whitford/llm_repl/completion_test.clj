(ns us.whitford.llm-repl.completion-test
  "Regression locks for the `io` layer's completion seam (mementum/knowledge/
   design/architecture.md § D4, refactor step 3): build-request purity, the
   :complete-fn contract shape, the tool loop, and the three D4 amendments
   (slug-aware orientation, structural/visible budget, loud reasoning-only
   finals). Loop tests stub the backend at `completion/session-backend`
   (plain `defn-`, redef-able by design — see that fn's docstring) so no
   real network call ever happens; `p/await!` passes a plain map straight
   through (verified: `(p/await! 42)` → `42`), so `send-turn` can just
   return the canned Response map directly."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [com.fulcrologic.statecharts.promise :as p]
   [escapement.llm.protocol :as proto]
   [escapement.tools.protocol :as tp]
   [us.whitford.llm-repl.completion :as completion]
   [us.whitford.llm-repl.registry :as registry]
   [us.whitford.llm-repl.roster :as llm]
   [us.whitford.llm-repl.tape :as tape]
   [us.whitford.llm-repl.tools :as tools]))

;; ── fixture: clean registry ⊕ a trivial test tool (avoids real eval side
;;    effects that :clojure/eval would carry into a dispatch test) ─────────

(defrecord EchoTool []
  tp/Tool
  (tool-name [_] :test/echo)
  (description [_] "echoes its :x input back — test-only tool")
  (input-schema [_] [:map {:closed true} [:x :any]])
  (invoke [_ {:keys [x]}] {:result (pr-str x) :is-error false}))

(defn- reset-fixture
  [f]
  (reset! registry/sessions* {})
  (registry/reset-events!)
  (reset! registry/version* 0)
  (tp/register! tools/tool-registry* (->EchoTool))
  (f))

(use-fixtures :each reset-fixture)

;; ── stub backend ─────────────────────────────────────────────────────────

(defn- stub-backend
  "reify LLMBackend: every `send-turn` conjs the request onto `requests-atom`
   (so tests can assert what rode the wire) and pops the next canned
   response off `responses-atom` (a queue-in-an-atom). Returns the response
   map BARE — p/await! passes a plain value through unchanged, no
   `p/resolved` wrapping needed."
  [responses-atom requests-atom]
  (reify proto/LLMBackend
    (send-turn [_ request]
      (swap! requests-atom conj request)
      (let [resp (first @responses-atom)]
        (swap! responses-atom rest)
        resp))))

(defn- text-response [s]
  {:stop-reason :end_turn :content [{:type :text :text s}] :usage {} :model "stub"})

(defn- tool-use-response [id name input]
  {:stop-reason :tool_use :content [{:type :tool_use :id id :name name :input input}]
   :usage {} :model "stub"})

(defn- empty-response []
  {:stop-reason :end_turn :content [] :usage {} :model "stub"})

;; ── build-request (pure) ─────────────────────────────────────────────────

(deftest build-request-messages-projection-test
  (let [t   (-> [] (tape/append-user "hi") (tape/append-assistant "yo"))
        req (completion/build-request {:model :m :preamble? false} :s t)]
    (is (= (tape/render-messages t) (:messages req)))))

(deftest build-request-conversation-id-test
  (let [req (completion/build-request {:model :m :preamble? false} :probe [])]
    (is (= :probe (:conversation/id req)))))

(deftest build-request-system-cache-control-test
  (let [req (completion/build-request {:model :m :preamble? false} :s [])]
    (is (= {:type :ephemeral} (:system-cache-control req)))))

(deftest build-request-system-nil-when-blank-and-no-preamble-test
  (let [req (completion/build-request {:model :m :system "" :preamble? false} :s [])]
    (is (not (contains? req :system)))))

(deftest build-request-preamble-applied-test
  (with-redefs [llm/config (fn [] {:preamble "TEST-PREAMBLE"})]
    (let [req (completion/build-request {:model :m :system "sys text" :preamble? true} :s [])]
      (is (= "TEST-PREAMBLE\n\nsys text" (:system req))))))

(deftest build-request-thinking-modeled-test
  (testing "false → {:type :disabled}"
    (is (= {:type :disabled}
           (:thinking (completion/build-request {:model :m :preamble? false :thinking false} :s [])))))
  (testing "true → absent (server default)"
    (is (not (contains? (completion/build-request {:model :m :preamble? false :thinking true} :s [])
                        :thinking))))
  (testing "a modeled map passes through"
    (is (= {:type :enabled :budget-tokens 1024}
           (:thinking (completion/build-request
                       {:model :m :preamble? false :thinking {:type :enabled :budget-tokens 1024}}
                       :s [])))))
  (testing "nil → absent"
    (is (not (contains? (completion/build-request {:model :m :preamble? false :thinking nil} :s [])
                        :thinking)))))

(deftest build-request-temperature-test
  (testing "present"
    (is (= 0.7 (:temperature (completion/build-request
                              {:model :m :preamble? false :temperature 0.7} :s [])))))
  (testing "absent"
    (is (not (contains? (completion/build-request {:model :m :preamble? false} :s []) :temperature)))))

;; ── assistant-text ────────────────────────────────────────────────────────

(deftest assistant-text-test
  (testing "concatenates :text blocks, drops thinking/tool blocks"
    (is (= "helloworld"
           (completion/assistant-text
            {:content [{:type :text :text "hello"}
                       {:type :thinking :thinking "hmm"}
                       {:type :tool_use :id "1" :name "x" :input {}}
                       {:type :text :text "world"}]}))))
  (testing "\"\" on none"
    (is (= "" (completion/assistant-text {:content []})))))

;; ── session-tools ─────────────────────────────────────────────────────────

(deftest session-tools-true-test
  (is (= (tp/all-tools tools/tool-registry*) (completion/session-tools true))))

(deftest session-tools-whitelist-test
  (let [ts (completion/session-tools [:test/echo])]
    (is (= [:test/echo] (mapv tp/tool-name ts)))))

(deftest session-tools-unknown-throws-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown tool :nope/kw — registered:"
                        (completion/session-tools [:nope/kw]))))

(deftest session-tools-nil-test
  (is (nil? (completion/session-tools nil))))

;; ── tool-wire ─────────────────────────────────────────────────────────────

(deftest tool-wire-round-trip-test
  (let [{:keys [defs name->kw]} (completion/tool-wire (completion/session-tools [:test/echo]))]
    (is (= 1 (count defs)))
    (is (= "test_echo" (:name (first defs))))
    (is (= :test/echo (get name->kw "test_echo")))))

;; ── orientation (D4a ⊕ D7: config-chain resolved) ─────────────────────────

(deftest with-tools-system-substitutes-slug-test
  ;; the shipped default (builtin-defaults :orientation, bottom of the chain)
  (with-redefs [llm/config (fn [] llm/builtin-defaults)]
    (let [oriented (:system (completion/with-tools-system {} {:model :m} :s))]
      (is (str/includes? oriented ":s"))
      (is (not (str/includes? oriented "{slug}"))))))

(deftest with-tools-system-appends-with-blank-line-test
  (is (= "existing\n\nT:s"
         (:system (completion/with-tools-system
                   {:system "existing"} {:orientation "T{slug}"} :s)))))

(deftest with-tools-system-creates-system-when-absent-test
  (is (= "T:s" (:system (completion/with-tools-system {} {:orientation "T{slug}"} :s)))))

(deftest with-tools-system-session-config-replaces-test
  ;; D7: what the redef extension point became — a session config key
  (is (= "custom orientation for :probe only"
         (:system (completion/with-tools-system
                   {} {:orientation "custom orientation for {slug} only"} :probe)))))

(deftest with-tools-system-root-chain-test
  (with-redefs [llm/config (fn [] {:orientation "root {slug} orientation"})]
    (is (= "root :s orientation"
           (:system (completion/with-tools-system {} {:model :m} :s))))))

(deftest with-tools-system-explicit-none-leaves-request-untouched-test
  ;; chain resolves to NONE → an intentionally unoriented armed session
  (is (= {} (completion/with-tools-system {} {:orientation false} :s)))
  (is (= {:system "keep"} (completion/with-tools-system {:system "keep"} {:orientation nil} :s))))

;; ── tool loop ─────────────────────────────────────────────────────────────

(defn- tool-result-content
  "The :content STRING of the sole tool_result block riding request `req`'s
   last (user) message — the shape `tool-complete` builds each round."
  [req]
  (-> req :messages last :content first :content))

(deftest tool-complete-happy-path-test
  (let [responses (atom [(tool-use-response "1" "test_echo" {:x 42}) (text-response "final answer")])
        requests  (atom [])
        stub      (stub-backend responses requests)
        config    {:model :m :preamble? false :system nil :tools [:test/echo]
                   :orientation "orient {slug}"}]
    (with-redefs [completion/session-backend (fn [_ _] stub)]
      (let [out ((completion/tool-complete config :s) [])]
        (testing "dispatches once, returns the final text"
          (is (= "final answer" out)))
        (testing "the first request carries :tools defs and the orientation-bearing :system"
          (let [req0 (first @requests)]
            (is (seq (:tools req0)))
            (is (str/includes? (:system req0) ":s"))))
        (testing "messages grew by assistant(content)+user(tool_results) between rounds"
          (is (= 2 (count (:messages (second @requests))))))))))

(deftest tool-complete-remaining-count-test
  (let [responses (atom [(tool-use-response "1" "test_echo" {:x 1})
                         (tool-use-response "2" "test_echo" {:x 2})
                         (text-response "done")])
        requests  (atom [])
        stub      (stub-backend responses requests)
        config    {:model :m :preamble? false :system nil :tools [:test/echo]
                   :orientation "orient {slug}"}]
    (with-redefs [completion/session-backend (fn [_ _] stub)]
      ((completion/tool-complete config :s) [])
      (testing "the tool_result riding the SECOND request carries the round-0 remaining count"
        (is (str/includes? (tool-result-content (nth @requests 1)) "[7 dispatches remain]")))
      (testing "the tool_result riding the THIRD request carries the round-1 (decremented) count"
        (is (str/includes? (tool-result-content (nth @requests 2)) "[6 dispatches remain]"))))))

(deftest tool-complete-budget-boundary-test
  (let [responses (atom [(tool-use-response "1" "test_echo" {:x 1})
                         (tool-use-response "2" "test_echo" {:x 2})
                         (tool-use-response "3" "test_echo" {:x 3})
                         (text-response "the final word")])
        requests  (atom [])
        stub      (stub-backend responses requests)
        config    {:model :m :preamble? false :system nil :tools [:test/echo]
                   :orientation "orient {slug}"}]
    (with-redefs [completion/session-backend (fn [_ _] stub)
                  completion/tool-bounce-budget 2]
      (let [out ((completion/tool-complete config :s) [])]
        (testing "the final request has NO :tools key — stripped at the boundary"
          (is (= 4 (count @requests)))
          (is (not (contains? (last @requests) :tools))))
        (testing "the refusal tool_results rode the final request"
          (is (str/includes? (tool-result-content (last @requests)) "BUDGET EXHAUSTED")))
        (testing "the final text is returned"
          (is (= "the final word" out)))))))

;; ── empty final (D4c) ─────────────────────────────────────────────────────

(deftest tool-complete-empty-final-text-only-exit-test
  (let [responses (atom [(empty-response)])
        requests  (atom [])
        stub      (stub-backend responses requests)
        config    {:model :m :preamble? false :system nil :tools [:test/echo]
                   :orientation "orient {slug}"}]
    (with-redefs [completion/session-backend (fn [_ _] stub)]
      (let [out ((completion/tool-complete config :s) [])]
        (is (= completion/empty-completion-marker out))
        (is (some #(= :error (:kind %)) @registry/events*))))))

(deftest tool-complete-empty-final-budget-exit-test
  (let [responses (atom [(tool-use-response "1" "test_echo" {:x 1})
                         (tool-use-response "2" "test_echo" {:x 2})
                         (tool-use-response "3" "test_echo" {:x 3})
                         (empty-response)])
        requests  (atom [])
        stub      (stub-backend responses requests)
        config    {:model :m :preamble? false :system nil :tools [:test/echo]
                   :orientation "orient {slug}"}]
    (with-redefs [completion/session-backend (fn [_ _] stub)
                  completion/tool-bounce-budget 2]
      (let [out ((completion/tool-complete config :s) [])]
        (is (= completion/empty-completion-marker out))
        (is (some #(= :error (:kind %)) @registry/events*))))))

(deftest plain-complete-empty-final-test
  (let [responses (atom [(empty-response)])
        requests  (atom [])
        stub      (stub-backend responses requests)
        config    {:model :m :preamble? false :system nil}]
    (with-redefs [completion/session-backend (fn [_ _] stub)]
      (let [out ((completion/plain-complete config :s) [])]
        (is (= completion/empty-completion-marker out))
        (is (some #(= :error (:kind %)) @registry/events*))))))

;; ── default-complete routing ─────────────────────────────────────────────

(deftest default-complete-tools-nil-routes-plain-test
  (let [responses (atom [(text-response "hi")])
        requests  (atom [])
        stub      (stub-backend responses requests)
        config    {:model :m :preamble? false :system nil :tools nil}]
    (with-redefs [completion/session-backend (fn [_ _] stub)]
      (let [out ((completion/default-complete config :s) [])]
        (is (= "hi" out))
        (is (not (contains? (first @requests) :tools)))))))

(deftest default-complete-tools-armed-routes-tooled-test
  (let [responses (atom [(text-response "hi")])
        requests  (atom [])
        stub      (stub-backend responses requests)
        config    {:model :m :preamble? false :system nil :tools [:test/echo]
                   :orientation "orient {slug}"}]
    (with-redefs [completion/session-backend (fn [_ _] stub)]
      ((completion/default-complete config :s) [])
      (is (seq (:tools (first @requests)))))))

(deftest default-complete-depth-guard-routes-plain-test
  (let [responses (atom [(text-response "hi")])
        requests  (atom [])
        stub      (stub-backend responses requests)
        config    {:model :m :preamble? false :system nil :tools [:test/echo]
                   :orientation "orient {slug}"}]
    (with-redefs [completion/session-backend (fn [_ _] stub)]
      (binding [completion/*tool-depth* 1]
        ((completion/default-complete config :s) [])))
    (testing "routed plain — no :tools on the wire despite :tools armed"
      (is (not (contains? (first @requests) :tools))))
    (testing "a {:kind :tool} depth-guard receipt was emitted"
      (is (some #(and (= :tool (:kind %)) (str/includes? (:msg %) "depth-guard")) @registry/events*)))))

;; ── *tool-depth* conveyance (bb futures convey bindings) ─────────────────

(deftest tool-depth-conveys-through-future-test
  (is (= 1 (binding [completion/*tool-depth* 1]
             @(future completion/*tool-depth*)))))
