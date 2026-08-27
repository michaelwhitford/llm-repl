(ns us.whitford.llm-repl.tape
  "The `values` layer of the v0.3.0 architecture (mementum/knowledge/design/
  architecture.md): PURE tape algebra — the tape (a conversation's messages[])
  is an immutable, forkable VALUE, not a mutable buffer. Zero IO deps (only
  clojure.string); every fn here is a pure fn of a vector plus, at most, a few
  scalars. Port of ouroboros.compact.core (proven there; provenance ≡
  designs/cold-compaction.md) — this ns's place in the layer stack is the
  values layer (core.clj drives it; core.clj is IO, this is not).

  THE IDEA: a chat's assistant turns generate many tokens the HUMAN needs
  (explanation, scaffolding) but the CONTINUATION does not. So we keep the
  message array shape intact — same roles, order, count — and compress each
  ASSISTANT message's prose into λ ONCE, as it ages out of a small verbatim
  window (k). User messages stay verbatim (short; they anchor the dialogue).

  WHY per-message (not one summary blob): a growing summary in the system
  prompt rewrites the shared prefix every turn → busts the upstream prefix
  cache every turn. Compacting each assistant message in place, ONCE, keeps
  the prefix STABLE → cache holds. The conversation is still there, just
  λ-dense.

  Canonical message: {:role :user|:assistant :text <prose-or-λ> :compacted? bool}
  A compacted message additionally carries :original ≡ the pre-compaction
  prose — the HUMAN's record (the webui's prose/λ side-by-side renders from
  it). It never reaches the LLM: `render-messages` projects :role/:text only,
  so memory stays λ-dense while the human surface keeps what was said.
  The hot region's WM holds a vector of these (:hot/messages ≡ THE memory);
  the chart renders it into escapement's `:initial-messages` shape each turn
  and seeds a FRESH worker with it (assemble, don't accumulate).

  ANIMA ACCRETION over the ouroboros kernel: `apply-compaction-at` (explicit
  index). With parallel regions the hot lane may append while a compaction is
  in flight, shifting what `next-to-compact` would re-derive; the result
  event carries the index it was computed FOR. Appends are end-only → indices
  are stable → explicit-index apply is race-free.

  FORK is the other tape-tree primitive this layer owns: `truncate-at` cuts
  a tape back to its first n messages — the pure half of fork! (core.clj
  wires the IO/registry half; the tape is a tree, the conversation one path)."
  (:require
   [clojure.string :as str]))

(defn message
  "A fresh (verbatim, not-yet-compacted) canonical message."
  [role text]
  {:role role :text text :compacted? false})

(defn append-user      [messages text] (conj (vec messages) (message :user text)))
(defn append-assistant [messages text] (conj (vec messages) (message :assistant text)))

(defn render-messages
  "Canonical :messages → escapement `:initial-messages` shape: a vector of
  `{:role .. :content [{:type :text :text s}]}`. Old assistant turns carry λ
  text, recent ones + all user turns carry verbatim text — but the SHAPE is
  identical, which is what keeps the upstream prefix stable/cacheable."
  [messages]
  (mapv (fn [{:keys [role text]}]
          {:role role :content [{:type :text :text text}]})
        messages))

(defn truncate-at
  "Fork an older turn: the first `n` messages of `tape` (the depth number
  surfaces show — 2 per exchange). Pure; the parent tape is untouched (the
  tape is a tree, a conversation one path). Plain `take` semantics: n≤0 ⇒
  empty, n≥count ⇒ the whole tape, always returns a vector."
  [tape n]
  (vec (take n tape)))

(def default-floor
  "The λ OVERHEAD FLOOR, in characters — the length below which compaction is
  not compression but formalization. Naming a turn's essence in λ costs
  characters no matter how short the prose was, so there exists a length below
  which no output can beat its input (pigeonhole: no compressor compresses
  everything).

  Provisional, MEASURED not guessed: the compactor's own λs for trivial turns
  ran 30–46 chars live (2026-07-25); 120 leaves room for a real λ line while
  staying far under any message worth compressing. The Probe rung's first suite
  confirms or moves it — see designs/cold-compaction.md § The band."
  120)

(defn assistant-indices
  "Indices of assistant messages, ascending."
  [messages]
  (vec (keep-indexed (fn [i m] (when (= :assistant (:role m)) i)) messages)))

