(ns us.whitford.llm-repl.registry-test
  "Regression locks for the `runtime` layer (registry.clj) — the ONE mutable
   place, and the D2/D3 chokepoints that make its invariants structural
   instead of documented (mementum/knowledge/design/architecture.md)."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [us.whitford.llm-repl.registry :as registry]))

(defrecord Widget [x])

(defn- reset-registry-fixture
  "Full clean slate around every test — the three atoms are public, so a
   direct reset! is the honest one-liner (registry/reset-events! only owns
   the event ring/id counter, deliberately)."
  [f]
  (reset! registry/sessions* {})
  (registry/reset-events!)
  (reset! registry/version* 0)
  (f))

(use-fixtures :each reset-registry-fixture)

;; ── mutate! ───────────────────────────────────────────────────────────────

(deftest mutate!-applies-pure-fn-test
  (testing "applies f, returns [old new]"
    (let [[old new] (registry/mutate! (fn [reg] (assoc reg :s {:tape [] :turns 0})))]
      (is (= {} old))
      (is (= {:s {:tape [] :turns 0}} new)))))

(deftest mutate!-version-strictly-increases-test
  (testing "version* bumps by exactly 1 per successful mutate! call"
    (let [before @registry/version*]
      (registry/mutate! #(assoc % :a 1))
      (is (= (inc before) @registry/version*))
      (registry/mutate! #(assoc % :b 2))
      (is (= (+ before 2) @registry/version*)))))

(deftest mutate!-edn-assert-throws-test
  (testing "a fn stored in a session → mutate! throws, sessions* keeps the
            (bad) swapped value — pinned choice (see registry/mutate! doc):
            the swap already happened when the assert fires, and it does NOT
            roll back. version* is NOT bumped (the throw precedes that line)."
    (let [before-version @registry/version*]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-EDN"
                             (registry/mutate! (fn [reg] (assoc reg :bad {:f (fn [] 1)})))))
      (is (contains? @registry/sessions* :bad) "the bad swap is NOT rolled back")
      (is (= before-version @registry/version*) "version* did not bump on a throw")))
  (testing "an atom stored in a session → throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (registry/mutate! (fn [reg] (assoc reg :bad2 {:a (atom 1)}))))))
  (testing "a record stored in a session → throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (registry/mutate! (fn [reg] (assoc reg :bad3 {:r (->Widget 1)})))))))

;; ── edn-violations ────────────────────────────────────────────────────────

(deftest edn-violations-clean-test
  (testing "nil for a clean, all-EDN session map"
    (is (nil? (registry/edn-violations {:s {:tape [] :config {:model :m} :turns 0}})))))

(deftest edn-violations-detects-fn-test
  (let [v (registry/edn-violations {:s {:complete-fn (fn [] :nope)}})]
    (is (some? v))
    (is (= 1 (count v)))))

(deftest edn-violations-detects-ideref-test
  (let [v (registry/edn-violations {:s {:cache (atom {})}})]
    (is (some? v))))

(deftest edn-violations-detects-record-test
  (let [v (registry/edn-violations {:s {:thing (->Widget 42)}})]
    (is (some? v))))

;; ── event! ────────────────────────────────────────────────────────────────

(deftest event!-map-gets-id-and-at-test
  (let [e (registry/event! {:kind :note :msg "hi"})]
    (is (pos-int? (:id e)))
    (is (pos-int? (:at e)))
    (is (= :note (:kind e)))
    (is (= "hi" (:msg e)))))

(deftest event!-ids-strictly-monotonic-test
  (let [a (registry/event! {:kind :note :msg "a"})
        b (registry/event! {:kind :note :msg "b"})
        c (registry/event! {:kind :note :msg "c"})]
    (is (< (:id a) (:id b) (:id c)))))

(deftest event!-string-coerces-test
  (let [e (registry/event! "plain string")]
    (is (= {:kind :note :msg "plain string"}
           (select-keys e [:kind :msg])))))

(deftest event!-ring-bounded-test
  (testing "pushing 210 events keeps only the last 200"
    (dotimes [i 210] (registry/event! (str "e" i)))
    (is (= 200 (count @registry/events*)))
    (is (= "e209" (:msg (last @registry/events*))))
    (is (= "e10"  (:msg (first @registry/events*))))))

(deftest event!-bumps-version-test
  (let [before @registry/version*]
    (registry/event! "x")
    (is (= (inc before) @registry/version*))))

;; ── event-line ────────────────────────────────────────────────────────────

(deftest event-line-eval-test
  (is (= "eval! :s ✓@6" (registry/event-line {:kind :eval! :slug :s :msg "✓@6"}))))

(deftest event-line-note-test
  (is (= "bare message" (registry/event-line {:kind :note :msg "bare message"}))))

(deftest event-line-string-passthrough-test
  (is (= "already a line" (registry/event-line "already a line"))))

(deftest event-line-nil-slug-elided-test
  (is (= "reset-all!" (registry/event-line {:kind :reset-all!}))))

;; ── events-since ──────────────────────────────────────────────────────────

(deftest events-since-test
  (let [a (registry/event! "a")
        b (registry/event! "b")
        _c (registry/event! "c")]
    (testing "only ids > since"
      (is (= 2 (count (registry/events-since (:id a))))))
    (testing "empty once caught up"
      (is (= [] (registry/events-since (:id (registry/event! "d"))))))
    (is (= (:id b) (:id (first (registry/events-since (:id a))))))))

;; ── wait-for-event! ───────────────────────────────────────────────────────

(deftest wait-for-event!-pending-returns-immediately-test
  (let [a (registry/event! "a")
        b (registry/event! "b")]
    (is (= [b] (registry/wait-for-event! (:id a) 1000)))))

(deftest wait-for-event!-woken-by-future-event-test
  (let [start (:id (registry/event! "start"))
        result (future (registry/wait-for-event! start 5000))]
    (Thread/sleep 50)
    (registry/event! "woke-it")
    (let [woken (deref result 2000 :timed-out)]
      (is (not= :timed-out woken))
      (is (= ["woke-it"] (mapv :msg woken))))))

(deftest wait-for-event!-timeout-test
  (let [start (:id (registry/event! "start"))
        t0    (System/currentTimeMillis)
        r     (registry/wait-for-event! start 50)]
    (is (= [] r))
    (is (< (- (System/currentTimeMillis) t0) 2000) "returns promptly, not after some huge default")))
