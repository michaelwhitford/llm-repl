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

**Design amended 2026-08-27 (live container troubleshooting):** D2 race +
interrupt-ghost live-confirmed; D4 grew slug-aware `:orientation`, structural
budget, loud empty-finals, manual malli schemas; D7 grew EOF-assert, config
schema (humanized errors), the config prompt stack (`:system-prompt` ∧
`:orientation`; open questions flagged for ratification); placeholders grew
trace-durability (escapement capture). Queue: ⚪ formal-config-malli ∧
⚪ trace-durability.

**Refactor steps 1–4 DONE** — (1) `2aa7513` `tape` (values) ⊕ D6 twin
runner; (2) `4e89759` `registry` (runtime) — mutate! chokepoint, events-as-
data, wait-for-event!, D2 race dissolved (memories/swap-vals-race-detection);
(3) `aff3a1b` `completion` (io) — :complete-fn contract named ⊕ D4
amendments ({slug} orientation, structural budget, loud empty-finals);
(4) `02a71e8` api ns `us.whitford.llm-repl` — core.clj DELETED, compact!
born (one-true-write: explicit-index race-free, every outcome a receipt),
ONE grammar (parse-submission) consumed by plain loop ∧ tui wire,
variant-slug exported (tui.frame consumes @ step 6). RATIFIED: registry-
direct — client wire strings fetch @…registry/* fully qualified, NO api
delegations. Suite: 94 tests / 234 assertions, both runtimes.
compact-live-trial (queue) is now UNBLOCKED.

**Next pickup: refactor step 5 — `client` version-poll ⊕ long-poll.**
RemoteCore rides registry/version* (poll tiny number, fetch on change) ⊕
wait-for-event! long-poll; structural suppress-echo (kill the :repl/id
regex at client.clj form) ⊕ attach-loss fail-loud (live-found: dead daemon
renders as live — memories/tui-dead-daemon-silent). Full order in
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
In progress: ▶ v030-refactor — steps 1–4 ✅ (2aa7513, 4e89759, aff3a1b, 02a71e8); next ≡ step 5 (client version-poll/long-poll, structural suppress-echo). compact-live-trial unblocked.