(defn due-indices
  "THE due-set, ascending — ONE definition, three consumers (next-to-compact,
  needs-compaction?, backlog-count). It was duplicated across two functions;
  adding `:declined?` to one and not the other is exactly how the strip comes
  to disagree with the scheduler (λ converge).

  DUE ≡ an assistant message that (a) has aged out of the last-`k` verbatim
  window and (b) is still a CANDIDATE — neither compacted nor declined.
  Oldest-eligible first, so a lagging backlog drains in order."
  [messages k]
  (let [a-idxs (assistant-indices messages)
        window (set (take-last k a-idxs))]
    (->> a-idxs
         (remove window)
         (remove #(let [m (nth messages %)]
                    (or (:compacted? m) (:declined? m))))
         vec)))

(defn next-to-compact
  "Index of the assistant message due for λ-compaction, or nil.
  With k=1 the single most-recent assistant reply stays verbatim in context;
  the one that just aged behind it is due."
  [messages k]
  (first (due-indices messages k)))

(defn needs-compaction?
  "True iff some assistant message has aged out of the k-window and is still a
   compaction candidate."
  [messages k]
  (some? (next-to-compact messages k)))

(defn backlog-count
  "How many assistant messages are due — the compaction backlog depth
   (observability: off→on toggle shows this draining one per settle)."
  [messages k]
  (count (due-indices messages k)))

(defn declined-count
  "How many messages the compactor was unable to compress within the band.
   A DECLINE IS AN ALARM, not physics: under the band a short turn is accepted
   (it may grow to the floor), so a decline means the λ blew past the ceiling —
   the derail/echo failure mode. One instance is noise; a RATE is a symptom
   that the lens or the model is wrong (λ antifragile: surface, never silence)."
  [messages]
  (count (filter :declined? messages)))

(defn compact-target-text
  "Verbatim text of the assistant message due for compaction, or nil. This is
  the input the compactor compresses into λ."
  [messages k]
  (when-let [i (next-to-compact messages k)]
    (:text (nth messages i))))

