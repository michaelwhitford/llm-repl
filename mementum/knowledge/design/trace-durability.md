---
type: Architecture
title: Trace durability ⊕ tape persistence — one seam on escapement's capture layer
status: BUILT 2026-08-28 (same day as ratification) — verified by trace_test.clj (15 tests incl. disk twin), capture tests in completion_test/llm_repl_test, and a LIVE two-incarnation restart (visit 1→2, :recover receipt, transcript :seq continuous)
related: [design/architecture, design/library-contract, upstream/escapement, container]
---

# Trace durability ⊕ tape persistence — ratified design

> Drafted 2026-08-28 from the capture-layer exploration (source-read of all 7
> escapement nses ⊕ bb round-trip verified against the 1.0.1 jar already on
> the classpath — see [upstream/escapement](../upstream/escapement.md) § capture
> layer). Expands the architecture doc's "Trace durability" placeholder and
> ABSORBS the tape-persistence queue item: one integration covers both.
> RATIFIED 2026-08-28 (human: yes to all 5, Q2 pinned receipt-and-skip) —
> § Ratified decisions is authoritative; build to this document.

## Why (unchanged from the placeholder, plus one merge)

1. **Recall** — the `:self-mod` experiment tape died with a container
   restart; the llama.cpp verbose log (the only trace) gets purged. Durable
   traces make `compact!` safe at scale: the tape is rewritten, every step
   stays retrievable (`:original` on-tape ⊕ full generation on disk).
   **CLOSED 2026-08-28** — the motivating failure is verified dead in the
   place it happened: `.llm-repl/` keys off CWD ≡ `/work`, so a
   containerized daemon writes the trace through the mount to the host and
   `:io/ref` resolves from macOS (knowledge/container § trace durability
   crosses the wall). No container-specific code; the mount seam was already
   the answer.
2. **Provenance** — the `:implant` result: a forged turn (raw `swap!`) is
   invisible from inside; on disk, `tape ∖ trace ≡ undeclared edits`.
   Every legitimate assistant turn has a captured generation behind it;
   silence in the trace is the tell. Audit surface, not restriction.
   External evidence (2026-08-28, memories/dialect-detection-is-the-null-
   trace): louisabraham.github.io/load-bearing measures Claude's dialect at
   ~37% of human-attributed GitHub PRs — with no trace layer, provenance
   degrades to stylometry: aggregate-only, never per-document, and unable
   to tell laundering from genuine absorption. Traces make provenance a
   lookup, not an inference.
3. **NEW — confabulation audit** — compact-validation's verdict: over-
   compaction confabulates FLUENTLY, never reports absence. Arm-diff needs
   ground truth that outlives the daemon; `:original` on the tape dies with
   the registry. The trace is the durable arm-diff substrate.
4. **Tape persistence falls out** — `ArtifactStore` is generic (path ≡ the
   addressing key); a tape snapshot is just one more artifact. The separate
   `tape-persistence` queue item collapses into this seam.

## What escapement provides (verified, zero new deps)

All in `com.fulcrologic/escapement 1.0.1` — already on bb.edn ≡ deps.edn,
all bb-loadable, round-trip verified 2026-08-28. Full λ contracts:
[upstream/escapement](../upstream/escapement.md) § capture layer. Short form:

```
capture.cljc      locator-addressed EDN blobs → {:io/ref :io/snippet ≤80ch}
                  first-write-wins requests · seed-visit-counts restart guard
transcript.clj    single-writer JSONL: FIFO ∧ no-interleave ∧ monotonic :seq,
                  seq CONTINUES across append-resume · never crashes caller
storage/disk      atomic writes (temp+rename) · tree literally walkable
storage/disk_read multi-session READ store: read-events*(query, short-circuits),
                  list-sessions*, constant-memory summaries
replay.cljc       refine-turn: captured request ⊕ overrides → any LLMBackend
                  → {:request :response :original-request}
```

## Coordinate mapping — the fork forest IS the node tree

