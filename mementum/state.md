---
type: Working Memory
title: Project State
---

# Project State

> Bootloader — a real 30-second read. Architecture ≡
> [design/architecture](knowledge/design/architecture.md) (authoritative).
> History ≡ `git log --oneline`. Details ≡ knowledge/ pages.

## What this is

llm-repl — an LLM chat completion as a branchable continuation: the tape
(`messages[]`) is an immutable, forkable VALUE; the repl is the PLACE tapes
live. Humans (TUI), models (self-eval), editors ≡ equal nREPL clients of one
persistent core. Two consumers: standalone tool (`bb llm-repl`) and library
for anima (`us.whitford/llm-repl` on Clojars, `:complete-fn` injection).
v0.2.0 tagged ∧ public ≡ the accrete-as-we-go baseline.

## Frontier

**v0.3.0 design RATIFIED @ `4cce4be`** — full-codebase assessment folded
into `design/architecture.md` (layers, ns map, decisions D1–D7, formal
shape, build/CI) ⊕ `design/library-contract.md` (stable surface for anima).
The refactor targets those documents. Knowledge metabolized out of this
file: [container](knowledge/container.md) ·
[attach-topology](knowledge/attach-topology.md) ·
[self-eval](knowledge/self-eval.md) ·
[tui-design-rules](knowledge/tui-design-rules.md).

**Next pickup: refactor step 1 — `tape` ns (extract ⊕ absorb chat-memory) ⊕
its tests.** Pure, zero risk, seeds the test suite. Full order in
architecture § refactor order.

## Live invariants (violable tomorrow — the rest live in the design)

- Registry stays EDN: `:complete-fn` injected per-call, NEVER stored; no
  fn/atom/record in a session (serialized over nREPL every view refresh).
- rf G1: eval-rf keeps the 1-arity completer. G2: eager drivers only.
- bb.edn ≡ deps.edn (bb primary, JVM superset; escapement via Clojars
  coordinate — changes upstream need a RELEASE).
- NO nucleus/boot seed in the repo — preamble ≡ config; the licensing
  boundary is ~/.config/llm-repl/config.edn. AGENTS.md with nucleus content
  stays UNTRACKED.
- nREPL ≡ unauthenticated eval — `:bind "0.0.0.0"` only behind a wall.
- Config stickiness: open! persists; absence ≠ reset
  (memories/config-stickiness).
- Knowledge scope: mementum here ≡ llm-repl the INSTRUMENT; model findings
  gathered THROUGH it belong to anima.

## Queue

→ `mementum/queue.md` (prospective memory — glyphed intentions, verdicts).
Selected next: 🔵 v030-refactor, step 1 (`tape` ns ⊕ tests).
