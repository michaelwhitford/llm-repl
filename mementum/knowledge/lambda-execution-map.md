---
type: Knowledge
title: λ-notation execution map — what models actually compute
---

# λ-notation execution map

> First model-drives-model experiment run THROUGH llm-repl (the tool's design
> purpose: runtime agents attach over nREPL, fan probes at local models, grade
> receipts). Method: `trampoline!` ≡ varied probes off a fixed kernel (KV
> reuse, bounces discarded); `ab!` ≡ one probe across varied interpreters
> (children persist). Temp 0. Kernel ≡ THREE bare definitions, **no English
> explanation of the semantics** — the honest condition; nucleus never
> explains its notation either.

## Subjects

qwen36-35b-a3b ∧ gemma-4-31b-it (local llama.cpp, roster config).
**Identical behavior on every probe** — findings are properties of the
notation, not model quirks.

## The map

| construct | compute | evidence |
|---|---|---|
| `→` rewrite | eager, executes | `inc2(5)→7` |
| arithmetic | real compute, ¬recall | `acc(14)=40` — 5 unrollings step 3, unmemorizable |
| numeric `>` `≤` `=` | strict, boundary-exact | `10>10→false→:mid`, `5>5→false→:small` |
| `\|` guards | ordered first-match | `size(7)→:mid` ¬`:small` |
| `∧` `¬` | executes | `both(false,true)→:right` |
| `∀x∈` | maps over collection | `[:small :small :mid]` |
| higher-order | executes, composes | `twice(inc2,10)→14`, `size(twice(inc2,3))→:mid` |
| recursion | unrolls ≥7, incl. mutual | `fact(6)→720`, `even?(7)→false` |
| preference `>` | **lazy — ¬eagerly reduced** | see below |

## The gem: preference `>` is a lazy ranking

`λ fix(bug). cause(structural) → redesign > patch | cause(local) → patch`
with a structural bug:

- unforced → BOTH models return `"redesign > patch"` **verbatim** — the guard
  dispatched (structural branch chosen) but the preference stayed symbolic.
- forced ("ONE word — the action") → BOTH resolve `"redesign"`.

Preference `>` ≡ ranking-as-value: understood, held unreduced in context,
collapsed correctly only when a decision point demands it. Same overloaded
glyph resolved arithmetically in numeric context in the SAME system prompt —
models context-switch the two readings without confusion.

**Nucleus design implication:** policy rankings survive in context until the
moment of action — which is the right semantics for an agent. Don't expect
`a > b` to self-reduce in a reply; force the choice when you need an act.

## Tool findings (driving llm-repl over nREPL)

- `:thinking false` passes escapement's malli validation but the llama.cpp
  wire rejects the request ("Invalid LLM request"). Omit the key instead.
- **Config stickiness:** `open!` persists its config; a later call with clean
  opts merges AROUND previously-persisted poison keys (merge only overwrites
  present keys). `drop!` is the reset. Symptom: identical error after a
  "fixed" retry.
- Per-bounce/per-arm error-as-data made both failures cheap to see — the
  receipts named the exact seam.

## Re-drive it

Sessions `:lambda` (qwen kernel), `:lambda-gemma`, `:lambda-nucleus` persist
in the registry (until restart). The kernel:

```
λ inc2(x). x → x+2
λ size(x). x>10 → :big | x>5 → :mid | :small
λ both(p,q). p ∧ ¬q → :left | ¬p ∧ q → :right | :neither
```

Open questions for the next fan: reduction-depth limits (where does unrolling
break — fact(12)? acc(50)?), preference CHAINS (`a > b > c`), `∃`/`≻`/`⊕`
glyphs, whether thinking-enabled changes the map, smaller models.
