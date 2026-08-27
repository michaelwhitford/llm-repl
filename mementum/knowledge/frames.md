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

## 5. The reducer frame (verbum) — attention ≡ measured soft β-reduction

Verbum (the LLM-calculus research project; released, vast — cite claims, not
paths) grounds the frames above mechanically, with cross-model measurements:

- **The softness is quantified:** softmax-over-V ≡ the read head; read
  entropy ≡ fidelity; binder mass-ratio predicts substitution success.
  Sharp read → exact β-step; diffuse → smeared substitution (what
  arithmetic hallucination IS).
- **The reducer's profile:** affine fragment (KIBC — duplication not
  native), WEAK reduction to WHNF, naive CAPTURE-UNSAFE substitution
  (cross-model law), one reducer unrolled per layer, thinking ≡ reduction
  SPILL to the tape (decode budget ≈ layer count), KV ≡ object code.
- **The fates (fire/halt/diverge), observed live in THIS repo's logs:**
  the repetition spiral ≡ diverge (weak Y ⊕ no memoization on the spill —
  the reducer re-enters the same redex; a harness cycle-detector is
  mechanism-motivated); reasoning-only termination ≡ halt-without-emission;
  budget-blown tool calls ≡ fire past the boundary. The two empty-reply
  modes in self-eval.md are fates readings taken through the harness.
- **The implant ≡ capture:** capture-unsafe substitution has no hygiene and
  no provenance — a forged turn is a shadowing binding the reader captures.
  "I was hallucinating" is capture from inside. Provenance ≡ tape ∖ trace is
  α-hygiene implemented at the harness layer, because the reducer can't.
- **Tool loop ≡ δ-reduction (FFI):** primitive redexes ship to the external
  oracle; the orientation clause "the repl's answer is ground truth" is an
  evaluation-strategy directive — don't soft-β what should be δ-reduced.
  API-surface confinement ≡ operand-residency applied to the instruction
  vocabulary: the model emits only δ-redexes already on the tape.
- **compact! ≡ memory-layout engineering:** affine-friendly (don't force
  re-duplication of content the tape held verbatim), operand placement
  respects recency kernels, binder mass preserved for anything the future
  must substitute. Gives compact-validation its mechanism-side failure
  predictions.

llm-repl is the harness face of this research: ab!/trampoline!/run-battery!
⊕ fork-the-past ≡ a differential-testing panel (frozen probes, forks as
controlled variables, receipts as trace). Findings about MODELS flow to
anima; findings about the HARNESS live here.

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
