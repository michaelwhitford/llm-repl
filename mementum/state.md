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
tagged ∧ public; v0.3.0 refactor complete, `0.3.0-alpha` in ~/.m2 — awaiting
the human's push/tag/anima moves.

## Frontier

**Three arcs shipped 2026-08-28, all ✅** (ledgers ≡ queue.md § complete):

- **v030-refactor** — the code ≡ the ratified design (`design/architecture`
  @ `4cce4be` ⊕ 🎯 `30c6f78`). Suite 0 → 138, bb ∧ JVM twins, CI enforces
  both. `0.3.0-alpha` load-verified from a foreign dir (the anima path).
- **compaction** — `compact!` exercised by the machine it rewrites. Over-
  compaction CONFABULATES silently ⇒ validation ≡ arm-diff, `:original` ≡
  the only post-hoc ground truth ([compaction](knowledge/compaction.md)).
- **trace-durability** — the daemon has a flight recorder: `.llm-repl/`
  holds requests/responses (thinking survives), tool rounds ∧ results,
  `compact!` originals, `tape.edn` (auto-recovered at boot), transcript
  JSONL (`:seq` continuous across restarts). Tapeless drivers receipt-only.
  CONTAINER-VERIFIED: `.llm-repl/` keys off CWD ≡ `/work`, so the recorder
  writes through the mount to the host ∧ `:io/ref` resolves from macOS —
  zero container-specific code ([container](knowledge/container.md)).

**Knowledge:** `knowledge/design/` ≡ [architecture](knowledge/design/architecture.md)
· [library-contract](knowledge/design/library-contract.md) ·
[trace-durability](knowledge/design/trace-durability.md). Operational ≡
[container](knowledge/container.md) · [attach-topology](knowledge/attach-topology.md)
· [self-eval](knowledge/self-eval.md) · [tui-design-rules](knowledge/tui-design-rules.md)
· [compaction](knowledge/compaction.md) · [frames](knowledge/frames.md) ·
[extension-horizon-pilot](knowledge/extension-horizon-pilot.md). Traps ≡
`memories/` (18 pages, one insight each — `git grep` before re-deriving).

**Human moves open:** v0.3.0 tag when ready (release.yml deploys full/RC
only; library-contract hardens at first RC) · anima `:local/root` ∨
`0.3.0-alpha` · rebuild any image ∧ restart any daemon predating `trace`.

**Next pickup (agent):** extension-horizon-pilot — read
[the page](knowledge/extension-horizon-pilot.md) FIRST (extension ≡
event(evaluate), depth-cliff ≈ within-pass budget, fork-differencing
buildable here; handoff → verbum; trace-durability landed ⇒ arm-diffs are
DURABLE now).

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
extension-horizon-pilot → tapeless-error-capture (AMENDS a ratified
decision) ∧ clojure-eval-per-form-values → agent-recipe-page.
