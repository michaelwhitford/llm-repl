---
type: Reference
title: Fan-out, lineage, and diffs — iterated search through ab!
status: active
related: [design/architecture, self-eval, tui-design-rules, ../memories/kv-prefix-fork]
---

# Fan-out, lineage, and diffs

> `ab!` is the finite-difference step: ONE probe across VARIED interpreters,
> children persisted. That makes iterated search (hill-climbing, GA, prompt
> evolution) a loop over `ab!` rather than a feature — the docstring already
> says it: *"continue any arm, fork the winner, fan again … the tree is the
> experiment record."* This page is the part the docstring can't hold:
> the non-obvious option, the measured costs, and where it bites.

## "Diff" is four things — three are free

| diff | source | cost |
|---|---|---|
| **genotype** (parent config vs child) | `:forked-from` ⊕ `[:config :system]` | free — string diff over registry data |
| **output** (one probe, N interpreters) | `ab!` → `{:repl/variants {vk result}}` | free — it IS the return value |
| **lineage** (who begat whom) | `:forked-from` ∧ `:forked-at` | free — the fork forest |
| **fitness delta** | you write it | **the hard part** |

The instrument gives dispatch and bookkeeping. It does not give fitness, and
fitness is ~80% of any search. Reaching for an LLM judge adds a *noisy
oracle to your own fitness landscape* — prefer a mechanical scorer against
known answers; if you can't write one, you don't yet have an experiment.

## The one non-obvious move: `{:at 0}`

Verified live 2026-08-28 (qwen36-35b-a3b, temp 0, tools disarmed):

```clojure
(ab! :ga       {:terse … :stepped … :checked …} probe)          ; gen 0
(ab! :ga-terse {:m1 … :m2 …}                    probe {:at 0})  ; gen 1
```

`:at 0` truncates the child's tape copy to zero messages. The next
generation answers the probe on a CLEAN tape while `:forked-from` still
records descent:

```
:ga           nil        "You are a helpful assistant."
:ga-terse     :ga        "Answer with only the final number."
:ga-terse-m1  :ga-terse  "Answer with only the final number, no currency symbol."
```

**Without `:at 0` every generation inherits its parent's conversation** and
you are scoring genotypes against a contaminated prefix — drift that looks
like selection pressure. Nothing warns you; the arms still run, the numbers
still move. This is the single move that separates a search from a mess.

## Lineage is DERIVED, so pruning destroys it

The mutation is never stored — only the resulting config is. You recover
"what changed" by diffing a child against its parent:

```clojure
(let [p (repl/snapshot :ga-terse) c (repl/snapshot :ga-terse-m1)]
  [(get-in p [:config :system]) (get-in c [:config :system])])
```

Corollary, and it is a real tension: **`drop!`-ing a losing arm severs the
diff chain of everything descended from it.** A search wants to prune the
population; the genealogy wants every ancestor kept. Same shape as git's
reachability GC — the fix, when someone needs it, is to make "keep" an
explicit act rather than making "prune" a destructive one.

## Cost shape (measured, not estimated)

| | per call | why |
|---|---|---|
| `trampoline!` bounce | ~170 ms | shares the tape's KV prefix |
| `ab!` arm (varying `:system`) | ~1.1 s | **varying `:system` IS varying the prefix** — full prefill, every arm |
| 3-arm `ab!` | 3.4 s | sequential by design (slot contention; determinism > speed) |

A 20-individual × 10-generation search ≈ **4 minutes wall clock** and ~390 KB
of registry. Compute is never the bottleneck.

**The registry is.** A 2-arm `ab!` produces **15 version bumps** (fork ⊕
user-append ⊕ assistant-append ⊕ events, per arm), and with a TUI attached
every bump can trigger a refetch of the WHOLE registry, tapes included
(`client.clj:183`). A 20×10 search is ~1500 bumps against a registry growing
to 390 KB — tens to hundreds of MB of wire, coalesced only by the poll cycle.
Headless (an agent over nREPL, nothing attached) pays none of it. → ⚪
registry-fetch-projection; iterated search is that ticket's canonical
workload, and tui-design-rules.md carries the audit.

## Other friction worth knowing before you hit it

- **Slug compounding.** `variant-slug` composes: ten generations of
  winner-forking gives a ten-segment slug. The tree pane strips prefixes so
  it *renders* fine; the keys get silly.
- **Single-probe fitness ties instantly.** In the verified run both gen-1
  arms scored 100. Selection needs a battery (`run-battery!`) and a
  tiebreak, not one probe.
- **Tools must be disarmed** or the model reaches for `clojure_eval` and you
  measure model+runtime → `memories/probe-hygiene-tools-armed`.
- **Tapeless drivers capture nothing on ✓.** Evaluate with `ab!`/`eval!` and
  the run is durable; evaluate with `trampoline!` and only receipts survive
  → ⚪ tapeless-success-capture.

## Where the hint lives, and why there

Split deliberately (λ ground: structure > instruction):

- **`ab!`'s docstring ⊕ `^:manual` summary** — the mechanics. `(manual)` is
  THE seam every agent surface derives from, and the model's bootstrap move
  is `require → (help)`. Mechanics belong where the model already looks.
- **`:orientation`** — existence only. It now names "N-arm counterfactual
  fans" among the commands and tells the model to read
  `(:doc (meta #'repl/CMD))` before driving one. It does NOT carry the
  recipe: orientation states WHERE the model lives and the bootstrap move;
  enumerating the surface there would drift the moment a docstring changes.
