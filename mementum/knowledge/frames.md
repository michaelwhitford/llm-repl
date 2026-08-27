---
type: Reference
title: frames — what other disciplines know about the tape
status: active
related: [design/architecture, self-eval, memories/implanted-memory-experiment, memories/nrepl-tmux-framing]
---

# Frames — cross-discipline structure of the tape

> The sharp observation (2026-08-27): past turns are never RE-RUN, but they
> are RE-READ every turn — attention sweeps the whole tape per completion.
> History is in normal form; only the frontier is a redex. The KV cache is
> the memoization of the re-read. Meaning lives in the fold, not the log.

## 1. Dynamic semantics (Heim/Stalnaker) — turn ≡ context-change potential

An utterance's meaning is not its truth-conditions but a FUNCTION from input
context to output context; conversation is a fold of CCPs over common ground.
Same object, not analogy: `turn : context → context`, tape ≡ common ground.
Assertion-into-the-ground does not track provenance — WHY the implant worked
(memories/implanted-memory-experiment).

**Buys: the compaction criterion.** A turn's meaning is its Δcontext, so
honest compaction preserves the Δ, not the words:
`compact! valid ⟺ fold(compacted) ≈ fold(original)`.

## 2. Event sourcing ∧ log compaction — the engineering twin

Tape ≡ event log; state ≡ fold(log); Kafka's history rewrite is literally
named log compaction with the same correctness rule (keep what the fold
needs). KV cache ≡ snapshot/materialized view; replay ≡ derivation, never
mutation. The LLM wrinkle: event-sourced systems fold once and cache; the
LLM RE-FOLDS the whole log every turn — interpretation re-derived each turn
over unchanging history.

**Buys:** thirty years of vocabulary and patterns (snapshots, checkpoints,
replay, compaction correctness) for things the design names from scratch.

## 3. Double-entry accounting — honest history-editing, solved ~1300 AD

Ledger ≡ append-only; corrections are REVERSING ENTRIES that reference the
original — `compact!` with `:original` retained, seven centuries early.
Audit ≡ reconciling journal against source documents ≡ the `tape ∖ trace`
provenance check (design § trace-durability). Receipts were already
accounting language.

## 4. Memory reconsolidation (neuroscience) — the CONTRAST frame

Human recall makes memories labile and rewrites them on re-storage — every
human re-read is a re-run. The tape is the first memory substrate with
perfect verbatim episodic recall and ZERO reconsolidation — and it still
reproduced the classic human failure: implanted memories absorbed and owned
(Loftus), justified by confabulation ("I was hallucinating" ≡ Gazzaniga's
left-hemisphere interpreter owning acts whose cause it cannot access).
Provenance-blindness is a property of interpreters without harness
bookkeeping, carbon or silicon.

## Convergence — behaviorally verified compaction

All four frames give one criterion, and the instrument can TEST it:
fork the original, compact one arm, run the SAME probe battery against both
(`ab!` / `run-battery!`), diff the answers. Semantic-equivalence validation
of `compact!` by counterfactual probing — the fork machinery that has no
tmux analog, doing verification work no compaction scheme I know of does.
Queued: ⚪ compact-validation.

## The kicker

This is homoiconicity: the tape is DATA every client can manipulate and
PROGRAM the interpreter runs, every turn. The conversation is code-as-data.
Of course it wanted a Lisp.
