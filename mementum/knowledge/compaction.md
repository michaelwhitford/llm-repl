---
type: Reference
title: compaction — the one true write, and how to use it without lying to yourself
status: active
related: [design/architecture, frames, self-eval, memories/self-compaction-first-exercise, memories/compaction-confabulates-not-forgets]
---

# Compaction

> `compact!` is THE one true write (D1) — everything else on the tape is
> append-only. It rewrites one assistant message in place to its λ essence.
> Both halves of the arc are live-verified (2026-08-28): the trial (the
> model compacting its OWN tape) and the validation (A/B/C behavioral diff).

## Mechanics (D1, api ns)

- `(compact! slug i λ)` — index-EXPLICIT: append-only tapes make indices
  stable, so no compare-and-swap on `i` is needed (race-free by
  construction). 4-arity adds a `floor` override.
- Outcomes as data, never a throw: `:accepted` (λ within the band;
  `:original` retained on the message — the human record, never rendered
  to the LLM) · `:declined` (λ past the ceiling; the message leaves the
  due-set FOREVER — a negative cache entry) · `:no-op` (bad index / not an
  assistant turn / already compacted-or-declined).
- EVERY outcome emits a `⚡ {:kind :compact!}` receipt — observability, not
  restriction, is the guard. `:turns` never changes (role is preserved).
- KV impact: one-time prefix-cache bust at the rewritten cell; the next
  turn re-prefills through it invisibly (measured: no error, no ritual).

## Self-compaction (the trial, one dispatch)

The armed model (`:tools`, slug-aware orientation) compacted its own tape
in ONE dispatch: snapshot → wrote a 164-char λ from its 929-char prose
(~5.7:1) → `compact!` → reported the outcome map. Verified externally:
receipt ≡ tape ≡ report.

**The rule that made it one-shot: give the model an EXPLICIT size budget**
("one dense sentence, under 200 chars"). The band contract is invisible to
the model — the instruction stands in for it; don't make it discover the
band by declined attempts.

## Validation (A/B/C) — what compaction preserves and how it fails

Criterion: `compact!` valid ⟺ fold(compacted) ≈ fold(original) — preserve
the Δcontext, not the words.

- **Good λ (binders kept):** 5/5 probe parity with the pristine arm. Every
  observable delta traced exactly to the λ (unit duplicates gone; the arm
  answers in the λ's phrasing). The compression victim was pure redundancy
  — markdown scaffolding, restatement, adjective padding.
- **Harsh λ (binders starved): the tape CONFABULATES, it does not forget.**
  Every probe answered fluently and confidently. One starved binding came
  back CORRECT-by-attractor (the same generator re-invented 230°C — nowhere
  on that tape); ordering re-derived from RECENCY (the surviving decision
  turn won); a detail tail drifted plausibly. All three frames.md reducer
  predictions, live.

## Operating rules (the synthesis)

1. **λ authorship ≡ binder selection.** Keep names, numbers, decisions —
   the future's operands. Spend the budget from redundancy, never from
   bindings. (frames: binder mass predicts substitution success.)
2. **A compacted tape never says "I don't know."** No signal separates
   recalled from regenerated — so NEVER validate by asking the model what
   it remembers. Validation ≡ arm-vs-arm behavioral diff (`fork!` ⊕
   `run-battery!` with identical probes; `fork! :at` gives a pre-treatment
   prefix for extra arms).
3. **`:original` is the only post-hoc ground truth.** D1's retention is
   not a nicety — after compaction it is the sole recall of what the λ
   dropped. (Caveat carried in D1: retained originals double those cells'
   weight in a full-registry fetch; D3's delta protocol is the mitigation.)
4. **Trace-durability is the audit surface for silent confabulation** —
   durable per-turn payloads let a reader diff what the model SAID against
   what its tape could actually have told it (queue: ⚪ trace-durability;
   same provenance logic as the :implant finding — silence in the trace is
   the tell).
