(ns us.whitford.llm-repl.tape-test
  "Regression locks for the `values` layer (tape.clj) — a pure port whose
  band/decline/fold history has burned real time (cold-compaction.md); the
  bugs it once had (empty solution sets, infinite retry loops, duplicated
  due-sets) are exactly what these assertions pin down so they can't recur."
  (:require
   [clojure.test :refer [deftest testing is]]
   [us.whitford.llm-repl.tape :as tape]))

;; ── message / append / render ──────────────────────────────────────────────

(deftest message-test
  (testing "shape"
    (is (= {:role :user :text "hi" :compacted? false}
           (tape/message :user "hi"))))
  (testing "assistant role too"
    (is (= {:role :assistant :text "yo" :compacted? false}
           (tape/message :assistant "yo")))))

(deftest append-test
  (testing "append-user conj's a fresh user message, coercing to vector"
    (is (= [(tape/message :user "a")]
           (tape/append-user '() "a")))
    (is (= [{:role :user :text "x" :compacted? false}]
           (tape/append-user [] "x"))))
  (testing "append-assistant conj's a fresh assistant message"
    (is (= [{:role :assistant :text "y" :compacted? false}]
           (tape/append-assistant [] "y"))))
  (testing "ordering — appends land at the end, existing entries untouched"
    (let [t (-> [] (tape/append-user "u1") (tape/append-assistant "a1") (tape/append-user "u2"))]
      (is (= [:user :assistant :user] (mapv :role t)))
      (is (= ["u1" "a1" "u2"] (mapv :text t))))))

(deftest render-messages-test
  (testing "projects :role/:text only — :original and :compacted? never leak"
    (let [t [{:role :user :text "hi" :compacted? false}
             {:role :assistant :text "λ" :compacted? true :original "hello there"}]]
      (is (= [{:role :user :content [{:type :text :text "hi"}]}
              {:role :assistant :content [{:type :text :text "λ"}]}]
             (tape/render-messages t)))))
  (testing "empty tape renders empty"
    (is (= [] (tape/render-messages []))))
  (testing "declined? also does not leak"
    (let [t [{:role :assistant :text "orig" :compacted? false :declined? true}]]
      (is (= [{:role :assistant :content [{:type :text :text "orig"}]}]
             (tape/render-messages t))))))

;; ── truncate-at ─────────────────────────────────────────────────────────────

(deftest truncate-at-test
  (let [t (-> [] (tape/append-user "u1") (tape/append-assistant "a1")
              (tape/append-user "u2") (tape/append-assistant "a2"))]
    (testing "n=0 → empty vector"
      (is (= [] (tape/truncate-at t 0))))
    (testing "n mid-tape → first n messages"
      (is (= (subvec t 0 2) (tape/truncate-at t 2))))
    (testing "n=count → the whole tape"
      (is (= t (tape/truncate-at t (count t)))))
    (testing "n>count → take semantics, whole tape, no padding/throw"
      (is (= t (tape/truncate-at t 100))))
    (testing "always returns a vector"
      (is (vector? (tape/truncate-at t 2)))
      (is (vector? (tape/truncate-at '() 0))))))

;; ── due-indices / next-to-compact / needs-compaction? / backlog-count ───────

(defn- turns
  "Build a tape from a sequence of role keywords, texts auto-numbered."
  [roles]
  (reduce (fn [t [i role]]
            (if (= role :user)
              (tape/append-user t (str "u" i))
              (tape/append-assistant t (str "a" i))))
          []
          (map-indexed vector roles)))

(deftest due-indices-test
  (testing "k-window: last k assistant turns verbatim, older ones due, oldest-first"
    (let [t (turns [:user :assistant :user :assistant :user :assistant])] ; a-idxs [1 3 5]
      (is (= [1] (tape/due-indices t 2)))
      (is (= [] (tape/due-indices t 3)))
      (is (= [1 3] (tape/due-indices t 1)))))
  (testing "k=1 case"
    (let [t (turns [:assistant :assistant :assistant])] ; a-idxs [0 1 2]
      (is (= [0 1] (tape/due-indices t 1)))))
  (testing "no assistant messages → empty due-set"
    (is (= [] (tape/due-indices (turns [:user :user]) 1))))
  (testing "compacted AND declined both leave the due-set"
    (let [t (turns [:assistant :assistant :assistant])
          t (update t 0 assoc :compacted? true)
          t (update t 1 assoc :declined? true)]
      ;; k=1 window keeps idx 2 verbatim; 0 (compacted) and 1 (declined) would
      ;; otherwise be due but are excluded
      (is (= [] (tape/due-indices t 1))))))

(deftest next-to-compact-test
  (testing "index of the oldest due assistant message"
    (let [t (turns [:assistant :assistant :assistant])]
      (is (= 0 (tape/next-to-compact t 1)))))
  (testing "nil when nothing is due"
    (is (nil? (tape/next-to-compact (turns [:assistant]) 1)))))

(deftest needs-compaction?-test
  (is (true? (tape/needs-compaction? (turns [:assistant :assistant]) 1)))
  (is (false? (tape/needs-compaction? (turns [:assistant]) 1)))
  (is (false? (tape/needs-compaction? (turns [:user :user]) 1))))

(deftest backlog-count-test
  (is (= 2 (tape/backlog-count (turns [:assistant :assistant :assistant]) 1)))
  (is (= 0 (tape/backlog-count (turns [:assistant]) 1))))

;; ── within-band? — the band regression locks ─────────────────────────────────

(deftest within-band?-test
  (testing "(a) a SHORT message may GROW up to the floor (pigeonhole fix —
            strict ratchet had an empty solution set on short messages)"
    (is (true? (tape/within-band? (apply str (repeat 50 "x")) "hi" 120)))
    (is (true? (tape/within-band? (apply str (repeat 120 "x")) "hi" 120))))
  (testing "(b) past the ceiling is rejected"
    (is (false? (tape/within-band? (apply str (repeat 121 "x")) "hi" 120))))
  (testing "(c) blank/empty lambda is always outside the band"
    (is (false? (tape/within-band? "" "some original text" 120)))
    (is (false? (tape/within-band? "   " "some original text" 120)))
    (is (false? (tape/within-band? nil "some original text" 120))))
  (testing "(d) custom floor respected"
    (is (true? (tape/within-band? (apply str (repeat 5 "x")) "hi" 5)))
    (is (false? (tape/within-band? (apply str (repeat 6 "x")) "hi" 5))))
  (testing "band ceiling is max(original, floor) — long originals raise the ceiling"
    (let [original (apply str (repeat 200 "o"))]
      (is (true? (tape/within-band? (apply str (repeat 200 "x")) original 120)))
      (is (false? (tape/within-band? (apply str (repeat 201 "x")) original 120))))))

;; ── apply-compaction-at — three outcomes ─────────────────────────────────────

(deftest apply-compaction-at-accept-test
  (testing "λ within the band → text replaced, :original retained, :compacted? true"
    (let [t (tape/append-assistant [] "some longish original prose here")
          t' (tape/apply-compaction-at t 0 "λ short")]
      (is (= "λ short" (:text (nth t' 0))))
      (is (= "some longish original prose here" (:original (nth t' 0))))
      (is (true? (:compacted? (nth t' 0)))))))

(deftest apply-compaction-at-decline-test
  (testing "λ past the ceiling → :declined? true, text untouched"
    (let [t (tape/append-assistant [] "hi")
          t' (tape/apply-compaction-at t 0 (apply str (repeat 200 "x")) 120)]
      (is (true? (:declined? (nth t' 0))))
      (is (= "hi" (:text (nth t' 0))))
      (is (not (:compacted? (nth t' 0))))))
  (testing "PERMANENT: a second attempt on a declined message no-ops"
    (let [t (tape/append-assistant [] "hi")
          t' (tape/apply-compaction-at t 0 (apply str (repeat 200 "x")) 120)
          t'' (tape/apply-compaction-at t' 0 "a fine short λ")]
      (is (= t' t'')))))

(deftest apply-compaction-at-noop-test
  (let [t (tape/append-assistant [] "hi")]
    (testing "index out of range"
      (is (= t (tape/apply-compaction-at t 5 "λ")))
      (is (= t (tape/apply-compaction-at t 1 "λ"))))
    (testing "negative index"
      (is (= t (tape/apply-compaction-at t -1 "λ"))))
    (testing "non-assistant role"
      (let [tu (tape/append-user [] "hi")]
        (is (= tu (tape/apply-compaction-at tu 0 "λ")))))
    (testing "already compacted"
      (let [tc (update t 0 assoc :compacted? true)]
        (is (= tc (tape/apply-compaction-at tc 0 "λ")))))
    (testing "already declined"
      (let [td (update t 0 assoc :declined? true)]
        (is (= td (tape/apply-compaction-at td 0 "λ")))))
    (testing "non-integer index"
      (is (= t (tape/apply-compaction-at t 0.5 "λ")))
      (is (= t (tape/apply-compaction-at t "0" "λ")))
      (is (= t (tape/apply-compaction-at t nil "λ"))))))

(deftest normalize-lambda-through-apply-test
  (testing "leading \"λ: \" is stripped"
    (let [t (tape/append-assistant [] "original")
          t' (tape/apply-compaction-at t 0 "λ: the answer")]
      (is (= "the answer" (:text (nth t' 0))))))
  (testing "whitespace trimmed"
    (let [t (tape/append-assistant [] "original")
          t' (tape/apply-compaction-at t 0 "   spaced out   ")]
      (is (= "spaced out" (:text (nth t' 0)))))))

(deftest apply-compaction-test
  (testing "applies at next-to-compact (k-derived)"
    (let [t (turns [:assistant :assistant :assistant])
          t' (tape/apply-compaction t 1 "λ0")]
      (is (= "λ0" (:text (nth t' 0))))
      (is (true? (:compacted? (nth t' 0))))))
  (testing "no-op when nothing is due"
    (let [t (turns [:assistant])]
      (is (= t (tape/apply-compaction t 1 "λ"))))))

;; ── declined-count / compact-target-text ─────────────────────────────────────

(deftest declined-count-test
  (is (= 0 (tape/declined-count (turns [:assistant :assistant]))))
  (let [t (-> (turns [:assistant :assistant])
              (update 0 assoc :declined? true))]
    (is (= 1 (tape/declined-count t)))))

(deftest compact-target-text-test
  (testing "the verbatim text of the message due for compaction"
    (let [t (turns [:assistant :assistant])]
      (is (= "a0" (tape/compact-target-text t 1)))))
  (testing "nil when nothing is due"
    (is (nil? (tape/compact-target-text (turns [:assistant]) 1)))))

;; ── fold-split ────────────────────────────────────────────────────────────────

(deftest fold-split-too-short-test
  (testing "fewer than k+1 assistant messages ⇒ nothing to fold"
    (let [t (turns [:user :assistant :user :assistant])]
      (is (= {:head [] :tail t} (tape/fold-split t 2))))))

(deftest fold-split-test
  (testing "tail starts at the k-th-from-last assistant message"
    ;; a-idxs [1 3 5]; k=1: k-th-from-last assistant = idx 5;
    ;; predecessor (idx 4) is :user → extend
    (let [t (turns [:user :assistant :user :assistant :user :assistant])
          {:keys [head tail]} (tape/fold-split t 1)]
      (is (= (subvec t 0 4) head))
      (is (= (subvec t 4) tail))))
  (testing "extended to include the prompting user turn when immediate predecessor is user"
    ;; a-idxs [0 2 4]; k=2: k-th-from-last assistant = idx 2;
    ;; predecessor (idx 1) is :user → extend to 1
    (let [t (turns [:assistant :user :assistant :user :assistant])
          {:keys [head tail]} (tape/fold-split t 2)]
      (is (= (subvec t 0 1) head))
      (is (= (subvec t 1) tail))))
  (testing "head+tail reassemble to the original tape"
    (let [t (turns [:user :assistant :user :assistant :user :assistant :user :assistant])
          {:keys [head tail]} (tape/fold-split t 2)]
      (is (= t (into head tail))))))

;; ── fold-input ────────────────────────────────────────────────────────────────

(deftest fold-input-test
  (testing "role-tagged dialogue lines"
    (let [head [{:role :user :text "hello"} {:role :assistant :text "hi there"}]]
      (is (= "user: hello\nassistant: hi there" (tape/fold-input head)))))
  (testing "empty head → empty string"
    (is (= "" (tape/fold-input [])))))

;; ── apply-fold ────────────────────────────────────────────────────────────────

(deftest apply-fold-accept-test
  (testing "strictly shorter block accepted; fold block first, :compacted?,
            session-id in header"
    (let [long-text (apply str (repeat 200 "z"))
          t (-> [] (tape/append-user "u0") (tape/append-assistant long-text)
                (tape/append-user "u1") (tape/append-assistant "a1")
                (tape/append-user "u2") (tape/append-assistant "a2"))
          {:keys [messages folded?]} (tape/apply-fold t 1 "sess-1" "a short λ")]
      (is (true? folded?))
      (is (true? (:compacted? (first messages))))
      (is (re-find #"^session\(sess-1\) ⊢" (:text (first messages))))
      (is (re-find #"a short λ$" (:text (first messages)))))))

(deftest apply-fold-reject-blank-lambda-test
  (let [long-text (apply str (repeat 200 "z"))
        t (-> [] (tape/append-user "u0") (tape/append-assistant long-text)
              (tape/append-user "u1") (tape/append-assistant "a1"))
        {:keys [messages folded?]} (tape/apply-fold t 1 "sess-1" "")]
    (is (false? folded?))
    (is (= t messages))))

(deftest apply-fold-reject-lambda-marker-only-test
  (testing "a \"λ:\"-only λ normalizes to blank and is rejected"
    (let [long-text (apply str (repeat 200 "z"))
          t (-> [] (tape/append-user "u0") (tape/append-assistant long-text)
                (tape/append-user "u1") (tape/append-assistant "a1"))
          {:keys [messages folded?]} (tape/apply-fold t 1 "sess-1" "λ:   ")]
      (is (false? folded?))
      (is (= t messages)))))

(deftest apply-fold-reject-not-shorter-test
  (testing "block not strictly shorter than head text → rejected"
    (let [t (-> [] (tape/append-user "u0") (tape/append-assistant "hi")
                (tape/append-user "u1") (tape/append-assistant "a1"))
          long-lambda (apply str (repeat 200 "y"))
          {:keys [messages folded?]} (tape/apply-fold t 1 "sess-1" long-lambda)]
      (is (false? folded?))
      (is (= t messages)))))

(deftest apply-fold-reject-too-short-session-test
  (testing "too-short session (nothing to fold) → rejected"
    (let [t (turns [:user :assistant])
          {:keys [messages folded?]} (tape/apply-fold t 2 "sess-1" "a λ")]
      (is (false? folded?))
      (is (= t messages)))))
