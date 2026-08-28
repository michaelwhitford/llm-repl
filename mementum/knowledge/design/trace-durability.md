---
type: Architecture
title: Trace durability ⊕ tape persistence — one seam on escapement's capture layer
status: PROPOSED — awaiting ratification (do not build until the human ratifies)
related: [design/architecture, design/library-contract, upstream/escapement]
---

# Trace durability ⊕ tape persistence — design proposal

> Drafted 2026-08-28 from the capture-layer exploration (source-read of all 7
> escapement nses ⊕ bb round-trip verified against the 1.0.1 jar already on
> the classpath — see [upstream/escapement](../upstream/escapement.md) § capture
> layer). Expands the architecture doc's "Trace durability" placeholder and
> ABSORBS the tape-persistence queue item: one integration covers both.
> Nothing here is ratified; § Open questions lists the decision points.

## Why (unchanged from the placeholder, plus one merge)

1. **Recall** — the `:self-mod` experiment tape died with a container
   restart; the llama.cpp verbose log (the only trace) gets purged. Durable
   traces make `compact!` safe at scale: the tape is rewritten, every step
   stays retrievable (`:original` on-tape ⊕ full generation on disk).
2. **Provenance** — the `:implant` result: a forged turn (raw `swap!`) is
   invisible from inside; on disk, `tape ∖ trace ≡ undeclared edits`.
   Every legitimate assistant turn has a captured generation behind it;
   silence in the trace is the tell. Audit surface, not restriction.
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

## Open questions (ratification decision points)

1. **Default on or off?** Proposal: ON when a daemon owns a work-dir; OFF
   under `--plain` (debug hatch, no persistence surprise). Config
   `:trace {:enabled? bool :dir ".llm-repl"}`.
2. **Recovery: auto or explicit?** Proposal: auto-recover into `sessions*`
   at boot (loud receipts) — matches "the daemon is the persistence given a
   process". Alternative: a `restore!` manual command, lazier but another
   thing to know.
3. **Turn ≡ assistant tape index** — accepts sparse turn numbers (indices
   skip user messages). Alternative: per-session eval counter (dense, but a
   second bookkeeping number). Proposal: tape index (one number, already in
   receipts as `✓@N`).
4. **compact! original blob** — redundant with `:original` on-tape? Proposal:
   yes, capture anyway — the on-tape copy dies with the registry and D3
   flags its fetch weight; the durable copy is the arm-diff ground truth.
5. **Queue merge** — subsume ⚪ tape-persistence into ⚪ trace-durability
   (this doc) on ratification.

## Estimate

1–2 sessions (unchanged from the queue): trace ns ⊕ seam wiring ⊕ recovery
⊕ tests (memory-store stub makes the suite filesystem-free; disk round-trip
gets one bb ∧ JVM twin test each).
