(ns us.whitford.llm-repl.tui.frame-test
  "The headless TUI suite D6 names (and v0.2.0 falsely claimed — the
   coherence lesson): frame/key-from-bytes/edit-step against byte vectors ∧
   registry snapshots, no terminal anywhere. Runs under bb AND JVM.

   Also holds the D5 naming lock: `short-name ∘ variant-slug ≡ identity` —
   the ONE test that lets tui.frame encode the inverse of the api ns's
   child-naming convention without requiring the api layer."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [escapement.tui.compositor :as cmp]
   [escapement.tui.theme :as theme]
   [us.whitford.llm-repl :as repl]
   [us.whitford.llm-repl.tui.frame :as frame]))

(def th (theme/theme-for (theme/color-capability true)))

;; ── wrap-text / visible-window ──────────────────────────────────────────────

(deftest wrap-text-folds-and-respects-newlines
  (is (= ["abcde" "fgh"] (frame/wrap-text "abcdefgh" 5)))
  (is (= ["ab" "cd"] (frame/wrap-text "ab\ncd" 10)) "newlines respected")
  (is (= [""] (frame/wrap-text "" 10)) "always at least one line")
  (is (= ["a" "b"] (frame/wrap-text "ab" 1)))
  (is (= ["x"] (frame/wrap-text "x" 0)) "width floors at 1, never loops"))

(deftest visible-window-clamps-scroll
  (let [lines (mapv str (range 10))]
    (testing "tail-anchored: zero scroll shows the last h lines"
      (let [{:keys [lines scroll scroll-used]} (frame/visible-window lines 3 0)]
        (is (= ["7" "8" "9"] (vec lines)))
        (is (= {:pos 10 :total 10} scroll))
        (is (zero? scroll-used))))
    (testing "scroll past the head clamps — and reports the EFFECTIVE value"
      (let [{:keys [lines scroll-used]} (frame/visible-window lines 3 999)]
        (is (= ["0" "1" "2"] (vec lines)))
        (is (= 7 scroll-used) "callers sync state to this (phantom-scroll fix)")))))

;; ── key-from-bytes (byte vectors, per D6) ───────────────────────────────────

(defn- byte-reader
  "read! over a canned int seq; exhaustion ≡ -2 (timeout), mimicking the
   NonBlockingReader contract key-from-bytes decodes against."
  [ints]
  (let [q (atom (seq ints))]
    (fn [_timeout]
      (let [b (first @q)]
        (swap! q rest)
        (if (nil? b) -2 (int b))))))

(defn- decode [ints] (frame/key-from-bytes (byte-reader ints)))

(deftest key-decoding
  (testing "plain chars — unicode ≥128 included (accents type fine)"
    (is (= [:char \a] (decode [97])))
    (is (= [:char \é] (decode [233]))))
  (testing "control keys"
    (is (= :eof (decode [-1])))
    (is (= :ctrl-c (decode [3])))
    (is (= :ctrl-d (decode [4])))
    (is (= :tab (decode [9])))
    (is (= :enter (decode [13])))
    (is (= :enter (decode [10])))
    (is (= :backspace (decode [127])))
    (is (= :backspace (decode [8])))
    (is (= :space (decode [32]))))
  (testing "bare ESC ≡ ESC then timeout (the >0ms wait — escapement bug lineage)"
    (is (= :esc (decode [27]))))
  (testing "CSI arrows / home / end"
    (is (= :up (decode [27 91 65])))
    (is (= :down (decode [27 91 66])))
    (is (= :right (decode [27 91 67])))
    (is (= :left (decode [27 91 68])))
    (is (= :home (decode [27 91 72])))
    (is (= :end (decode [27 91 70]))))
  (testing "CSI ~ params — paging and BRACKETED PASTE (the full-param win)"
    (is (= :pgup (decode [27 91 53 126])))
    (is (= :pgdn (decode [27 91 54 126])))
    (is (= :paste-start (decode [27 91 50 48 48 126])))
    (is (= :paste-end (decode [27 91 50 48 49 126]))))
  (testing "SS3 arrows (application cursor mode)"
    (is (= :up (decode [27 79 65])))
    (is (= :left (decode [27 79 68]))))
  (testing "unknown sequences degrade to :other, never throw"
    (is (= :other (decode [27 91 57 57 126])))
    (is (= :other (decode [1])))))

;; ── edit-step ───────────────────────────────────────────────────────────────

(def empty-input {:buffer "" :cursor 0 :history [] :hist-idx nil :paste? false})

(defn- feed
  "Thread keys through edit-step; returns the final {:input :submit}."
  [in ks]
  (reduce (fn [{:keys [input]} k] (frame/edit-step input k))
          {:input in}
          ks))

(deftest editing-basics
  (testing "typing builds the buffer, cursor tracks"
    (let [{:keys [input]} (feed empty-input [[:char \h] [:char \i]])]
      (is (= "hi" (:buffer input)))
      (is (= 2 (:cursor input)))))
  (testing "enter on blank does NOT submit"
    (is (nil? (:submit (frame/edit-step empty-input :enter)))))
  (testing "enter with text submits, clears, records history"
    (let [{:keys [input submit]} (feed empty-input [[:char \h] [:char \i] :enter])]
      (is (= "hi" submit))
      (is (= "" (:buffer input)))
      (is (= ["hi"] (:history input)))))
  (testing "backspace deletes before the cursor; no-op at 0"
    (let [{:keys [input]} (feed empty-input [[:char \a] [:char \b] :left :backspace])]
      (is (= "b" (:buffer input))))
    (is (= empty-input (:input (frame/edit-step empty-input :backspace)))))
  (testing "esc clears the buffer"
    (let [{:keys [input]} (feed empty-input [[:char \x] :esc])]
      (is (= "" (:buffer input))))))

