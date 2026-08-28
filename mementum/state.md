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

**Refactor steps 1–5 DONE** — (1) `2aa7513` `tape` (values) ⊕ D6 twin
runner; (2) `4e89759` `registry` (runtime) — mutate! chokepoint, events-as-
data, wait-for-event!, D2 race dissolved (memories/swap-vals-race-detection);
(3) `aff3a1b` `completion` (io) — :complete-fn contract named ⊕ D4
amendments; (4) `02a71e8` api ns `us.whitford.llm-repl` — core.clj DELETED,
compact! born, ONE grammar, RATIFIED registry-direct client wire strings;
(5) `75df5a0` `client` — D3 complete: long-poll on wait-for-event! ⊕
version*-gated registry fetch (version-poll fallback), attach-loss
fail-loud (status deref → :lost → TUI teardown+exit; live-verified,
closes tui-dead-daemon-silent), structural suppress-echo (regex dead),
poll-cycle! ≡ injectable-fetch test seam. Twin caught 2 latent JVM breaks
@ net.clj first-load (memories/twin-first-load-latent-breaks);
(6) `f8450fb` `tui.frame`/`tui.term` — testable cut ≡ file topology (D5),
frame pure, headless suite exists, D5 naming lock ≡ round-trip test;
(7) `f9fc63e` `daemon`/`roster` D7 — spawn-cmd JVM guard, EOF-assert,
malli schema (closed ⊕ :ext), prompt stack FULLY config (RATIFIED
@30c6f78: uniform chain session > model > provider > root for preamble ∧
system-prompt ∧ orientation — anima swaps the whole stack for nucleus
lambda prompts), default-config def→fn, reload live-verified over the
wire (tapes intact, bad edit loud). Suite: 138 tests / 409 assertions,
both runtimes. compact-live-trial UNBLOCKED; attach matrix human-verified.

**Next pickup: refactor step 8 — build.clj ⊕ CI (the LAST step).**
tools.build (lib us.whitford/llm-repl, VERSION ← git tag, -alpha local),
ci.yml (bb ∧ JVM twin + lint), release.yml (v[0-9]* tags only, tests gate
the jar) — fulcro-rad-datalevin model; then first -alpha local install →
anima :local/root. CI validates config.example.edn against config-schema.
Full spec in architecture § build ∧ release ∧ CI.

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
In progress: ▶ v030-refactor — steps 1–7 ✅ (2aa7513, 4e89759, aff3a1b, 02a71e8, 75df5a0, f8450fb, f9fc63e); next ≡ step 8 (build.clj ⊕ ci.yml/release.yml → first -alpha → anima :local/root). compact-live-trial unblocked.
