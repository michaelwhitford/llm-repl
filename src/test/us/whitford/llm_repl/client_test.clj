(ns us.whitford.llm-repl.client-test
  "The client seam's twin suite (runs under bb AND JVM — design D6).

   Wire-free by construction: `poll-cycle!` takes its whole wire as
   `:fetch-fn`, so a scripted stub exercises the D3 protocol — long-poll
   delivery, version-gated registry fetch, the version-poll fallback — and
   the attach-loss contract (failure counting → status* :lost → cb wake-up:
   memories/tui-dead-daemon-silent) without a socket. `command-receipt?` is
   the D4 structural suppress-echo, pure."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [us.whitford.llm-repl.client :as client]))

;; ── D4 — structural suppress-echo ───────────────────────────────────────────

(deftest command-receipt-structural
  (testing "api command results — maps carrying :repl/id — suppress the echo"
    (is (client/command-receipt? (pr-str {:repl/id :s :repl/reply "hi" :repl/turns 3})))
    (is (client/command-receipt? (pr-str {:repl/id :s :repl/error "no such repl session: :s"}))))
  (testing "ordinary values echo"
    (is (not (client/command-receipt? "42")))
    (is (not (client/command-receipt? "{:a 1}")))
    (is (not (client/command-receipt? "[:repl/id :s]"))) ; vector, not a receipt map
    (is (not (client/command-receipt? "nil")))
    (is (not (client/command-receipt? nil))))
  (testing "the regex killer: the SPELLING inside a string is not a receipt"
    ;; the old #\":repl/id\" regex matched this and wrongly suppressed
    (is (not (client/command-receipt? (pr-str ":repl/id")))))
  (testing "unreadable values are not receipts (and never throw)"
    (is (not (client/command-receipt? "#object[foo 0x1 \"bar\"]")))
    (is (not (client/command-receipt? "#unknown/tag {:repl/id :s}")))))

;; ── poll-cycle! harness ─────────────────────────────────────────────────────

(defn- scripted-fetch
  "A fetch-fn stub: routes on the FIRST matching substring of the code sent
   (order matters — 'wait-for-event!' before 'version*' etc.), records every
   code string in `calls*`. Unrouted code ≡ {:err …} so a cycle asking for
   something unexpected fails the test loudly."
  [calls* routes]
  (fn [code]
    (swap! calls* conj code)
    (or (some (fn [[k r]] (when (str/includes? code k) r)) routes)
        {:err (str "unrouted fetch: " code)})))

(defn- env+
  "A poll-cycle! env over fresh atoms and a scripted fetch. Returns the env
   plus the atoms/spies for assertions."
  [routes & {:keys [ev0] :or {ev0 []}}]
  (let [calls*   (atom [])
        cb-hits* (atom 0)]
    {:env {:fetch-fn  (scripted-fetch calls* routes)
           :reg-cache (atom {})
           :ev-cache  (atom ev0)
           :status*   (atom {:attach :ok})
           :cb        #(swap! cb-hits* inc)
           :max-failures 3 :backoff-ms 1 :interval-ms 7
           :long-poll-ms 50 :events-cap 200}
     :calls* calls* :cb-hits* cb-hits*}))

(defn- sent? [calls* substr]
  (boolean (some #(str/includes? % substr) @calls*)))

;; ── D3 — long-poll mode ─────────────────────────────────────────────────────

(deftest long-poll-delivers-events-and-version-gated-registry
  (let [{:keys [env calls* cb-hits*]}
        (env+ [["wait-for-event!" {:ok [{:id 5 :kind :eval! :slug :s :msg "✓@6"}]}]
               ["version*"        {:ok 9}]
               ["sessions*"       {:ok {:s {:slug :s :turns 3}}}]])
        s' (client/poll-cycle! env {:mode :long-poll :last-version 8
                                    :since-id 4 :failures 0})]
    (is (= [{:id 5 :kind :eval! :slug :s :msg "✓@6"}] @(:ev-cache env)))
    (is (= {:s {:slug :s :turns 3}} @(:reg-cache env)))
    (is (= 5 (:since-id s')) "since-id advances to the max delivered event id")
    (is (= 9 (:last-version s')))
    (is (zero? (:failures s')))
    (is (nil? (:sleep-ms s')) "long-poll mode: the server park IS the pacing")
    (is (= 1 @cb-hits*))
    (is (sent? calls* "wait-for-event! 4 50") "parks since the caught-up id")
    (is (= {:attach :ok} @(:status* env)))))

(deftest long-poll-quiet-timeout-fetches-nothing
  (let [{:keys [env calls* cb-hits*]}
        (env+ [["wait-for-event!" {:ok []}]
               ["version*"        {:ok 8}]])
        s' (client/poll-cycle! env {:mode :long-poll :last-version 8
                                    :since-id 4 :failures 0})]
    (is (not (sent? calls* "sessions*"))
        "version unmoved → the registry never crosses the wire")
    (is (zero? @cb-hits*) "nothing changed → no repaint")
    (is (= 4 (:since-id s')))
    (is (zero? (:failures s')))))

(deftest long-poll-eventless-version-move-still-fetches-registry
  ;; the RARE case: a registry mutation with no receipt (raw swap! by an
  ;; attached client) — caught on wake by the version check
  (let [{:keys [env cb-hits*]}
        (env+ [["wait-for-event!" {:ok []}]
               ["version*"        {:ok 9}]
               ["sessions*"       {:ok {:x {:slug :x}}}]])
        s' (client/poll-cycle! env {:mode :long-poll :last-version 8
                                    :since-id 4 :failures 0})]
    (is (= {:x {:slug :x}} @(:reg-cache env)))
    (is (= 9 (:last-version s')))
    (is (= 1 @cb-hits*))))

;; ── D3 — version-poll fallback ──────────────────────────────────────────────

(deftest version-poll-fallback-rides-the-delta
  (let [{:keys [env calls* cb-hits*]}
        (env+ [["events-since" {:ok [{:id 3 :kind :note :msg "hi"}]}]
               ["version*"     {:ok 9}]
               ["sessions*"    {:ok {:s {:slug :s}}}]])
        s' (client/poll-cycle! env {:mode :version-poll :last-version 3
                                    :since-id 2 :failures 0})]
    (is (not (sent? calls* "wait-for-event!")) "fallback never parks")
    (is (sent? calls* "events-since 2") "the event tail rides the delta fn")
    (is (= [{:id 3 :kind :note :msg "hi"}] @(:ev-cache env)))
    (is (= {:s {:slug :s}} @(:reg-cache env)))
    (is (= 3 (:since-id s')))
    (is (= 7 (:sleep-ms s')) "fallback paces itself on the interval")
    (is (= 1 @cb-hits*))))

(deftest version-poll-quiet-cycle-sends-only-the-tiny-number
  (let [{:keys [env calls* cb-hits*]}
        (env+ [["version*" {:ok 3}]])
        s' (client/poll-cycle! env {:mode :version-poll :last-version 3
                                    :since-id 2 :failures 0})]
    (is (= ["@us.whitford.llm-repl.registry/version*"] @calls*)
        "quiet cycle ≡ ONE tiny fetch — the v0.2.0 serialize-everything sin is dead")
    (is (zero? @cb-hits*))
    (is (= 7 (:sleep-ms s')))))

;; ── attach-loss (memories/tui-dead-daemon-silent) ───────────────────────────

(deftest attach-loss-counts-failures-then-fails-loud
  (let [{:keys [env cb-hits*]} (env+ [["wait-for-event!" {:err "EOF"}]])
        s0 {:mode :long-poll :last-version 8 :since-id 4 :failures 0}
        s1 (client/poll-cycle! env s0)
        s2 (client/poll-cycle! env s1)]
    (testing "below the threshold: counted, backed off, still attached"
      (is (= 1 (:failures s1)))
      (is (= 2 (:failures s2)))
      (is (= 1 (:sleep-ms s2)))
      (is (not (:lost? s2)))
      (is (= {:attach :ok} @(:status* env)))
      (is (zero? @cb-hits*)))
    (testing "at the threshold: status flips, cb wakes the wire layer, loop stops"
      (let [s3 (client/poll-cycle! env s2)]
        (is (:lost? s3))
        (is (= :lost (:attach @(:status* env))))
        (is (= "EOF" (:reason @(:status* env))))
        (is (= 1 @cb-hits*) "the wake-up fires exactly once")))))

(deftest attach-loss-error-signal-is-data-not-nil
  ;; the structural fix: a dead wire is {:err …} — visibly counted — never a
  ;; nil that reads as \"no change\" (the bug: TUI rendered a dead daemon)
  (let [{:keys [env]} (env+ [["wait-for-event!" {:ok []}]
                             ["version*"        {:err "connection reset"}]])
        s' (client/poll-cycle! env {:mode :long-poll :last-version 8
                                    :since-id 4 :failures 0})]
    (is (= 1 (:failures s')))))

(deftest success-resets-the-failure-count
  (let [{:keys [env]} (env+ [["wait-for-event!" {:ok []}]
                             ["version*"        {:ok 8}]])
        s' (client/poll-cycle! env {:mode :long-poll :last-version 8
                                    :since-id 4 :failures 2})]
    (is (zero? (:failures s')) "one good cycle forgives the blips")))

;; ── ring discipline ─────────────────────────────────────────────────────────

(deftest ev-cache-respects-the-cap
  (let [ev0 (mapv (fn [i] {:id i :kind :note :msg (str i)}) (range 1 196))
        {:keys [env]}
        (env+ [["wait-for-event!" {:ok (mapv (fn [i] {:id i :kind :note :msg (str i)})
                                             (range 196 206))}]
               ["version*"        {:ok 9}]
               ["sessions*"       {:ok {}}]]
              :ev0 ev0)
        s' (client/poll-cycle! env {:mode :long-poll :last-version 8
                                    :since-id 195 :failures 0})]
    (is (= 200 (count @(:ev-cache env))) "bounded ring, same cap as the server")
    (is (= 205 (:id (peek @(:ev-cache env)))) "newest survive the trim")
    (is (= 205 (:since-id s')))))