(deftest bracketed-paste-lands-as-one-submission
  (let [{:keys [input submit]}
        (feed empty-input [:paste-start [:char \a] :enter [:char \b] :paste-end :enter])]
    (is (= "a\nb" submit) "mid-paste enter ≡ newline; post-paste enter submits the block")
    (is (= "" (:buffer input)))))

(deftest history-walk-preserves-the-draft
  (let [in (:input (feed empty-input [[:char \a] :enter [:char \b] :enter]))
        ;; start a draft, then walk up
        in (:input (frame/edit-step in [:char \d]))
        up1 (:input (frame/edit-step in :up))
        up2 (:input (frame/edit-step up1 :up))
        up3 (:input (frame/edit-step up2 :up))]
    (is (= "b" (:buffer up1)))
    (is (= "a" (:buffer up2)))
    (is (= "a" (:buffer up3)) "clamped at the oldest")
    (testing "down walks back and restores the in-progress draft"
      (let [down1 (:input (frame/edit-step up2 :down))
            down2 (:input (frame/edit-step down1 :down))]
        (is (= "b" (:buffer down1)))
        (is (= "d" (:buffer down2)) "the draft survives the round trip")))))

;; ── the D5 naming lock ──────────────────────────────────────────────────────

(deftest short-name-inverts-variant-slug
  ;; ab! names children (variant-slug parent vk) ≡ :parent-vk; the tree
  ;; strips it back to "vk". This round-trip is the MUST-agree contract —
  ;; frame encodes the inverse rather than requiring the api layer.
  (doseq [[parent vk] [[:scratch :nucleus] [:s :b2] [:probe :warm]]]
    (is (= (name vk) (frame/short-name (repl/variant-slug parent vk) parent))))
  (testing "non-children pass through untouched"
    (is (= "zeta" (frame/short-name :zeta :scratch)))
    (is (= "scratch" (frame/short-name :scratch nil)))))

;; ── tree / order ────────────────────────────────────────────────────────────

(def reg
  {:scratch         {:slug :scratch :tape [{:role :user :text "hi"}
                                           {:role :assistant :text "yo"}]
                     :config {:model :m}}
   :scratch-nucleus {:slug :scratch-nucleus :tape [] :config {:model :m}
                     :forked-from :scratch :forked-at 2}
   :zeta            {:slug :zeta :tape [] :config {:model :m}}})

(deftest dfs-order-walks-the-forest
  (is (= [:scratch :scratch-nucleus :zeta] (frame/dfs-order reg)))
  (is (= [] (frame/dfs-order {}))))

(deftest tree-lines-shape
  (let [ls (frame/tree-lines reg :scratch th 24)]
    (is (= 3 (count ls)))
    (is (str/includes? (first ls) cmp/reverse-on-s) "current highlighted")
    (is (str/includes? (first ls) "scratch·2"))
    (is (some #(str/includes? % "nucleus·0 @2") ls)
        "child shows short name ⊕ depth ⊕ branch point")))

;; ── frame snapshots ─────────────────────────────────────────────────────────

(def state
  {:slug   :scratch
   :scroll 0
   :events [{:id 1 :kind :eval! :slug :scratch :msg "✓@2"}]
   :input  {:buffer "abc" :cursor 3 :history [] :hist-idx nil :paste? false}})

(deftest wide-frame-snapshot
  (let [{:keys [s cursor-row cursor-col scroll-used]} (frame/frame reg state th 100 30)]
    (is (str/includes? s "llm-repl · :scratch") "title carries the slug")
    (is (str/includes? s "tree") "tree pane present ≥70 cols")
    (is (str/includes? s "you ›") "user turn rendered")
    (is (str/includes? s "hi"))
    (is (str/includes? s "eval! :scratch ✓@2") "receipt in the tree footer")
    (is (str/includes? s "scratch[2]> abc") "prompt ⊕ buffer at depth 2")
    (is (= 30 cursor-row) "input row is the last")
    (is (= (+ (cmp/display-width "scratch[2]> ") 3 1) cursor-col))
    (is (zero? scroll-used))))

(deftest narrow-frame-falls-back-to-sessions-strip
  (let [{:keys [s]} (frame/frame reg state th 60 24)]
    (is (str/includes? s "sessions:") "narrow ≡ single pane ⊕ strip")
    (is (not (str/includes? s "eval! :scratch ✓@2"))
        "narrow mode has NO event display (ratified: footer home ≡ tree pane)")))

(deftest empty-tape-shows-welcome-hints
  (let [reg' (assoc-in reg [:scratch :tape] [])
        {:keys [s]} (frame/frame reg' state th 100 30)]
    (is (str/includes? s "type to chat") "in-idiom banner while the tape is empty")))

(deftest overlay-pops-over-decorated-and-head-anchored
  (let [st (assoc state :overlay {:title "help" :lines (mapv str (range 50))} :scroll 5)
        {:keys [s scroll-used]} (frame/frame reg st th 100 30)]
    (is (str/includes? s "⧉ help — esc closes")
        "the frame decorates — callers pass a bare title")
    (is (str/includes? s "5") "head-anchored: scroll 5 ≡ line 5 at the top")
    (is (= 5 scroll-used))))

(deftest overlay-scroll-clamps-to-document
  (let [st (assoc state :overlay {:title "t" :lines ["a" "b"]} :scroll 999)
        {:keys [scroll-used]} (frame/frame reg st th 100 30)]
    (is (zero? scroll-used) "2 lines < pane height → nothing to scroll")))

(deftest tape-scroll-clamps-and-reports
  (let [st (assoc state :scroll 9999)
        {:keys [scroll-used]} (frame/frame reg st th 100 30)]
    (is (< scroll-used 9999) "the effective scroll comes back for state sync")))
