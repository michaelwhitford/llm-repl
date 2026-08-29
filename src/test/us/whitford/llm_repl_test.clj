(ns us.whitford.llm-repl-test
  "The D2/D5/D1 regression locks for the `api` layer (mementum/knowledge/
   design/architecture.md §§ D1 D2 D5) — formerly core-test.clj, mechanically
   re-pointed at `us.whitford.llm-repl` (refactor step 4: core.clj ceases to
   exist). Every driver injects a STUB `:complete-fn` — (fn [config slug]
   (fn [tape] → reply-text)), the actual injected-IO contract — never a real
   backend. The deterministic race lock is the centerpiece: a stub that
   itself mutates the registry mid-completion, simulating the live-confirmed
   v0.2.0 bug (a concurrent write silently clobbered by store!) WITHOUT
   threads or sleeps — no flakiness possible."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [escapement.llm.protocol :as proto]
   [escapement.protocols :as eproto]
   [escapement.storage.memory :as mem]
   [us.whitford.llm-repl :as repl]
   [us.whitford.llm-repl.completion :as completion]
   [us.whitford.llm-repl.registry :as registry]
   [us.whitford.llm-repl.tape :as tape]
   [us.whitford.llm-repl.trace :as trace]))

(defn- reset-registry-fixture
  [f]
  (trace/close!)                 ; trace state is global — never leak an install
  (reset! registry/sessions* {})
  (registry/reset-events!)
  (reset! registry/version* 0)
  (f)
  (trace/close!))

(use-fixtures :each reset-registry-fixture)

;; ── stub complete-fns ────────────────────────────────────────────────────

(defn- stub-complete
  "The plainest stub: config ⊕ slug → (fn [tape] → \"reply\"), ignoring both."
  [_config _slug]
  (fn [_tape] "reply"))

(defn- throwing-complete
  [_config _slug]
  (fn [_tape] (throw (ex-info "backend unreachable" {}))))

