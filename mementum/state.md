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

**v0.3.0 REFACTOR COMPLETE** (▶→✅ v030-refactor, 2026-08-28) — all 8
steps landed; the code ≡ the ratified design (`design/architecture.md` @
`4cce4be` ⊕ ratifications 🎯 `30c6f78`). Suite 0 → 138 tests / 409
assertions, bb ∧ JVM twins, CI enforces both (ci.yml ⊕ release.yml live).
`0.3.0-alpha` installed to ~/.m2 ∧ load-verified from a foreign dir (the
anima consumption path). Step ledger ≡ queue.md ✅ v030-refactor; history ≡
`git log --oneline`. Knowledge pages: [container](knowledge/container.md) ·
[attach-topology](knowledge/attach-topology.md) ·
[self-eval](knowledge/self-eval.md) ·
[tui-design-rules](knowledge/tui-design-rules.md).

**Notable post-ratification decisions (all in design doc):** registry-direct
client wire strings (step 4) · attach-loss fail-loud rides client (step 5,
closes tui-dead-daemon-silent) · D5 naming lock ≡ round-trip test (step 6) ·
prompt stack FULLY config, uniform chain, closed schema ⊕ :ext (step 7,
🎯 30c6f78 — anima swaps the whole stack for nucleus lambda prompts).

**Human moves now open:** push main (first CI run) · v0.3.0 tag when ready
(release.yml deploys full/RC only; library-contract hardens at first RC) ·
anima `:local/root` ∨ `0.3.0-alpha` from ~/.m2.

**Next pickup (agent):** queue front — tape-persistence ∨ trace-durability
∨ agent-recipe-page. Compaction arc COMPLETE (trial ⊕ validation ✅
2026-08-28): self-compaction one-shots with an explicit size budget;
over-compaction confabulates silently (memories/
compaction-confabulates-not-forgets — validation ≡ arm-diff, λ ≡ keep
binders). trace-durability gains urgency: durable traces are the audit
surface for silent confabulation.

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
Nothing in progress — v030-refactor ✅ ∧ compaction arc ✅ (trial ⊕
validation, verdicts in queue.md). Front of queue: tape-persistence ∨
trace-durability ∨ agent-recipe-page.
