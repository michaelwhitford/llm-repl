(ns us.whitford.llm-repl.core-test
  "The D2 regression locks (mementum/knowledge/design/architecture.md § D2):
   every driver injects a STUB `:complete-fn` — (fn [config slug] (fn [tape]
   → reply-text)), core's actual injected-IO contract — never a real backend.
   The deterministic race lock is the centerpiece: a stub that itself mutates
   the registry mid-completion, simulating the live-confirmed v0.2.0 bug
   (a concurrent write silently clobbered by store!) WITHOUT threads or
   sleeps — no flakiness possible."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [us.whitford.llm-repl.core :as core]
   [us.whitford.llm-repl.registry :as registry]
   [us.whitford.llm-repl.tape :as tape]))

(defn- reset-registry-fixture
  [f]
  (reset! registry/sessions* {})
  (registry/reset-events!)
  (reset! registry/version* 0)
  (f))

(use-fixtures :each reset-registry-fixture)

;; ── stub complete-fns ────────────────────────────────────────────────────

(defn- stub-complete
  "The plainest stub: config ⊕ slug → (fn [tape] → \"reply\"), ignoring both."
  [_config _slug]
  (fn [_tape] "reply"))

(defn- throwing-complete
  [_config _slug]
  (fn [_tape] (throw (ex-info "backend unreachable" {}))))

(defn- interloping-complete
  "THE DETERMINISTIC RACE LOCK: while THIS completion runs (between eval!'s
   user-turn mutate! and its assistant-turn mutate!), append an interloper
   turn to the SAME slug directly via registry/mutate! — simulating a
   concurrent client's write landing mid-flight (the live-confirmed v0.2.0
   race), then return this call's own reply. No thread, no sleep, no flake."
  [_config slug]
  (fn [_tape]
    (registry/mutate!
     (fn [reg]
       (if (contains? reg slug)
         (update-in reg [slug :tape] tape/append-assistant "interloper-reply")
         reg)))
    "reply"))

(defn- mid-fold-interloping-complete
  "Same trick, aimed at run-battery!'s local fold: on its FIRST invocation
   (there are several — one per probe), append an interloper turn directly,
   then behave normally for the rest of the fold. Simulates the registry
   moving while a battery folds locally over its starting snapshot (G2)."
  [_config slug]
  (let [fired? (atom false)]
    (fn [_tape]
      (when-not @fired?
        (reset! fired? true)
        (registry/mutate!
         (fn [reg]
           (if (contains? reg slug)
             (update-in reg [slug :tape] tape/append-assistant "battery-interloper")
             reg))))
      "reply")))

;; ── eval! happy path ─────────────────────────────────────────────────────

