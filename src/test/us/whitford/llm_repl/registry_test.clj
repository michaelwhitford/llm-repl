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
  (reset! registry/event-tap* nil)
  (reset! registry/mutate-tap* nil)
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

;; ── index / view (the wire's payload shapes) ──────────────────────────────

(def ^:private two-sessions
  {:parent {:slug :parent :tape [{:role :user :text "BODY-ALPHA"}
                                 {:role :assistant :text "BODY-BETA"}]
            :config {:model "m1" :preamble? true} :turns 1}
   :child  {:slug :child :tape [{:role :user :text "BODY-GAMMA"}]
            :config {:model "m2"} :turns 0
            :forked-from :parent :forked-at 2}})

(deftest index-drops-bodies-keeps-edges-test
  (testing "message bodies are GONE; edges ∧ counts survive"
    (let [idx (registry/index two-sessions)]
      (is (= #{:parent :child} (set (keys idx))) "keyed by slug")
      (is (= {:slug :parent :model "m1" :preamble? true :depth 2 :turns 1
              :forked-from nil :forked-at nil}
             (:parent idx)))
      (is (= {:slug :child :model "m2" :preamble? nil :depth 1 :turns 0
              :forked-from :parent :forked-at 2}
             (:child idx)))
      (is (not-any? #(contains? % :tape) (vals idx)) "no :tape key at all")
      (is (not (re-find #"BODY-" (pr-str idx))) "no message text anywhere in the payload"))))

(deftest index-is-pure-and-smaller-test
  (testing "pure fn of its argument — never derefs sessions*"
    (reset! registry/sessions* {})
    (is (= 2 (count (registry/index two-sessions)))))
  (testing "the projection is strictly smaller on the wire (the whole point)"
    (is (< (count (pr-str (registry/index two-sessions)))
           (count (pr-str two-sessions))))))

(deftest view-splits-payload-not-round-trip-test
  (reset! registry/sessions* two-sessions)
  (testing "index ⊕ the FOCUSED tape only, from ONE deref"
    (let [v (registry/view :parent)]
      (is (= #{:index :slug :tape} (set (keys v))))
      (is (= :parent (:slug v)))
      (is (= [{:role :user :text "BODY-ALPHA"} {:role :assistant :text "BODY-BETA"}] (:tape v)))
      (is (= 1 (:depth (get-in v [:index :child]))) "other sessions ride as counts")
      (is (nil? (get-in v [:index :child :tape])))))
  (testing "index depth ∧ focused tape count agree — a torn read is unreachable"
    (let [v (registry/view :parent)]
      (is (= (count (:tape v)) (get-in v [:index :parent :depth])))))
  (testing "unknown focus ≡ :tape nil (NOT-A-TAPE), index still whole"
    (let [v (registry/view :nope)]
      (is (nil? (:tape v)))
      (is (= 2 (count (:index v))))))
  (testing "an open-but-empty session ≡ [] — distinct from nil"
    (reset! registry/sessions* {:fresh {:slug :fresh :tape [] :config {} :turns 0}})
    (is (= [] (:tape (registry/view :fresh))))))

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

;; ── injected taps — D9 disarm-on-throw (tap-failure-receipts) ─────────────
;; architecture.md § D9: a tap that throws is DISARMED (slot reset! nil
;; FIRST — recursion-safe by construction, this test suite completing IS the
;; recursion proof) ⊕ ONE loud :tap-disarmed receipt. Never silent
;; (the pre-D9 `(catch Throwable _ nil)` was S3* failing silently —
;; knowledge/state-audit.md §1), never spam (one failure ≡ one receipt).

(deftest event-tap-throw-disarms-and-receipts-test
  (testing "a throwing event-tap → slot nil ⊕ exactly ONE :tap-disarmed
            receipt naming the tap ∧ the throw; the original event still
            lands FIRST (its append precedes the tap call), and event!
            returns it despite the throw"
    (reset! registry/event-tap* (fn [_] (throw (ex-info "boom" {}))))
    (let [e (registry/event! {:kind :eval! :slug :s :msg "hi"})]
      (is (= :eval! (:kind e)) "event! returns the completed event map"))
    (is (nil? @registry/event-tap*) "slot disarmed")
    (let [evs @registry/events*]
      (is (= [:eval! :tap-disarmed] (mapv :kind evs)) "original first, receipt second")
      (is (re-find #"event-tap threw .*boom" (:msg (peek evs)))
          "receipt names which tap ∧ what killed it"))))

(deftest tap-disarm-receipt-fires-once-test
  (testing "after disarm, later event! calls are tap-free — one failure ≡
            one receipt, never receipt spam"
    (reset! registry/event-tap* (fn [_] (throw (ex-info "boom" {}))))
    (registry/event! {:kind :eval! :slug :s :msg "one"})
    (registry/event! {:kind :eval! :slug :s :msg "two"})
    (registry/event! {:kind :eval! :slug :s :msg "three"})
    (is (= 1 (count (filter #(= :tap-disarmed (:kind %)) @registry/events*))))))

(deftest mutate-tap-throw-disarms-and-receipts-test
  (testing "a throwing mutate-tap never breaks the mutation path — the swap ∧
            EDN assert ∧ version bump all precede the tap; slot disarmed ⊕
            ONE receipt"
    (reset! registry/mutate-tap* (fn [_ _] (throw (RuntimeException. "disk gone"))))
    (let [before-version @registry/version*
          [old new] (registry/mutate! #(assoc % :s {:tape [] :turns 0}))]
      (is (= {} old))
      (is (= {:s {:tape [] :turns 0}} new) "mutation landed despite the tap throw")
      (is (< before-version @registry/version*) "version bumped normally"))
    (is (nil? @registry/mutate-tap*) "slot disarmed")
    (let [receipts (filterv #(= :tap-disarmed (:kind %)) @registry/events*)]
      (is (= 1 (count receipts)))
      (is (re-find #"mutate-tap threw .*disk gone" (:msg (first receipts)))))))

(deftest mutate-tap-disarm-observed-by-event-tap-test
  (testing "the mutate-tap disarm receipt flows through event! — a healthy,
            still-armed event-tap OBSERVES it (the transcript records the
            durability loss) and is NOT itself disarmed"
    (let [seen (atom [])]
      (reset! registry/event-tap* (fn [e] (swap! seen conj (:kind e))))
      (reset! registry/mutate-tap* (fn [_ _] (throw (ex-info "x" {}))))
      (registry/mutate! #(assoc % :a {:tape [] :turns 0}))
      (is (= [:tap-disarmed] @seen) "event-tap saw the receipt")
      (is (some? @registry/event-tap*) "event-tap survives a mutate-tap disarm")
      (is (nil? @registry/mutate-tap*)))))