```
escapement          llm-repl
──────────────      ─────────────────────────────────────────────────────
work-dir        ≡   <proj>/.llm-repl/          daemon-owned; in the container
                                               this sits under /work (the one
                                               hole) → survives restarts
session-id      ≡   the daemon instance        ONE escapement session-dir per
                                               daemon work-dir ("main")
node-id         ≡   the session slug (:ouro)   nodes/<slug>/… — the fork
                                               forest materialized on disk;
                                               ab! child slugs (parent-variant,
                                               D5) are filesystem-safe already
visit           ≡   daemon incarnation         seed-visit-counts on boot →
                                               max+1; a restart NEVER
                                               overwrites prior traces
turn            ≡   tape index of the          stable: compact! is
                    assistant message the      index-stable (ratified, step 4)
                    completion produced
```

`encode-node-id` (`/`→`_`) is not perfectly reversible — authoritative
coordinates ride the transcript event, per escapement's own posture.

## What gets captured, where (the emission seams)

```
seam (exists today)                    captures                       locator
─────────────────────                  ────────                       ───────
completion/plain-complete ∧            request  (build-request out —  turns/<n>/request.edn
  tool-complete, around                 FULL wire: system ⊕ messages   (first-write-wins:
  proto/send-turn                       ⊕ knobs)                       continuations never clobber)
same, after p/await!                   response (escapement Response   turns/<n>/response.edn
                                        VERBATIM — thinking blocks
                                        survive; assistant-text drops
                                        them from the TAPE, the trace
                                        keeps them — the :self-mod
                                        reconstruction need, met)
completion tool loop, per dispatch     tool result EDN                turns/<n>/tool-results/<tool_use_id>.edn
registry mutation chokepoint           tape snapshot (latest, atomic  nodes/<slug>/<visit>/tape.edn
  (mutate! — sessions* changed)         overwrite — recovery source)
registry/event! (already the ONE       every receipt → transcript     transcript.jsonl
  receipt chokepoint)                   JSONL via make-transcript-fn   (⊕ :io/ref when the
                                                                       receipt has a blob)
api/open!                              session config seed            nodes/<slug>/<visit>/seed.edn
api/compact!                           pre-compaction original blob   turns/<n>/original.edn
                                        (durable twin of :original)
completion/send-traced!, on THROW      the failed request ⊕ error      nodes/<slug>/<visit>/
  (the ONE seam every physical           (:at :error :ex-type          failures/<ts>-<n>.edn
  send passes through)                   :ex-data :request)            (AMENDED 2026-08-28 —
                                         UNGATED by *capture?*          ungated; see decision 1)
```

Receipts grow `:io/ref` — the receipt stream already points INTO the tree
(architecture invariant); with a ref it points into the *disk* tree too. The
tree pane needs zero changes; a later overlay can chase refs.

## The new ns — `us.whitford.llm-repl.trace` (io layer)

One ns owns the integration; every other ns keeps exactly one call-shape:

```
λ(trace).  trace/enabled?  : config → bool                (config :trace, default ON for daemon)
           trace/init!     : config → nil                 (daemon boot: open transcript sink,
                                                           build DiskArtifactStore,
                                                           seed-visit-counts → visit)
           trace/capture!  : slug × turn × kind × data × snippet → {:io/ref …} | nil (disabled ⇒ nil)
           trace/receipt!  : event → nil                  (transcript write!; wired into
                                                           registry/event! — ONE chokepoint)
           trace/tape!     : slug × tape → nil            (atomic snapshot on mutate!)
           trace/close!    : nil → nil                    (daemon shutdown: drain ∧ join)
```