(deftest eval!-happy-path-test
  (let [result (core/eval! :s "hi" {:complete-fn stub-complete})
        tape'  (:tape (core/snapshot :s))]
    (testing "tape gains a user turn then an assistant turn"
      (is (= [:user :assistant] (mapv :role tape')))
      (is (= "hi" (:text (first tape'))))
      (is (= "reply" (:text (second tape')))))
    (testing ":turns derived ≡ assistant count"
      (is (= 1 (:turns (core/snapshot :s)))))
    (testing "result shape"
      (is (= {:repl/id :s :repl/reply "reply" :repl/depth 2 :repl/turns 1}
             (select-keys result [:repl/id :repl/reply :repl/depth :repl/turns]))))))

;; ── eval! completion throw — persist-first, retry-safe ──────────────────

(deftest eval!-completion-throw-retains-user-turn-test
  (let [result (core/eval! :s2 "hi" {:complete-fn throwing-complete})
        tape'  (:tape (core/snapshot :s2))]
    (testing "the user turn PERSISTS — a retry continues from it"
      (is (= [:user] (mapv :role tape')))
      (is (= "hi" (:text (first tape')))))
    (testing "result carries :repl/error, no :repl/reply"
      (is (some? (:repl/error result)))
      (is (nil? (:repl/reply result))))
    (testing "a loud event was emitted"
      (is (some #(and (= :eval! (:kind %)) (str/starts-with? (:msg %) "✗"))
                @registry/events*)))))

;; ── THE deterministic race lock ──────────────────────────────────────────

(deftest eval!-race-lock-test
  (core/open! :race)
  (let [result (core/eval! :race "probe" {:complete-fn interloping-complete})
        tape'  (:tape (core/snapshot :race))
        texts  (mapv :text tape')]
    (testing "append-not-clobber: BOTH turns land (under v0.2.0 store! the
              interloper was silently lost)"
      (is (some #{"interloper-reply"} texts) "the interloper's turn survived")
      (is (some #{"reply"} texts) "eval!'s own reply also landed")
      (is (= "reply" (:repl/reply result))))
    (testing "a {:kind :raced} event was emitted — visible, never silent"
      (is (some #(= :raced (:kind %)) @registry/events*)))
    (testing ":turns counts every assistant turn on the final tape, race included"
      (is (= (count (filter #(= :assistant (:role %)) tape'))
             (:turns (core/snapshot :race)))))))

;; ── fork! TOCTOU ──────────────────────────────────────────────────────────

(deftest fork!-missing-from-test
  (let [r (core/fork! :nope :dest)]
    (is (some? (:repl/error r)))))

(deftest fork!-existing-to-test
  (core/open! :src)
  (core/open! :dest)
  (let [r (core/fork! :src :dest)]
    (is (some? (:repl/error r)))))

(deftest fork!-happy-truncation-derives-turns-test
  (core/eval! :src2 "a" {:complete-fn stub-complete})
  (core/eval! :src2 "b" {:complete-fn stub-complete})
  (let [r         (core/fork! :src2 :dest2 {:at 2})
        dest-sess (core/snapshot :dest2)]
    (is (nil? (:repl/error r)))
    (is (= 2 (count (:tape dest-sess))) "truncated to the first 2 messages")
    (is (= 1 (:turns dest-sess)) ":turns re-derived from the TRUNCATED tape")))

;; ── open! idempotent get-or-create; drop!/reset-all! ────────────────────

(deftest open!-idempotent-and-config-merge-test
  (core/open! :o {:temperature 0.1})
  (core/open! :o {:temperature 0.9})
  (is (= 0.9 (get-in (core/snapshot :o) [:config :temperature])) "reopen merges config")
  (is (= 1 (count (filter #(= :open! (:kind %)) @registry/events*)))
      "only ONE open! event fired — the reopen was a merge, not a creation"))

(deftest drop!-and-reset-all!-test
  (core/open! :d1)
  (testing "drop! reports existence"
    (is (true? (core/drop! :d1)))
    (is (false? (core/drop! :d1))))
  (core/open! :r1)
  (core/open! :r2)
  (core/reset-all!)
  (is (= {} @registry/sessions*)))

;; ── run-battery! ──────────────────────────────────────────────────────────

(deftest run-battery!-stub-test
  (let [r     (core/run-battery! :b ["p1" "p2"] {:complete-fn stub-complete})
        tape' (:tape (core/snapshot :b))]
    (is (= 4 (count tape')) "2 probes × (user+assistant)")
    (is (= 2 (:turns (core/snapshot :b))))
    (is (= 2 (:repl/turns r)))))

(deftest run-battery!-raced-test
  (core/open! :rb)
  (let [r     (core/run-battery! :rb ["p1" "p2"] {:complete-fn mid-fold-interloping-complete})
        tape' (:tape (core/snapshot :rb))
        texts (mapv :text tape')]
    (testing "the interloper's turn AND both battery replies all land"
      (is (some #{"battery-interloper"} texts))
      (is (= 3 (count (filter #(= :assistant (:role %)) tape')))))
    (testing "a raced event was emitted"
      (is (some #(= :raced (:kind %)) @registry/events*)))
    (testing ":turns still correct — derived from the final tape"
      (is (= (count (filter #(= :assistant (:role %)) tape'))
             (:turns (core/snapshot :rb))))
      (is (= (:turns (core/snapshot :rb)) (:repl/turns r))))))
