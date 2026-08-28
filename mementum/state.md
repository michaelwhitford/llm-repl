---
type: Working Memory
title: Project State
---

# Project State

> Bootloader — a real 30-second read. Architecture ≡
> [design/architecture](knowledge/design/architecture.md) (authoritative).
> History ≡ `git log --oneline`. Verdicts ≡ `queue.md`. Detail ≡ knowledge/.

## What this is

llm-repl — an LLM chat completion as a branchable continuation: the tape
(`messages[]`) is an immutable, forkable VALUE; the repl is the PLACE tapes
live. Humans (TUI), models (self-eval), editors ≡ equal nREPL clients of one
persistent core. Two consumers: standalone tool (`bb llm-repl`) and library
for anima (`us.whitford/llm-repl`, `:complete-fn` injection). v0.2.0
tagged ∧ public; v0.3.0 complete ∧ PUSHED, `0.3.0-alpha` in ~/.m2 — the tag
∧ the anima move are the human's.

## Frontier

**Four arcs shipped 2026-08-28, all ✅** (ledgers ≡ queue.md § complete).
CI is real now: pushed, two green runs — the twin suite ∧ kondo ∧ schema
validation all exercised on ubuntu, not just this laptop.

- **v030-refactor** — code ≡ the ratified design (@`4cce4be` ⊕ 🎯`30c6f78`).
  The suite was born here: 0 → 138, now 170 (bb ∧ JVM twins, CI enforces).
- **compaction** — over-compaction CONFABULATES silently ⇒ validation ≡
  arm-diff, `:original` ≡ the only post-hoc ground truth.
- **trace-durability ⊕ tapeless-error-capture** — `.llm-repl/` ≡ a flight
  recorder (requests ∧ verbatim responses ∧ tool rounds ∧ `compact!`
  originals ∧ `tape.edn` recovery ∧ transcript `:seq` across restarts), and
  failed sends ALWAYS capture with ✗ receipts carrying `:io/ref` (decision 1
  AMENDED). Keys off CWD ≡ `/work` ⇒ writes through the container mount to
  the host. The HTTP 400 behind the amendment is still UNEXPLAINED — the
  ticket bought the instrument, not the answer.

**Knowledge:** `knowledge/design/` ≡ architecture ∧ library-contract ∧
trace-durability. `knowledge/` ≡ container ∧ attach-topology ∧ self-eval ∧
tui-design-rules ∧ compaction ∧ frames ∧ extension-horizon-pilot ∧
upstream/escapement. Traps ≡ `memories/` (18 pages, one insight each —
`git grep -- mementum/` before re-deriving; recall > re-derivation).

**Human moves open:** v0.3.0 tag when ready (release.yml deploys full/RC
only; library-contract hardens at first RC) · anima `:local/root` ∨
`0.3.0-alpha` · restart any LOCAL daemon predating `trace` (the container is
already on the current image).

**Next pickup (agent):** extension-horizon-pilot — read
[the page](knowledge/extension-horizon-pilot.md) FIRST (extension ≡
event(evaluate), depth-cliff ≈ within-pass budget, fork-differencing here;
handoff → verbum, Michael rules the freeze). Ready NOW: arm-diffs are
DURABLE, and the instrument is LIVE — container `llm-repl` @ 127.0.0.1:7899
(the repo's `.nrepl-port` points there), `:scratch` recovered at visit 2,
qwen3.6-35B-A3B behind it. Drive it over nREPL; `bounce!`/`trampoline!` cost
the tape nothing.

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
- Lint: `.clj-kondo/config.edn` PINS the levels it depends on — a dev's home
  config merges UNDER it and must never be able to lint weaker than CI.
- Config stickiness: open! persists; absence ≠ reset.
- Knowledge scope: mementum here ≡ llm-repl the INSTRUMENT; model findings
  gathered THROUGH it belong to anima.

## Queue

→ `mementum/queue.md` (prospective memory). Nothing in progress. Front:
extension-horizon-pilot → clojure-eval-per-form-values → agent-recipe-page.