(defn- boom-backend
  "A backend whose every send throws — drives the REAL completion path (no
   injected :complete-fn) so the trace seam actually runs."
  [t]
  (reify proto/LLMBackend
    (send-turn [_ _] (throw t))))

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
  (let [result (repl/eval! :s "hi" {:complete-fn stub-complete})
        tape'  (:tape (repl/snapshot :s))]
    (testing "tape gains a user turn then an assistant turn"
      (is (= [:user :assistant] (mapv :role tape')))
      (is (= "hi" (:text (first tape'))))
      (is (= "reply" (:text (second tape')))))
    (testing ":turns derived ≡ assistant count"
      (is (= 1 (:turns (repl/snapshot :s)))))
    (testing "result shape"
      (is (= {:repl/id :s :repl/reply "reply" :repl/depth 2 :repl/turns 1}
             (select-keys result [:repl/id :repl/reply :repl/depth :repl/turns]))))))

;; ── eval! completion throw — persist-first, retry-safe ──────────────────

(deftest eval!-completion-throw-retains-user-turn-test
  (let [result (repl/eval! :s2 "hi" {:complete-fn throwing-complete})
        tape'  (:tape (repl/snapshot :s2))]
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
  (repl/open! :race)
  (let [result (repl/eval! :race "probe" {:complete-fn interloping-complete})
        tape'  (:tape (repl/snapshot :race))
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
             (:turns (repl/snapshot :race)))))))

;; ── fork! TOCTOU ──────────────────────────────────────────────────────────

(deftest fork!-missing-from-test
  (let [r (repl/fork! :nope :dest)]
    (is (some? (:repl/error r)))))

(deftest fork!-existing-to-test
  (repl/open! :src)
  (repl/open! :dest)
  (let [r (repl/fork! :src :dest)]
    (is (some? (:repl/error r)))))

(deftest fork!-happy-truncation-derives-turns-test
  (repl/eval! :src2 "a" {:complete-fn stub-complete})
  (repl/eval! :src2 "b" {:complete-fn stub-complete})
  (let [r         (repl/fork! :src2 :dest2 {:at 2})
        dest-sess (repl/snapshot :dest2)]
    (is (nil? (:repl/error r)))
    (is (= 2 (count (:tape dest-sess))) "truncated to the first 2 messages")
    (is (= 1 (:turns dest-sess)) ":turns re-derived from the TRUNCATED tape")))

;; ── open! idempotent get-or-create; drop!/reset-all! ────────────────────

(deftest open!-idempotent-and-config-merge-test
  (repl/open! :o {::repl/temperature 0.1})
  (repl/open! :o {::repl/temperature 0.9})
  (is (= 0.9 (get-in (repl/snapshot :o) [:config ::repl/temperature])) "reopen merges config")
  (is (= 1 (count (filter #(= :open! (:kind %)) @registry/events*)))
      "only ONE open! event fired — the reopen was a merge, not a creation"))

(deftest sessions-list-shape-lock-test
  (testing "PUBLIC surface (library-contract): a VECTOR of compact maps, no
            message bodies — the shape is registry/index's, flattened here,
            and it must not drift when the TUI's wire payload changes"
    (repl/open! :sl {::repl/model "m1"})
    (repl/eval! :sl "hello" {:complete-fn stub-complete})
    (let [[s :as all] (repl/sessions-list)]
      (is (vector? all))
      (is (= 1 (count all)))
      (is (= #{:slug :model :preamble? :depth :turns :forked-from :forked-at}
             (set (keys s))))
      (is (= [:sl "m1" 2 1] [(:slug s) (:model s) (:depth s) (:turns s)]))
      (is (not (str/includes? (pr-str all) "hello")) "no message text on this surface")))
  (testing "≡ the vals of registry/index — ONE definition of the projection"
    (is (= (vec (vals (registry/index @registry/sessions*)))
           (repl/sessions-list)))))

(deftest drop!-and-reset-all!-test
  (repl/open! :d1)
  (testing "drop! reports existence"
    (is (true? (repl/drop! :d1)))
    (is (false? (repl/drop! :d1))))
  (repl/open! :r1)
  (repl/open! :r2)
  (repl/reset-all!)
  (is (= {} @registry/sessions*)))

;; ── run-battery! ──────────────────────────────────────────────────────────

(deftest run-battery!-stub-test
  (let [r     (repl/run-battery! :b ["p1" "p2"] {:complete-fn stub-complete})
        tape' (:tape (repl/snapshot :b))]
    (is (= 4 (count tape')) "2 probes × (user+assistant)")
    (is (= 2 (:turns (repl/snapshot :b))))
    (is (= 2 (:repl/turns r)))))

(deftest run-battery!-raced-test
  (repl/open! :rb)
  (let [r     (repl/run-battery! :rb ["p1" "p2"] {:complete-fn mid-fold-interloping-complete})
        tape' (:tape (repl/snapshot :rb))
        texts (mapv :text tape')]
    (testing "the interloper's turn AND both battery replies all land"
      (is (some #{"battery-interloper"} texts))
      (is (= 3 (count (filter #(= :assistant (:role %)) tape')))))
    (testing "a raced event was emitted"
      (is (some #(= :raced (:kind %)) @registry/events*)))
    (testing ":turns still correct — derived from the final tape"
      (is (= (count (filter #(= :assistant (:role %)) tape'))
             (:turns (repl/snapshot :rb))))
      (is (= (:turns (repl/snapshot :rb)) (:repl/turns r))))))

;; ── compact! — D1: the one true write ────────────────────────────────────

(deftest compact!-accept-test
  (repl/eval! :cx "hi" {:complete-fn (fn [_ _] (fn [_] (apply str (repeat 200 "x"))))})
  (let [before  (:text (get-in (repl/snapshot :cx) [:tape 1]))
        r       (repl/compact! :cx 1 "λ essence")
        session (repl/snapshot :cx)
        msg     (get-in session [:tape 1])]
    (testing "outcome accepted, positive savings"
      (is (= :accepted (:repl/outcome r)))
      (is (pos? (:repl/saved r)))
      (is (= (- (count before) (count "λ essence")) (:repl/saved r))))
    (testing "the tape entry at i carries the λ text, :original, :compacted?"
      (is (= "λ essence" (:text msg)))
      (is (= before (:original msg)))
      (is (true? (:compacted? msg))))
    (testing "a {:kind :compact!} event was emitted"
      (is (some #(= :compact! (:kind %)) @registry/events*)))))

(deftest compact!-decline-test
  (repl/eval! :cd "hi" {:complete-fn (fn [_ _] (fn [_] "short"))})
  (let [before (:text (get-in (repl/snapshot :cd) [:tape 1]))
        ;; 4-arity floor kept tiny — a λ well past a floor of 1 declines
        r      (repl/compact! :cd 1 "this lambda is way past the tiny ceiling" 1)
        msg    (get-in (repl/snapshot :cd) [:tape 1])]
    (testing "outcome declined, text untouched"
      (is (= :declined (:repl/outcome r)))
      (is (= before (:text msg)))
      (is (true? (:declined? msg))))
    (testing "a {:kind :compact!} event was emitted"
      (is (some #(= :compact! (:kind %)) @registry/events*)))))

(deftest compact!-no-op-bad-index-test
  (repl/eval! :cn "hi" {:complete-fn stub-complete})
  (let [r (repl/compact! :cn 99 "λ")]
    (is (= :no-op (:repl/outcome r)))
    (is (some #(= :compact! (:kind %)) @registry/events*))))

(deftest compact!-no-op-user-role-test
  (repl/eval! :cu "hi" {:complete-fn stub-complete})
  ;; index 0 is the USER turn — compaction only ever targets assistant turns
  (let [r (repl/compact! :cu 0 "λ")]
    (is (= :no-op (:repl/outcome r)))))

(deftest compact!-no-op-already-settled-test
  (repl/eval! :ca "hi" {:complete-fn (fn [_ _] (fn [_] (apply str (repeat 200 "x"))))})
  (repl/compact! :ca 1 "λ essence")
  (testing "a second attempt on an already-compacted message no-ops"
    (let [r (repl/compact! :ca 1 "another λ")]
      (is (= :no-op (:repl/outcome r)))
      (is (= "λ essence" (:text (get-in (repl/snapshot :ca) [:tape 1])))
          "the first accepted λ stays — the second attempt changed nothing"))))

(deftest compact!-missing-session-test
  (let [r (repl/compact! :nope 0 "λ")]
    (is (some? (:repl/error r)))
    (is (nil? (:repl/outcome r)))))

(deftest compact!-race-stability-test
  (repl/eval! :cr "hi" {:complete-fn (fn [_ _] (fn [_] (apply str (repeat 200 "x"))))})
  ;; index 1 (the assistant turn) computed HERE — then more turns land on
  ;; the tape BEFORE compact! runs, simulating a concurrent eval! (D1's
  ;; explicit-index rationale: appends are end-only, so index 1 still names
  ;; the SAME message no matter what appended after it)
  (let [i (dec (count (:tape (repl/snapshot :cr))))]
    (repl/eval! :cr "again" {:complete-fn stub-complete})
    (repl/eval! :cr "again again" {:complete-fn stub-complete})
    (let [r   (repl/compact! :cr i "λ essence")
          msg (get-in (repl/snapshot :cr) [:tape i])]
      (is (= :accepted (:repl/outcome r)))
      (is (= "λ essence" (:text msg)))
      (is (= :assistant (:role msg)) "still the same, originally-targeted message"))))

;; ── parse-submission — D5: the one grammar ───────────────────────────────

(deftest parse-submission-quit-test
  (is (= {:kind :quit} (repl/parse-submission ":q")))
  (is (= {:kind :quit} (repl/parse-submission nil))))

(deftest parse-submission-noop-test
  (is (= {:kind :noop} (repl/parse-submission "")))
  (is (= {:kind :noop} (repl/parse-submission "   "))))

(deftest parse-submission-form-test
  (is (= {:kind :form :text "(+ 1 2)"} (repl/parse-submission "(+ 1 2)")))
  (is (= {:kind :form :text "  (form)"} (repl/parse-submission "  (form)"))
      "leading whitespace before the paren still counts as a form"))

(deftest parse-submission-chat-test
  (is (= {:kind :chat :text "hello world"} (repl/parse-submission "hello world"))))

;; ── variant-slug — D5: the ab! child-naming convention ───────────────────

(deftest variant-slug-test
  (is (= :s-bare (repl/variant-slug :s :bare))))

(deftest ab!-children-named-via-variant-slug-test
  (repl/open! :base)
  (let [r (repl/ab! :base {:bare {} :armed {::repl/tools true}} "probe" {:complete-fn stub-complete})]
    (is (= #{:bare :armed} (set (keys (:repl/variants r)))))
    (is (some? (repl/snapshot (repl/variant-slug :base :bare))))
    (is (some? (repl/snapshot (repl/variant-slug :base :armed))))
    (is (= :base-bare (repl/variant-slug :base :bare)))
    (is (= :base-armed (repl/variant-slug :base :armed)))))

;; ── trace integration at the driver grain (design/trace-durability.md) ───
;;
;; The capture seam lives in `completion` (its own tests cover blob shapes);
;; these lock the API-layer contracts: seed at open!, :io/ref on ✓ (and its
;; honesty gate), the tapeless drivers' receipt-only rule, compact!'s durable
;; original, and the tape.edn snapshot riding every driver commit.

(defn- install-trace! []
  (let [store (mem/new-store)]
    (trace/install! {:store store})
    store))

(defn- read-blob [store path]
  (some-> (eproto/read-artifact store "main" path) edn/read-string))

(defn- stub-backend
  "Minimal LLMBackend for driving the REAL default-complete path (capture
   rides inside it — an injected :complete-fn would bypass the seam)."
  [reply]
  (reify proto/LLMBackend
    (send-turn [_ _request]
      {:stop-reason :end_turn :content [{:type :text :text reply}]
       :usage {} :model "stub"})))

(deftest open!-captures-seed-test
  (let [store (install-trace!)]
    (repl/open! :s {::repl/model :m})
    (testing "creation writes the replayable seed (config ⊕ birth metadata)"
      (let [seed (read-blob store "nodes/s/1/seed.edn")]
        (is (= :s (:slug seed)))
        (is (= :m (get-in seed [:config ::repl/model])))))
    (testing "re-open! does not re-seed (creation only)"
      (eproto/write-artifact! store "main" "nodes/s/1/seed.edn" "sentinel" {})
      (repl/open! :s {::repl/temperature 0.5})
      (is (= "sentinel" (eproto/read-artifact store "main" "nodes/s/1/seed.edn"))))))

(deftest eval!-default-path-captures-and-refs-test
  (let [store (install-trace!)]
    (with-redefs [completion/session-backend (fn [_ _] (stub-backend "yo"))]
      (repl/eval! :s "hi" {::repl/model :m ::repl/preamble? false ::repl/system nil}))
    (testing "response blob landed at the assistant's tape index (1)"
      (is (= "yo" (-> (read-blob store "nodes/s/1/turns/1/response.edn")
                      :content first :text))))
    (testing "the ✓ receipt carries :io/ref pointing at it"
      (is (some #(and (= :eval! (:kind %))
                      (str/starts-with? (str (:msg %)) "✓")
                      (= "nodes/s/1/turns/1/response.edn" (:io/ref %)))
                @registry/events*)))
    (testing "the tape.edn snapshot followed the commit (depth 2, full map)"
      (let [snap (read-blob store "nodes/s/1/tape.edn")]
        (is (= 2 (count (:tape snap))))
        (is (contains? snap :config))))))

(deftest eval!-injected-complete-fn-no-ref-test
  (install-trace!)
  (repl/eval! :s "hi" {:complete-fn stub-complete})
  (testing "an injected :complete-fn bypasses the capture seam — the ✓
            receipt must NOT claim a ref to a blob that never landed"
    (let [ev (last (filter #(and (= :eval! (:kind %))
                                 (str/starts-with? (str (:msg %)) "✓"))
                           @registry/events*))]
      (is (some? ev))
      (is (not (contains? ev :io/ref))))))

(deftest tapeless-drivers-receipt-only-test
  (let [store (install-trace!)]
    (repl/open! :s)
    (with-redefs [completion/session-backend (fn [_ _] (stub-backend "out"))]
      (repl/bounce! :s "probe" {::repl/model :m ::repl/preamble? false ::repl/system nil})
      (repl/trampoline! :s ["p1" "p2"] {::repl/model :m ::repl/preamble? false ::repl/system nil}))
    (testing "no request/response blobs from tapeless sends (human-decided:
              colliding turn numbers — the receipt stream is their trace)"
      (is (nil? (read-blob store "nodes/s/1/turns/1/request.edn")))
      (is (nil? (read-blob store "nodes/s/1/turns/1/response.edn"))))
    (testing "the receipts exist all the same"
      (is (some #(= :bounce! (:kind %)) @registry/events*))
      (is (some #(= :tramp! (:kind %)) @registry/events*)))))

(deftest tapeless-FAILURE-captures-and-receipt-carries-the-ref-test
  (let [store (install-trace!)
        boom  (ex-info "llama.cpp API error: HTTP 400" {:status 400})]
    (repl/open! :s)
    (let [r (with-redefs [completion/session-backend (fn [_ _] (boom-backend boom))]
              (repl/bounce! :s "probe" {::repl/model :m ::repl/preamble? false ::repl/system nil}))]
      (testing "error-as-data unchanged — drivers still never throw"
        (is (= "send failed: llama.cpp API error: HTTP 400" (:repl/error r))))
      (testing "the exception to receipt-only: a FAILED tapeless send commits
                nothing, so no turn number can collide — it captures"
        (let [e (last (filter #(= :bounce! (:kind %)) @registry/events*))]
          (is (str/includes? (:msg e) "✗"))
          (testing "and the ✗ receipt POINTS AT the payload (the whole ticket:
                    it used to name the failure and nothing else)"
            (is (re-matches #"nodes/s/1/failures/\d+-\d+\.edn" (:io/ref e)))
            (let [blob (read-blob store (:io/ref e))]
              (is (some? (:messages (:request blob))) "the request that failed")
              (is (= {:status 400} (:ex-data blob))))))))))

(deftest trampoline-failures-each-keep-their-own-file-test
  (let [store (install-trace!)
        r     (do (repl/open! :s)
                  (with-redefs [completion/session-backend
                                (fn [_ _] (boom-backend (ex-info "down" {})))]
                    (repl/trampoline! :s ["p1" "p2" "p3"]
                                      {::repl/model :m ::repl/preamble? false ::repl/system nil})))
        refs  (mapv :io/ref (:repl/bounces r))]
    (testing "a fan-out over a down backend fails N times inside the same
              millisecond — N distinct files, none overwritten"
      (is (= 3 (count (distinct refs))))
      (is (every? #(some? (read-blob store %)) refs)))
    (testing "per-bounce error-as-data survives alongside the refs"
      (is (every? #(str/includes? (:error %) "down") (:repl/bounces r))))))

(deftest compact!-captures-durable-original-test
  (let [store (install-trace!)
        long-reply (apply str (repeat 200 "x"))]
    (repl/eval! :cx "hi" {:complete-fn (fn [_ _] (fn [_] long-reply))})
    (repl/compact! :cx 1 "λ essence")
    (testing "the pre-compaction MESSAGE captured at turns/<i>/original.edn —
              the arm-diff ground truth that outlives the registry (Q4)"
      (let [blob (read-blob store "nodes/cx/1/turns/1/original.edn")]
        (is (= long-reply (:text blob)))
        (is (= :assistant (:role blob)))))
    (testing "the on-tape :original still agrees (twin copies)"
      (is (= long-reply (get-in (repl/snapshot :cx) [:tape 1 :original]))))))

(deftest drop!-tombstones-snapshot-test
  (let [store (install-trace!)]
    (repl/eval! :s "hi" {:complete-fn stub-complete})
    (repl/drop! :s)
    (testing "drop! → tombstone (recovery must not resurrect it)"
      (is (:trace/dropped (read-blob store "nodes/s/1/tape.edn"))))))

;; ── unset! — the sticky config's release valve (D7 amendment) ─────────────

(deftest unset!-seeded-knobs-reseed-test
  (testing "unsetting a default-seeded knob RE-SEEDS from the live
            (default-config) — bare dissoc would mint new poison (:model
            absent ≡ broken send; :tools absent ≡ none, not default)"
    (repl/open! :u {::repl/tools [:clojure/eval] ::repl/temperature 0.9 ::repl/model :poison/model})
    (let [r (repl/unset! :u ::repl/tools ::repl/temperature ::repl/model)
          d (repl/default-config)
          c (:config (repl/snapshot :u))]
      (is (= [::repl/tools ::repl/temperature ::repl/model] (:repl/unset r)))
      (is (= (::repl/tools d) (::repl/tools c)) "config default governs again")
      (is (= (::repl/temperature d) (::repl/temperature c)))
      (is (= (::repl/model d) (::repl/model c)) "the poison model override is gone")
      (is (contains? c ::repl/model) "seeded keys stay PRESENT — never dissoc'd"))))

(deftest unset!-prompt-keys-dissoc-test
  (testing "unsetting a prompt-stack key DISSOCs — the D7 request-time chain
            resumes (session > model > provider > root)"
    (repl/open! :u {::repl/system "custom voice" ::repl/preamble false})
    (repl/unset! :u ::repl/system ::repl/preamble)
    (let [c (:config (repl/snapshot :u))]
      (is (not (contains? c ::repl/system)))
      (is (not (contains? c ::repl/preamble)))))
  (testing "present-nil is NOT unset — the two semantics are OPPOSITES (nil
            STOPS the chain ≡ explicitly none; unset RESUMES it)"
    (repl/open! :u2 {::repl/system nil})
    (is (contains? (:config (repl/snapshot :u2)) ::repl/system)
        "open! preserved the explicit none; only unset! removes it")))

(deftest unset!-errors-as-data-test
  (testing "commands return errors as DATA — the return is read (D9 idiom)"
    (repl/open! :u {})
    (is (str/includes? (:repl/error (repl/unset! :u :bogus)) "unknown config key")
        "unknown key names itself")
    (is (str/includes? (:repl/error (repl/unset! :u :bogus)) "orientation")
        "…and teaches the unsettable set")
    (is (str/includes? (:repl/error (repl/unset! :u)) "at least one key"))
    (is (= "no such repl session: :ghost" (:repl/error (repl/unset! :ghost ::repl/tools))))))

(deftest unset!-receipt-and-idempotence-test
  (testing "one loud receipt per unset!; unsetting an already-default key is
            harmless (still receipted — the caller asked, the caller hears)"
    (repl/open! :u {})
    (repl/unset! :u ::repl/temperature)
    (repl/unset! :u ::repl/temperature)
    (is (= 2 (count (filter #(= :unset! (:kind %)) @registry/events*))))
    (is (= (::repl/temperature (repl/default-config))
           (::repl/temperature (:config (repl/snapshot :u)))))))

;; ── register-manual-ns! — guarded (D9 registration-guards) ────────────────

(deftest register-manual-ns!-guards-test
  (testing "a typo'd/unloaded ns → loud throw naming it — the silent version
            'succeeded' quietly and broke (help)/(manual) for EVERY caller
            later, far from the cause (ns-publics on nil)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such LOADED"
                          (repl/register-manual-ns! 'no.such.namespace-typo)))
    (try (repl/register-manual-ns! 'no.such.namespace-typo)
         (catch clojure.lang.ExceptionInfo e
           (is (= {:ns 'no.such.namespace-typo :loaded? false} (:errors (ex-data e))))))
    (is (not-any? #{'no.such.namespace-typo} @repl/manual-namespaces*)
        "nothing landed in the compile set"))
  (testing "a non-symbol → loud throw (find-ns would ClassCastException
            confusingly; this names the contract instead)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expected\s+.*a symbol"
                          (repl/register-manual-ns! "a-string"))))
  (testing "a loaded ns registers, idempotently — (manual) still compiles"
    (let [before @repl/manual-namespaces*]
      (try
        (repl/register-manual-ns! 'us.whitford.llm-repl.tape)
        (repl/register-manual-ns! 'us.whitford.llm-repl.tape)
        (is (= 1 (count (filter #{'us.whitford.llm-repl.tape}
                                @repl/manual-namespaces*))))
        (is (vector? (repl/manual)) "the manual still compiles")
        (finally (reset! repl/manual-namespaces* before))))))