Failure posture: capture failures are receipts (`{:kind :trace :msg "✗ …"}`),
NEVER exceptions into the completion path — durability must not break evals
(matches transcript.clj's own never-crash-the-caller stance). But *silent*
no-write when enabled is the silent-fallback failure mode — hence the receipt.

Dependency direction stays downward: trace sits in io, calls escapement +
disk; registry does NOT require trace (the transcript hook is injected at
daemon boot — an open slot, `absent(default) ∧ present(compose)`), so the
registry stays EDN-pure and library consumers who never `trace/init!` pay
nothing.

## Recovery (the tape-persistence half)

Daemon boot, when `.llm-repl/` exists: `list-artifacts` → latest
`tape.edn` per slug per max-visit → offer into `sessions*`. The registry
invariant holds — tape.edn is pure EDN (messages ⊕ config), `:complete-fn`
was never stored, so a recovered session is immediately eval!-able. Recovery
emits `{:kind :recover}` receipts per session — visible, never silent.

## Replay bonus — `refine-turn` ≡ ab!-from-history, today

`llm.llamacpp` implements `LLMBackend`; `replay/refine-turn` loads a captured
`request.edn`, deep-merges overrides, sends. Re-issue any past turn of a DEAD
session against a different prompt/model — fork the past without the daemon
that ran it. No llm-repl code needed; document in the agent-recipe page.

## Invariants (proposed additions to the carried-forward list)

- `.llm-repl/` is machine-local, NEVER committed — captured `request.edn`
  contains the RESOLVED system prompt including the preamble; the licensing
  boundary (~/.config/llm-repl) must not leak into a repo via traces.
  Ship a `.llm-repl/.gitignore` (`*`) written by `trace/init!` (belt ⊕
  suspenders — same trick as CI caches).
- Trace failures → receipts, never throws, never silence.
- The live writer owns appends; readers use disk-read (enforced upstream).
- Registry stays EDN ∧ trace-free: the hook is injected, not required.

## Ratified decisions (2026-08-28, human: yes to all)

1. **Default: ON for daemon, OFF for `--plain`.** Config
   `:trace {:enabled? bool :dir ".llm-repl"}`. The daemon logs by default
   (syslog posture); the labeled debug hatch stays clean.
2. **Recovery: AUTO at boot, loud receipts — RECEIPT-AND-SKIP on bad
   snapshots** (human-pinned). A snapshot that fails to parse emits
   `{:kind :recover :msg "✗ …"}` and is skipped; the daemon boots degraded,
   never refuses to start over one bad session file. Recovery is additive to
   an empty registry; atomic writes ∧ visit-versioning bound the blast radius.
3. **Turn ≡ assistant tape index (sparse).** One ID space — the coordinate
   already in receipts (`✓@N`), survives compact! (index-stable, ratified).
   Gaps in `turns/` cost nothing; a second dense counter costs forever.
4. **compact! original → disk, yes.** Not redundant: the on-tape `:original`
   dies with the registry; the durable `original.edn` is the arm-diff ground
   truth against silent confabulation (compact-validation's finding).
5. **tape-persistence SUBSUMED** into this design (🚫 in queue). One store,
   one seam; tape.edn ≡ one more artifact.

## Build decisions (2026-08-28, build session — runtime-pinned ∧ human-consulted)

Runtime facts (bb REPL against the 1.0.1 jar, this session):

- **node-id must be a KEYWORD.** `encode-node-id` assumes keyword print form
  and strips the leading char — a STRING node-id `"ouro"` silently becomes
  path `nodes/uro/…`. Our slugs are keywords; pass them raw, never `(name)`.
- **kind is a STRING** (`"response"`, `"tool-results/<id>"`) — a keyword kind
  renders its colon into the filename (`turns/4/:response.edn`).
- **`capture-blob!` OVERWRITES (last write wins); only `capture-request!` is
  first-write-wins.** Verified: two blob writes → second content survives;
  two request writes → first survives.
- `read-artifact` returns the `pr-str` STRING — callers `edn/read-string`.
- Locators are DETERMINISTIC (path ≡ pure fn of node/visit/turn/kind) — a
  receipt can carry `:io/ref` for a blob *before or without* the write
  (`trace/ref-for`), no reordering of receipt-then-dispatch needed.

Decisions layered on the ratification:

1. **Tapeless drivers are receipt-only** (human-decided this session).
   `bounce!`/`trampoline!` never commit, so their sends have NO assistant
   tape index — N bounces off one prefix collide on the same turn number.
   They bind `trace/*capture?*` false; the receipt stream stays their trace
   (the standing S3* posture). `eval!`, `run-battery!`, and `ab!` arms get
   full capture. Provenance invariant unharmed: it concerns TAPE turns.

   **AMENDED 2026-08-28 (ratified ∧ built same day) — except on failure.**
   A FAILED send captures, tapeless or not: `trace/failure!` is gated on
   `enabled?` alone. The reason receipt-only exists is *colliding turn
   numbers*; a failed send commits nothing, so it collides with nothing, and
   it is precisely the send whose payload you need. Its locator needs no
   turn index — `failures/<ts>-<n>.edn`, the process-local `<n>` keeping a
   `trampoline!` fan-out over a down backend from overwriting itself.

   Found by USING the instrument, not by reading it: an armed `bounce!` died
   on an HTTP 400 four tool-rounds deep and left `✗ llama.cpp API error:
   HTTP 400` — enough to locate the failure, nothing to diagnose it with.
   Three identical retries then passed and the leading hypothesis was
   falsified, so the cause remains unknown; the request that would have
   settled it was never written. The receipt was the trace, and the trace
   was not enough.

   Mechanism: `completion/send-traced!` is now the ONE seam every physical
   send passes through (plain, tool-loop round, tool-loop final). On throw it
   captures, then rethrows with the locator in `ex-data` under `:trace/ref`;
   the drivers' `✗` receipts lift that to `:io/ref` (`err-receipt`), so the
   receipt POINTS AT the payload. `ex-message` is preserved verbatim and the
   original is the `cause` — every prior receipt and `:repl/error` string is
   byte-identical. With tracing off the seam adds nothing: the original
   throwable propagates untouched.
2. **`drop!` writes a tombstone** (`{:trace/dropped true :at ms}` over
   `tape.edn`) — without it, auto-recovery would resurrect deliberately
   dropped sessions on every daemon restart. `recover!` skips tombstones
   silently (intentional state, not a failure). Prior visits' turn blobs
   remain on disk — drop deletes the SESSION, never the history.
3. **Tool-loop intermediate responses** capture as `turns/<n>/rounds/<k>-response.edn`;
   the FINAL (text) response as `turns/<n>/response.edn`. blob-overwrite
   semantics would otherwise keep only the last round; intermediate thinking
   /tool_use blocks are exactly the reconstruction material (§ Why 1).
4. **`:io/ref` rides the receipts that name a blob**: `eval!`'s `✓@N`
   carries the response ref; each `⚡` dispatch receipt carries its
   tool-result ref (computed pre-dispatch via locator determinism — the
   pre-dispatch activity signal is kept). Battery's aggregate `N✓` receipt
   carries none (per-turn coords derivable). `event-line` ignores extra
   keys — zero surface changes.
5. **`tape.edn` content ≡ the full session map** (`:slug :tape :config
   :turns :created-at :forked-from :forked-at`) — recovery needs config to
   honor configuration-completeness, not just messages. All EDN by the D3
   registry invariant, so the snapshot is `pr-str`-safe by construction.
6. **Registry taps** (`event-tap*`, `mutate-tap*` — defonce atoms, nil
   default): registry stays trace-free; `trace/init!` injects, `close!`
   retracts. Tap calls are try/catch-guarded IN registry (a foreign tap must
   not break the event/mutation path; trace's own fns are loud via receipts
   — the guard is the belt for the injected-fn seam itself).

## Estimate

1–2 sessions (unchanged from the queue): trace ns ⊕ seam wiring ⊕ recovery
⊕ tests (memory-store stub makes the suite filesystem-free; disk round-trip
gets one bb ∧ JVM twin test each).
