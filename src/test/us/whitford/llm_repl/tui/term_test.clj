(ns us.whitford.llm-repl.tui.term-test
  "Locks for term's ONE testable seam: the `update-state!` chokepoint ⊕
   closed key schema (audit §2 — registry/mutate!'s pattern at TUI scale).
   Everything else in term.clj moves bytes at a real terminal and stays out
   of the suite by construction (D5); the chokepoint and the named mutators
   riding it are pure-enough — a plain atom, no terminal required."
  (:require
   [clojure.test :refer [deftest is testing]]
   [us.whitford.llm-repl.tui.term :as term]))

(deftest update-state!-applies-and-returns
  (testing "a pure fn of the current state; returns the NEW state"
    (let [st (atom {:slug :a :scroll 3 :render-dirty false})
          s' (term/update-state! st #(assoc % :render-dirty true))]
      (is (true? (:render-dirty s')))
      (is (= s' @st)))))

(deftest update-state!-closed-schema-throws-loud
  (testing "an unknown key → loud throw NAMING the key — the silent
            alternative was a typo'd key ⇒ a flag nobody reads ⇒ no repaint,
            ever (audit §2)"
    (let [st (atom {:slug :a})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"render-dity"
                            (term/update-state! st #(assoc % :render-dity true)))))
    ;; fresh atom — the no-rollback pin below means the first offender
    ;; would otherwise still be in the map here
    (let [st (atom {:slug :a})]
      (try (term/update-state! st #(assoc % :also-bad 1))
           (catch clojure.lang.ExceptionInfo e
             (is (= [:also-bad] (:unknown (ex-data e))))
             (is (= term/state-keys (:allowed (ex-data e))))))))
  (testing "pinned choice (registry/mutate! precedent): the swap has already
            landed and is NOT rolled back — the atom shows the offending
            shape, not a politely-reverted lie"
    (let [st (atom {:slug :a})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (term/update-state! st #(assoc % :oops 1))))
      (is (contains? @st :oops))))
  (testing "every key start! seeds (⊕ :overlay, :events) is declared — the
            schema covers the actual state shape"
    (let [st (atom {})]
      (is (map? (term/update-state!
                 st #(assoc % :view nil :events-ref nil :slug :s :nrepl-port 1
                            :scroll 0 :events [] :pending nil :input {}
                            :term-w 80 :term-h 24 :render-dirty true
                            :overlay nil))))))
  (testing "removing keys is always legal (dismiss-overlay!'s dissoc)"
    (let [st (atom {:slug :a :overlay {:title "t"}})]
      (is (not (contains? (term/update-state! st #(dissoc % :overlay))
                          :overlay))))))

(deftest named-mutators-ride-the-chokepoint
  (testing "focus-slug! ≡ focus moves, scroll resets, repaint requested"
    (let [st (atom {:slug :a :scroll 9 :render-dirty false})]
      (term/focus-slug! st :b)
      (is (= {:slug :b :scroll 0 :render-dirty true} @st))))
  (testing "set-pending! marks and clears"
    (let [st (atom {:slug :a})]
      (term/set-pending! st :a)
      (is (= :a (:pending @st)))
      (term/set-pending! st nil)
      (is (nil? (:pending @st)))))
  (testing "show-overlay!/dismiss-overlay! round-trip under the schema"
    (let [st (atom {:slug :a :scroll 5})]
      (term/show-overlay! st {:title "help" :lines ["x"]})
      (is (= "help" (get-in @st [:overlay :title])))
      (is (zero? (:scroll @st)))
      (term/dismiss-overlay! st)
      (is (not (contains? @st :overlay)))))
  (testing "scroll-view! sign flips per body kind, floors at 0 (tape ≡
            tail-anchored, overlay ≡ head-anchored)"
    (let [st (atom {:slug :a :scroll 0})]
      (term/scroll-view! st :up 5)            ; tape: up ≡ +
      (is (= 5 (:scroll @st)))
      (term/scroll-view! st :down 99)         ; floors at 0
      (is (zero? (:scroll @st)))
      (swap! st assoc :overlay {:title "t"})  ; overlay: up ≡ −
      (term/scroll-view! st :up 3)
      (is (zero? (:scroll @st))))))
