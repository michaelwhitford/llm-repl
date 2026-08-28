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
for anima (`us.whitford/llm-repl`, `:complete-fn` injection). v0.2.0 tagged ∧
public; v0.3.0 complete, `0.3.0-alpha` in ~/.m2 — tag ∧ anima are the human's.

## Frontier

**Five arcs shipped 2026-08-28** (ledgers ≡ queue.md § complete); CI is real
— twin suite ∧ kondo ∧ schema validation, green on ubuntu.

- **v030-refactor** — code ≡ the ratified design (@`4cce4be` ⊕ 🎯`30c6f78`).
  The suite was born here: 0 → 179 (bb ∧ JVM twins, CI enforces).
- **compaction** — over-compaction CONFABULATES silently ⇒ validation ≡
  arm-diff, `:original` ≡ the only post-hoc ground truth.
- **trace-durability ⊕ tapeless-error-capture** — `.llm-repl/` ≡ a flight
  recorder keyed off CWD ≡ `/work`; failed sends ALWAYS capture, ✗ receipts
  carry `:io/ref`. Payloads are the `eval!` path ONLY (→ ⚪ tapeless-success-
  capture). The HTTP 400 behind it: still UNEXPLAINED.
- **registry-fetch-projection** — the wire carries `registry/view` (index ⊕
  the FOCUSED tape, ONE deref), never every tape: 623.8KB → 36.0KB at n=300.
  Payload ∧ sockets ∧ the DECLINED push protocol ≡ knowledge/wire-protocol.md.

**Knowledge:** `knowledge/design/` ≡ architecture ∧ library-contract ∧
trace-durability. `knowledge/` ≡ container ∧ attach-topology ∧ self-eval ∧
tui-design-rules ∧ wire-protocol ∧ compaction ∧ frames ∧ fan-out-lineage ∧
upstream/escapement. Traps ≡ `memories/` (23 pages, one insight each — grep
before re-deriving; recall > re-derivation).

**Human moves open:** v0.3.0 tag when ready · anima `:local/root` ∨
`0.3.0-alpha` · **restart every core, container included — they predate
`registry/view`; a new TUI refuses them at attach, with the reason** · the
projection's TUI pass (Tab: right tape, no stale pane — agents have no TTY).

**Next pickup (agent):** clojure-eval-per-form-values, now nearly free —
nREPL emits one `value` frame PER TOP-LEVEL FORM (measured,
`memories/nrepl-streams-out-and-values-per-form`): copy that shape, don't
invent one. tapeless-success-capture still wants its human D-decision.
Instrument LIVE: container @ 127.0.0.1:7899, mount `~/llm-repl-work` →
`/work` — **NOT this repo**. Probing a model through it? Read
`memories/probe-hygiene-tools-armed` FIRST.

## Live invariants (violable tomorrow — the rest live in the design)

- Registry stays EDN: `:complete-fn` injected per-call, NEVER stored; no
  fn/atom/record in a session (serialized over nREPL every view refresh).
- The wire sends a PROJECTION (`registry/view`), never `@sessions*`; index ∧
  focused tape ride ONE payload (split the payload, never the round-trip).
  Focus is the CLIENT's; 3 sockets is the FLOOR — bb serializes a
  connection's evals, so sockets are the only multiplex.
- rf G1: eval-rf keeps the 1-arity completer. G2: eager drivers only.
- bb.edn ≡ deps.edn (bb primary, JVM superset; escapement via Clojars —
  upstream changes need a RELEASE).
- NO nucleus in the repo (AGENTS.md λ scope); THIS AGENTS.md stays UNTRACKED.
- nREPL ≡ unauthenticated eval — `:bind "0.0.0.0"` only behind a wall.
- Lint: `.clj-kondo/config.edn` PINS its levels (a home config merges UNDER
  it). Config stickiness: open! persists; absence ≠ reset.
- Knowledge scope: mementum here ≡ llm-repl the INSTRUMENT; findings ABOUT
  models belong to anima. This llm-repl is BLACK-BOX (HTTP, text in/out);
  verbum's same-named llm-repl attaches as a TENSOR — forward-pass questions
  go THERE. Cost of missing it: one session (🚫 extension-horizon-pilot).

## Queue

→ `queue.md` (prospective memory). Nothing in progress. Front:
clojure-eval-per-form-values → tapeless-success-capture (human D) → recipe.