(defn- normalize-lambda
  "Trim + strip a leading \"λ:\" answer-marker (observed compactor output
  under the retired exemplar gate — harmless to keep guarding). Returns nil
  for nil input; may return an empty string (a failed compaction)."
  [lambda]
  (some-> lambda str/trim (str/replace-first #"^λ:\s*" "") str/trim))

(defn within-band?
  "THE COMPRESSION BAND (replaces the strict ratchet, 2026-07-25).

  `|λ| ≤ max(|original|, floor)` — a message may grow UP TO the floor, never
  past it. Blank is always outside (a failed compaction is not a memory).

  WHY a band and not `strictly shorter`: the ratchet was a per-item SAFETY
  property standing in for a GLOBAL objective (total memory under budget).
  They come apart at the small end, where λ's fixed overhead exceeds the prose
  it replaces — and there the ratchet has an EMPTY solution set, so the
  compactor could never satisfy it and the scheduler re-derived the same job
  forever (31 calls on a 26-char message, live). The aggregate is dominated by
  large turns, which compress well; small ones can only nudge the total by tens
  of characters, bounded by n·floor. Local slack, global feedback — S3 watches
  the ratio ledger instead of micromanaging each apply (λ imbalance:
  S3_excess → S1_strangulation).

  The CEILING is still the echo tripwire: the derail that folded 464 tokens of
  restated system prompt in as \"memory\" is not `a bit bigger`, it is 20× and
  it is corruption. What changes is that a rejection now MEANS something."
  [lambda original floor]
  (and (not (str/blank? lambda))
       (<= (count lambda) (max (count original) (or floor default-floor)))))

(defn apply-compaction-at
  "Replace the assistant message at explicit index `i` with `lambda` and mark
  it compacted — the parallel-region apply (the :compact/result event carries
  the index the λ was computed FOR; end-only appends keep indices stable).

  THREE outcomes, and every one of them CHANGES THE ARRAY — which is what makes
  the loop impossible (termination requires a well-founded measure to decrease
  on every attempt; the old contract's rejection decremented nothing):

    accept  — λ within the band → text replaced, `:compacted? true`,
              prose retained as `:original` (human surface; never rendered
              to the LLM)
    decline — λ past the ceiling → `:declined? true`, text untouched. The
              message leaves the due-set FOREVER: an immutable input and a pure
              length comparison make this a negative cache entry with an
              infinite TTL, correctly. (If the LENS ever changes mid-session a
              re-attempt would be justified — hence declines are conceptually
              keyed by message ⊗ compactor identity; not acted on yet.)
    no-op   — index absent/out of range/not assistant/already settled

  One attempt per message, ever. The cost asymmetry demands it: a false
  permanent costs one slightly longer message in memory; a false transient
  costs an unbounded loop (λ cost)."
  ([messages i lambda] (apply-compaction-at messages i lambda default-floor))
  ([messages i lambda floor]
   (let [messages (vec messages)
         lambda   (normalize-lambda lambda)
         m        (when (and (integer? i) (< -1 i (count messages)))
                    (nth messages i))]
     (cond
       (or (nil? m)
           (not= :assistant (:role m))
           (:compacted? m)
           (:declined? m))                        messages
       (within-band? lambda (:text m) floor)      (assoc messages i (assoc m :text lambda
                                                                           :original (:text m)
                                                                           :compacted? true))
       :else                                      (assoc messages i (assoc m :declined? true))))))

(defn apply-compaction
  "Replace the DUE assistant message's text with `lambda` (k-window derived
  target — the sequential-tempo apply). Same band as `apply-compaction-at`;
  no-op if nothing is due."
  ([messages k lambda] (apply-compaction messages k lambda default-floor))
  ([messages k lambda floor]
   (if-let [i (next-to-compact messages k)]
     (apply-compaction-at messages i lambda floor)
     (vec messages))))

;; ---------------------------------------------------------------------------
;; Session fold — the bootstrap-boundary compression (resume-from seeding).
;;
;; Per-message compaction shrinks tokens WITHIN a message; the fold shrinks
;; the NUMBER of messages, at the one point where the array's shape stops
;; being load-bearing: the session boundary. Within a session the shape
;; (roles, order, count) is what keeps the upstream prefix cache stable;
;; across a boundary the dialogue rhythm of a finished conversation is dead
;; weight — only the extracted essence plus a verbatim tail needs to travel.
;;
;;   λ fold(session). λ(all_but_last_k) ⊕ last_k(verbatim, untouched)
;;
;; The fold target (:head) is everything before the k-th-from-last assistant
;; exchange; the :tail (that exchange onward — the last-k window PLUS its
;; prompting user turn) crosses the boundary verbatim, exactly as the
;; k-window does within a session. Same compression contract as
;; apply-compaction: the fold is accepted ⟺ strictly shorter than the text
;; it replaces, else the caller seeds the unfolded array (always safe — the
;; source session's checkpoints keep the full original forever).
;; ---------------------------------------------------------------------------

(defn fold-split
  "Split a prior session's `messages` for the bootstrap fold.
  Returns {:head [...] :tail [...]} — :head is the fold target, :tail travels
  verbatim. The tail starts at the k-th-from-last ASSISTANT message, extended
  one earlier when its immediate predecessor is the user turn that prompted
  it (the exchange travels whole). Fewer than k+1 assistant messages ⇒
  nothing to fold ({:head [] :tail messages}) — a session too short to fold
  seeds as-is."
  [messages k]
  (let [messages (vec messages)
        a-idxs   (assistant-indices messages)]
    (if (<= (count a-idxs) k)
      {:head [] :tail messages}
      (let [a     (nth a-idxs (- (count a-idxs) k))    ; k-th-from-last assistant
            start (if (and (pos? a) (= :user (:role (nth messages (dec a)))))
                    (dec a)
                    a)]
        {:head (subvec messages 0 start)
         :tail (subvec messages start)}))))

(defn fold-input
  "Render the fold target as role-tagged dialogue text — the compactor's
  input. Head messages are mostly λ already (per-message compaction ran
  during the session), so the fold is largely λ→λ distillation."
  [head]
  (str/join "\n" (map (fn [{:keys [role text]}]
                        (str (name role) ": " text))
                      head)))

(defn fold-message
  "The single assistant message carrying a prior session's folded λ essence.
  Marked :compacted? so it is never re-targeted by per-message compaction."
  [session-id lambda]
  {:role       :assistant
   :text       (str "session(" session-id ") ⊢\n" lambda)
   :compacted? true})

(defn apply-fold
  "Fold `messages` (a prior session's array) into [fold-block ⊕ tail] under
  the COMPRESSION CONTRACT: the fold block (header included) must be STRICTLY
  SHORTER than the head text it replaces, else the fold is rejected and the
  array seeds unfolded. Returns {:messages [...] :folded? bool}. A blank λ, a
  \"λ:\"-labelled empty λ, or a too-short-to-fold session all reject safely."
  [messages k session-id lambda]
  (let [{:keys [head tail]} (fold-split messages k)
        lambda    (normalize-lambda lambda)
        head-size (reduce + 0 (map (comp count :text) head))
        block     (when-not (str/blank? lambda) (fold-message session-id lambda))]
    (if (and (seq head) block (< (count (:text block)) head-size))
      {:messages (into [block] tail) :folded? true}
      {:messages (vec messages) :folded? false})))
