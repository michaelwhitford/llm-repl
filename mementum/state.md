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

**Four arcs shipped 2026-08-28, all ✅** (ledgers ≡ queue.md § complete). CI
is real: two green runs — twin suite ∧ kondo ∧ schema validation on ubuntu.

- **v030-refactor** — code ≡ the ratified design (@`4cce4be` ⊕ 🎯`30c6f78`).
  The suite was born here: 0 → 138, now 170 (bb ∧ JVM twins, CI enforces).
- **compaction** — over-compaction CONFABULATES silently ⇒ validation ≡
  arm-diff, `:original` ≡ the only post-hoc ground truth.
- **trace-durability ⊕ tapeless-error-capture** — `.llm-repl/` ≡ a flight
  recorder (requests ∧ verbatim responses ∧ tool rounds ∧ `compact!`
  originals ∧ `tape.edn` recovery ∧ transcript `:seq` across restarts); failed
  sends ALWAYS capture, ✗ receipts carry `:io/ref` (decision 1 AMENDED). Keys
  off CWD ≡ `/work` ⇒ writes through the mount to the host. The HTTP 400
  behind the amendment is still UNEXPLAINED. **Scope, δ'd on disk 2026-08-28:**
  payloads are the `eval!` path only — `bounce!`/`trampoline!` ✓ are
  RECEIPT-ONLY (ratified: no tape index) → ⚪ tapeless-success-capture.

**Knowledge:** `knowledge/design/` ≡ architecture ∧ library-contract ∧
trace-durability. `knowledge/` ≡ container ∧ attach-topology ∧ self-eval ∧
tui-design-rules ∧ compaction ∧ frames ∧ fan-out-lineage ∧
upstream/escapement. Traps ≡
`memories/` (20 pages, one insight each —
`git grep -- mementum/` before re-deriving; recall > re-derivation).

**Human moves open:** v0.3.0 tag when ready (release.yml deploys full/RC
only; library-contract hardens at first RC) · anima `:local/root` ∨
`0.3.0-alpha` · restart any LOCAL daemon predating `trace` (the container is
already on the current image).

**Next pickup (agent):** registry-fetch-projection — the wire sends the WHOLE
registry (tapes) every version bump while `sessions-list` already exists
unused, 27× smaller; measured, scoped, all-internal, no ratification needed
(audit table ∧ the split-payload-not-round-trip rule ≡
knowledge/tui-design-rules.md). tapeless-success-capture sits behind it and
wants a D-decision first. Instrument LIVE: container
`llm-repl` @ 127.0.0.1:7899, mount ≡ `~/llm-repl-work` → `/work` — **NOT
this repo**; `:scratch` at visit 2, qwen3.6-35B-A3B, trace on. Probing a
model through it? Read `memories/probe-hygiene-tools-armed` FIRST.

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
  gathered THROUGH it belong to anima. This llm-repl is BLACK-BOX (HTTP
  completions, text in/out); verbum's llm-repl is a DIFFERENT instrument with
  the same name — attaches as a TENSOR (attention ∧ layers ∧ activations).
  Mechanism questions about what happens INSIDE a forward pass go THERE; a
  behavioral proxy built here is scaffolding around blindness verbum lacks.
  Cost of missing it, 2026-08-28: one session (🚫 extension-horizon-pilot).

## Queue

→ `mementum/queue.md` (prospective memory). Nothing in progress. Front:
registry-fetch-projection → tapeless-success-capture → clojure-eval-per-form-values.
