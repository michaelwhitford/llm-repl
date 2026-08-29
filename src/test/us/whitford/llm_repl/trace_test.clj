(ns us.whitford.llm-repl.trace-test
  "Regression locks for the trace ns (design/trace-durability.md) — capture,
   tape snapshots via the registry taps, transcript emission, recovery with
   receipt-and-skip (ratified Q2), and the loud-failure posture. All on
   escapement's MEMORY store — filesystem-free (the disk round-trip twin
   test lives with the daemon wiring)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [escapement.protocols :as eproto]
   [escapement.storage.memory :as mem]
   [us.whitford.llm-repl.registry :as registry]
   [us.whitford.llm-repl.trace :as trace]))

(defn- clean-fixture
  "Registry reset ⊕ trace close on BOTH sides — trace state and taps are
   defonce'd globals; a leaked install would couple tests."
  [f]
  (trace/close!)
  (trace/ring-reset!)
  (reset! registry/sessions* {})
  (registry/reset-events!)
  (reset! registry/version* 0)
  (f)
  (trace/close!)
  (trace/ring-reset!))

(use-fixtures :each clean-fixture)

(defn- install-mem!
  "Memory-store trace runtime; returns {:store :events} — :events ≡ the
   transcript collector atom (what production's JSONL sink receives)."
  ([] (install-mem! {}))
  ([opts]
   (let [store     (mem/new-store)
         collected (atom [])]
     (trace/install! (merge {:store store
                             :transcript-fn #(swap! collected conj %)}
                            opts))
     {:store store :events collected})))

(defn- read-edn [store path]
  (some-> (eproto/read-artifact store "main" path) edn/read-string))

;; ── disabled ≡ total no-op ────────────────────────────────────────────────

(deftest disabled-no-ops-test
  (testing "every trace fn is nil-safe and silent when never installed"
    (is (not (trace/enabled?)))
    (is (nil? (trace/capture! :s 4 "response" {:a 1} "snip")))
    (is (nil? (trace/request! :s 4 {:messages []} "snip")))
    (is (nil? (trace/seed! :s {:slug :s})))
    (is (nil? (trace/tape! :s {:slug :s :tape []})))
    (is (nil? (trace/ref-for :s 4 "response")))
    (is (nil? (trace/failure! :s {:m 1} (ex-info "boom" {}))))
    (is (nil? (trace/receipt! {:kind :note :msg "x"})))
    (is (nil? (trace/recover!)))
    (is (empty? @registry/events*) "no receipts from disabled no-ops")))

;; ── capture ───────────────────────────────────────────────────────────────

(deftest capture-writes-blob-test
  (let [{:keys [store]} (install-mem!)
        ref (trace/capture! :s 4 "response" {:content [{:type :text :text "hi"}]} "hi")]
    (testing "ref shape ∧ keyword slug encodes correctly (runtime-pinned:
              a STRING node-id would silently lose its first char)"
      (is (= "nodes/s/1/turns/4/response.edn" (:io/ref ref)))
      (is (= "hi" (:io/snippet ref))))
    (testing "blob round-trips as EDN"
      (is (= {:content [{:type :text :text "hi"}]}
             (read-edn store (:io/ref ref)))))
    (testing "ref-for computes the same locator, pure"
      (is (= (:io/ref ref) (trace/ref-for :s 4 "response"))))))

(deftest capture-overwrite-vs-request-first-write-test
  (let [{:keys [store]} (install-mem!)]
    (testing "blobs OVERWRITE (last wins)"
      (trace/capture! :s 2 "response" {:v 1} "one")
      (trace/capture! :s 2 "response" {:v 2} "two")
      (is (= {:v 2} (read-edn store "nodes/s/1/turns/2/response.edn"))))
    (testing "requests are FIRST-write-wins (continuations never clobber)"
      (trace/request! :s 2 {:messages [:base]} "base")
      (trace/request! :s 2 {:messages [:cont]} "cont")
      (is (= {:messages [:base]} (read-edn store "nodes/s/1/turns/2/request.edn"))))))

(deftest capture-gate-test
  (let [{:keys [store]} (install-mem!)]
    (testing "*capture?* false (the tapeless-driver binding) suppresses
              request/response blobs…"
      (binding [trace/*capture?* false]
        (is (nil? (trace/capture! :s 3 "response" {:v 1} "x")))
        (is (nil? (trace/request! :s 3 {:m 1} "x"))))
      (is (nil? (read-edn store "nodes/s/1/turns/3/response.edn"))))
    (testing "…but NOT seeds or tape snapshots (session existence ∧
              persistence are not send-scoped)"
      (binding [trace/*capture?* false]
        (trace/seed! :s {:slug :s :config {}})
        (trace/tape! :s {:slug :s :tape []}))
      (is (some? (read-edn store "nodes/s/1/seed.edn")))
      (is (some? (read-edn store "nodes/s/1/tape.edn"))))))

;; ── failures: the ONE capture the tapeless drivers still make ─────────────

(deftest failure-capture-is-ungated-test
  (let [{:keys [store]} (install-mem!)
        ref (binding [trace/*capture?* false]
              (trace/failure! :s {:messages [:probe]}
                              (ex-info "HTTP 400" {:status 400})))]
    (testing "captures INSIDE the tapeless binding — the amendment: a failed
              send commits nothing, so it needs no tape index to collide on"
      (is (some? ref))
      (is (re-matches #"nodes/s/1/failures/\d+-\d+\.edn" (:io/ref ref))))
    (testing "the artifact carries what the ✗ receipt could not name"
      (let [blob (read-edn store (:io/ref ref))]
        (is (= {:messages [:probe]} (:request blob)) "the payload")
        (is (= "HTTP 400" (:error blob)))
        (is (= {:status 400} (:ex-data blob)))
        (is (int? (:at blob)))
        (is (str/includes? (:ex-type blob) "ExceptionInfo"))))))

(deftest failure-files-never-collide-test
  (let [{:keys [store]} (install-mem!)
        refs (mapv #(:io/ref (trace/failure! :s {:i %} (ex-info "boom" {})))
                   (range 5))]
    (testing "five failures inside one millisecond ≡ five files (blob writes
              are last-wins; the process-local seq is the tiebreaker) — a
              trampoline! fan-out over a down backend does exactly this"
      (is (= 5 (count (distinct refs))))
      (is (= #{0 1 2 3 4}
             (set (map #(:i (:request (read-edn store %))) refs)))))))

(deftest failure-degrades-unreadable-values-test
  (let [{:keys [store]} (install-mem!)
        ;; a live object: pr-str prints #object[…], read-string cannot read it
        ref  (trace/failure! :s {:sock (Object.)} (ex-info "boom" {:conn (Object.)}))
        blob (read-edn store (:io/ref ref))]
    (testing "the FILE still round-trips as EDN; only the unreadable value
              degrades to its printed form (poison the value, never the file)"
      (is (= "boom" (:error blob)))
      (is (string? (:request blob)))
      (is (string? (:ex-data blob))))))

(deftest capture-failure-is-receipt-never-throw-test
  (let [boom (reify eproto/ArtifactStore
               (write-artifact! [_ _ _ _ _] (throw (ex-info "disk gone" {})))
               (read-artifact [_ _ _] nil)
               (list-artifacts [_ _] []))]
    (trace/install! {:store boom})
    (testing "capture! returns nil and emits a {:kind :trace} receipt"
      (is (nil? (trace/capture! :s 1 "response" {:v 1} "x")))
      (let [e (last @registry/events*)]
        (is (= :trace (:kind e)))
        (is (str/includes? (:msg e) "✗"))
        (is (str/includes? (:msg e) "disk gone"))))
    (testing "tape! same posture"
      (trace/tape! :s {:slug :s :tape []})
      (is (= :trace (:kind (last @registry/events*)))))))

;; ── the registry taps ─────────────────────────────────────────────────────

(deftest mutate-tap-snapshots-changed-sessions-test
  (let [{:keys [store]} (install-mem!)]
    (registry/mutate! #(assoc % :s {:slug :s :tape [] :config {:us.whitford.llm-repl/model :m} :turns 0}))
    (testing "creation → full session map at tape.edn"
      (is (= {:slug :s :tape [] :config {:us.whitford.llm-repl/model :m} :turns 0}
             (read-edn store "nodes/s/1/tape.edn"))))
    (registry/mutate! #(update-in % [:s :tape] conj {:role :user :text "hi"}))
    (testing "change → snapshot follows (latest wins)"
      (is (= [{:role :user :text "hi"}]
             (:tape (read-edn store "nodes/s/1/tape.edn")))))
    (registry/mutate! #(dissoc % :s))
    (testing "removal → tombstone (recovery must not resurrect a drop!)"
      (is (:trace/dropped (read-edn store "nodes/s/1/tape.edn"))))))

(deftest mutate-tap-untouched-sessions-not-rewritten-test
  (let [{:keys [store]} (install-mem!)]
    (registry/mutate! #(assoc % :a {:slug :a :tape [] :config {} :turns 0}))
    ;; poison :a's snapshot, then mutate only :b — :a must NOT be rewritten
    (eproto/write-artifact! store "main" "nodes/a/1/tape.edn" "sentinel" {})
    (registry/mutate! #(assoc % :b {:slug :b :tape [] :config {} :turns 0}))
    (is (= "sentinel" (eproto/read-artifact store "main" "nodes/a/1/tape.edn"))
        "an unchanged session's snapshot was not touched")))

(deftest event-tap-feeds-transcript-test
  (let [{:keys [events]} (install-mem!)]
    (registry/event! {:kind :eval! :slug :s :msg "✓@4"})
    (registry/event! "plain note")
    (testing "every completed event map reaches the transcript fn, :id/:at on"
      (is (= 2 (count @events)))
      (is (= [:eval! :note] (mapv :kind @events)))
      (is (every? :id @events)))))

;; ── recovery (ratified Q2: auto, loud, receipt-and-skip) ──────────────────

(defn- seed-tape!
  "Write a tape.edn artifact directly (simulating a prior visit's snapshot)."
  [store node visit data]
  (eproto/write-artifact! store "main"
                          (str "nodes/" node "/" visit "/tape.edn")
                          (if (string? data) data (pr-str data))
                          {}))

(deftest recover-latest-visit-wins-test
  (let [{:keys [store]} (install-mem! {:visit 3})]
    (seed-tape! store "s" 1 {:slug :s :tape [{:role :user :text "old"}] :config {} :turns 0})
    (seed-tape! store "s" 2 {:slug :s :tape [{:role :user :text "new"}
                                             {:role :assistant :text "r"}] :config {:us.whitford.llm-repl/model :m} :turns 1})
    (trace/recover!)
    (testing "the max-visit snapshot is the one recovered, whole session map"
      (let [s (get @registry/sessions* :s)]
        (is (= 2 (count (:tape s))))
        (is (= {:us.whitford.llm-repl/model :m} (:config s)))))
    (testing "loud receipt per recovered session"
      (is (some #(and (= :recover (:kind %)) (= :s (:slug %)) (= "@2" (:msg %)))
                @registry/events*)))))

(deftest recover-receipt-and-skip-test
  (let [{:keys [store]} (install-mem! {:visit 2})]
    (seed-tape! store "good"  1 {:slug :good :tape [] :config {} :turns 0})
    (seed-tape! store "bad"   1 "{:slug :bad :tape [")          ; corrupt EDN
    (seed-tape! store "shapeless" 1 {:not-a :session})           ; parses, wrong shape
    (seed-tape! store "gone"  1 {:trace/dropped true :at 1})     ; tombstone
    (trace/recover!)
    (testing "good recovered; corrupt/shapeless/tombstone skipped — daemon
              boots DEGRADED, never refuses (human-pinned Q2)"
      (is (= #{:good} (set (keys @registry/sessions*)))))
    (testing "corrupt ∧ shapeless each emitted a ✗ recover receipt"
      (let [skips (filter #(and (= :recover (:kind %))
                                (str/includes? (str (:msg %)) "✗ skipped"))
                          @registry/events*)]
        (is (= 2 (count skips)))))
    (testing "tombstone skipped SILENTLY (intentional state, not a failure)"
      (is (not-any? #(str/includes? (str (:msg %)) "gone") @registry/events*)))))

(deftest recover-live-session-wins-test
  (let [{:keys [store]} (install-mem! {:visit 2})]
    (registry/mutate! #(assoc % :s {:slug :s :tape [{:role :user :text "live"}]
                                    :config {} :turns 0}))
    (seed-tape! store "s" 1 {:slug :s :tape [] :config {} :turns 0})
    (trace/recover!)
    (testing "an already-live slug keeps its in-memory tape (recovery is
              additive), with a receipt saying so"
      (is (= "live" (get-in @registry/sessions* [:s :tape 0 :text])))
      (is (some #(and (= :recover (:kind %))
                      (str/includes? (str (:msg %)) "already live"))
                @registry/events*)))))

(deftest recovered-session-snapshots-under-new-visit-test
  (let [{:keys [store]} (install-mem! {:visit 2})]
    (seed-tape! store "s" 1 {:slug :s :tape [] :config {} :turns 0})
    (trace/recover!)
    (testing "recovery's own mutate! re-snapshots under the CURRENT visit —
              each incarnation's tape.edn shows its own state"
      (is (some? (read-edn store "nodes/s/2/tape.edn"))))))

;; ── disk round-trip (the ONE filesystem test — bb ∧ JVM twin) ─────────────

(defn- rm-rf! [f]
  (doseq [^java.io.File x (reverse (file-seq (io/file f)))]
    (.delete x)))

(deftest disk-round-trip-two-incarnations-test
  (let [tmp (str (System/getProperty "java.io.tmpdir")
                 "/llm-repl-trace-test-" (System/currentTimeMillis))]
    (try
      ;; ── incarnation 1: init, work, die ──
      (trace/init! {:trace {:dir tmp}})
      (is (trace/enabled?))
      (testing "the licensing belt: .gitignore self-written at the work-dir"
        (is (= "*\n" (slurp (io/file tmp ".gitignore")))))
      (registry/mutate! #(assoc % :s {:slug :s
                                      :tape [{:role :user :text "hi"}
                                             {:role :assistant :text "yo"}]
                                      :config {:us.whitford.llm-repl/model :m} :turns 1}))
      (trace/capture! :s 1 "response"
                      {:content [{:type :text :text "yo"}]} "yo")
      (registry/event! {:kind :eval! :slug :s :msg "✓@2"})
      (trace/close!)
      (testing "blobs ∧ snapshot ∧ transcript are real files (atomic writes
                landed; the tree is literally walkable)"
        (is (.exists (io/file tmp "main" "nodes" "s" "1" "turns" "1" "response.edn")))
        (is (.exists (io/file tmp "main" "nodes" "s" "1" "tape.edn")))
        (is (pos? (count (str/split-lines
                          (slurp (io/file tmp "main" "transcript.jsonl")))))))

      ;; ── incarnation 2: reboot over the same dir ──
      (reset! registry/sessions* {})
      (trace/init! {:trace {:dir tmp}})
      (testing "visit seeded max+1 — a restart never overwrites prior traces"
        (is (= "nodes/s/2/turns/9/response.edn" (trace/ref-for :s 9 "response"))))
      (trace/recover!)
      (testing "the session came back whole — tape ⊕ config, immediately
                eval!-able (configuration-completeness)"
        (let [s (get @registry/sessions* :s)]
          (is (= 2 (count (:tape s))))
          (is (= {:us.whitford.llm-repl/model :m} (:config s)))
          (is (= "yo" (get-in s [:tape 1 :text])))))
      (testing "recovery was loud"
        (is (some #(and (= :recover (:kind %)) (= :s (:slug %)))
                  @registry/events*)))
      (finally
        (trace/close!)
        (rm-rf! tmp)))))

(deftest init-disabled-by-config-test
  (trace/init! {:trace {:enabled? false :dir "/nonexistent/never-created"}})
  (is (not (trace/enabled?)))
  (is (not (.exists (io/file "/nonexistent/never-created")))))

;; ── close! ────────────────────────────────────────────────────────────────

(deftest close-retracts-taps-test
  (let [{:keys [store events]} (install-mem!)]
    (trace/close!)
    (is (not (trace/enabled?)))
    (registry/event! "after close")
    (registry/mutate! #(assoc % :s {:slug :s :tape [] :config {} :turns 0}))
    (testing "no transcript emission, no snapshot after close!"
      (is (empty? @events))
      (is (nil? (eproto/read-artifact store "main" "nodes/s/1/tape.edn"))))
    (testing "idempotent"
      (is (nil? (trace/close!))))))

;; ── the send-ring (design § build decisions 7 — memory-only, RATIFIED) ────

(defn- entry [id bytes] {:id id :bytes bytes})

(deftest ring-trim-pure-test
  (testing "evicts oldest-first until within cap"
    (is (= [:b :c]
           (mapv :id (:entries (trace/ring-conj
                                {:cap 100 :bytes 90 :entries [(entry :a 50) (entry :b 40)]}
                                (entry :c 30)))))))
  (testing "bytes track evictions"
    (is (= 70 (:bytes (trace/ring-conj
                       {:cap 100 :bytes 90 :entries [(entry :a 50) (entry :b 40)]}
                       (entry :c 30))))))
  (testing "the NEWEST entry survives even oversized (cap ≡ high-water mark)"
    (let [r (trace/ring-conj {:cap 100 :bytes 0 :entries []} (entry :big 500))]
      (is (= [:big] (mapv :id (:entries r))))
      (is (= 500 (:bytes r)))))
  (testing "cap ≤ 0 ≡ off — clears"
    (is (= {:cap 0 :bytes 0 :entries []}
           (trace/ring-trim {:cap 0 :bytes 120 :entries [(entry :a 120)]})))))

(deftest ring-record-and-query-test
  (trace/ring-record! :s1 {:messages [:a]} {:response {:text "hi"}})
  (trace/ring-record! :s2 {:messages [:b]} {:error "HTTP 400"
                                            :io/ref "nodes/s2/1/failures/1-1.edn"})
  (testing "sends is newest-first; ✓ ∧ ✗ both recorded"
    (is (= [[:s2 false] [:s1 true]] (mapv (juxt :slug :ok?) (trace/sends))))
    (is (= [:s2] (mapv :slug (trace/sends 1)))))
  (testing "a ✗ entry's ref points at its own payload"
    (is (= "nodes/s2/1/failures/1-1.edn" (:io/ref (first (trace/sends))))))
  (testing "stats"
    (let [{:keys [entries bytes cap oldest-at newest-at]} (trace/ring-stats)]
      (is (= 2 entries))
      (is (pos? bytes))
      (is (= trace/default-ring-bytes cap))
      (is (<= oldest-at newest-at))))
  (testing "the ring records with trace DISABLED and *capture?* bound false —
            independence is the point (tapeless ✓ capture)"
    (is (not (trace/enabled?)))
    (binding [trace/*capture?* false]
      (trace/ring-record! :s3 {} {:response :r}))
    (is (= :s3 (:slug (first (trace/sends)))))))

(deftest ring-cap-knob-test
  (trace/ring-record! :s1 {} {:response :r})
  (testing "set-ring-cap! 0 clears ∧ disables recording"
    (trace/set-ring-cap! 0)
    (is (= [] (trace/sends)))
    (trace/ring-record! :s2 {} {:response :r})
    (is (= [] (trace/sends))))
  (testing "false ≡ off"
    (trace/set-ring-cap! false)
    (is (zero? (:cap (trace/ring-stats)))))
  (testing "init! applies :ring-bytes even when tracing is disabled"
    (trace/init! {:trace {:enabled? false :ring-bytes 1234}})
    (is (= 1234 (:cap (trace/ring-stats))))
    (is (not (trace/enabled?))))
  (testing "ring-reset! restores the default"
    (trace/ring-reset!)
    (is (= trace/default-ring-bytes (:cap (trace/ring-stats))))))
