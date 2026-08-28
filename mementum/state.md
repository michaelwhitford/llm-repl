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

**Seven arcs shipped 2026-08-28** (ledgers ≡ queue.md § complete); CI is real
— twin suite ∧ kondo ∧ schema validation, green on ubuntu.

- **v030-refactor** — code ≡ the ratified design (@`4cce4be` ⊕ 🎯`30c6f78`).
  The suite was born here: 0 → 179 (bb ∧ JVM twins, CI enforces).
- **compaction** — over-compaction CONFABULATES silently ⇒ validation ≡
  arm-diff, `:original` ≡ the only post-hoc ground truth.
- **trace-durability ⊕ error-capture ⊕ send-ring** — `.llm-repl/` ≡ flight
  recorder keyed off CWD ≡ `/work`; failed sends ALWAYS capture (✗ receipts
  carry `:io/ref`); the SEND-RING (memory-only RATIFIED, crash-loss deliberate)
  records EVERY send — tapeless ✓ closed. Query ≡ eval. HTTP 400: UNEXPLAINED.
- **registry-fetch-projection** — the wire carries `registry/view` (index ⊕
  the FOCUSED tape, ONE deref), never every tape: 623.8KB → 36.0KB at n=300.
  Payload ∧ sockets ∧ the DECLINED push protocol ≡ knowledge/wire-protocol.md.
- **clojure-eval-per-form-values** — `clojure_eval` echoes `=> v` PER form,
  stdout interleaved (nREPL shape, copied); ✗ carries partial echo. 186/591.

**Knowledge:** `knowledge/design/` ≡ architecture ∧ library-contract ∧
trace-durability. `knowledge/` ≡ container ∧ attach-topology ∧ self-eval ∧
tui-design-rules ∧ wire-protocol ∧ compaction ∧ frames ∧ fan-out-lineage ∧
upstream/escapement. Traps ≡ `memories/` (24 pages, one insight each — grep
before re-deriving; recall > re-derivation).

**Human moves open:** v0.3.0 tag · anima `:local/root` ∨ `0.3.0-alpha` · the
projection's TUI pass (Tab: right tape, no stale pane — agents have no TTY).
Container core CURRENT (send-ring δ'd live); rebuild ≡ `./docker/container.sh`
(committed — build→replace→eval-gate); no daemon. Stale cores refuse.

**Next pickup:** agent-recipe-page (~half session, agent-ready) — the
manual seam feeds a recipe, not a component (mcp-facade verdict).
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
agent-recipe-page → manual-malli-schemas → split-pane-tape-view.
