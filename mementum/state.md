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

**Seven arcs shipped 2026-08-28** (full ledgers ≡ queue.md § complete); CI
real — twin suite ∧ kondo ∧ schema, green. Headlines: v030-refactor (code ≡
design @`4cce4be` ⊕ 🎯`30c6f78`; suite 0→179, bb ∧ JVM twins) ·
compaction (over-compaction CONFABULATES silently ⇒ arm-diff validation,
`:original` ≡ ground truth) · trace-durability ⊕ error-capture ⊕ send-ring
(`.llm-repl/` flight recorder @ CWD ≡ `/work`; ✗ ALWAYS captures, `:io/ref`;
send-ring memory-only RATIFIED; HTTP 400 UNEXPLAINED) ·
registry-fetch-projection (wire ≡ `registry/view`, 623.8→36.0KB @ n=300 ≡
knowledge/wire-protocol.md) · clojure-eval-per-form-values (`=> v` per form,
stdout interleaved, nREPL shape). Suite now 203/674.

**Knowledge:** `knowledge/design/` ≡ architecture ∧ library-contract ∧
trace-durability. `knowledge/` ≡ container ∧ attach-topology ∧ self-eval ∧
tui-design-rules ∧ wire-protocol ∧ compaction ∧ frames ∧ fan-out-lineage ∧
upstream/escapement. Traps ≡ `memories/` (25 pages, one insight each — grep
before re-deriving; recall > re-derivation).

**First consumer landed 2026-08-29:** anima migrated to the library
(`:local/root`, thin adapter) — CLEAN: its full pre-migration llm-repl test
file passed UNCHANGED, live qwen probes work with `:complete-fn` injected.
Three seam findings ingested → queue: **library-config-inert-default**
(HIGH, design ticket — require-time `config*` leaks the operator's machine
config into every embedding host, observed live as `:tools true` in anima's
session configs; PRE-RC gate) · open-defaults-create-only ·
trace-capture-hook. Trap ≡ memories/ambient-config-leaks-into-embedding-hosts.

**Human moves open:** v0.3.0 tag · the
projection's TUI pass (Tab: right tape, no stale pane — agents have no TTY).
Container core CURRENT (send-ring δ'd live); rebuild ≡ `./docker/container.sh`
(committed — build→replace→eval-gate); no daemon. Stale cores refuse.

**Next pickup:** the STRICTNESS ARC — full state audit 2026-08-29
(`knowledge/state-audit.md`: ~34 STRICT · ~13 MIXED · ~5 LOOSE; looseness
clusters at S2 SEAMS, worst ≡ the tap swallow — the audit channel itself
fails silently). **The v0.3.0 tag WAITS on this arc** (human) — ALL DESIGN
RATIFIED (D7-amend `unset!` · D8 `defcommand` · D9 boundary-idiom rule:
data only where the return is READ, discarded-return side effects THROW,
throws are model-safe via tools.clj:89). SHIPPED 2026-08-29:
✅ tap-failure-receipts (`run-tap!` disarm-on-throw ⊕ `:tap-disarmed`
receipt) · ✅ daemon-state-hygiene (atomic `write-state!`, corrupt →
`.corrupt` aside ⊕ loud, `clean-state!` reports failures, ONE
`read-port-file`) · ✅ term-state-chokepoint (`update-state!` ⊕ closed
`state-keys`, 13 sites rewired, main's reaches → named mutators, term's
first test ns); suite 203/674. Three BUILD tickets remain:
registration-guards → config-unset-semantics ⊕ manual-malli-schemas (D8 RATIFIED: `defcommand` defn-grammar
attr-map, guard/errors pure fn, :catn, opts ← config-schema; guardrails
rejected → memories/guardrails-is-not-a-boundary-guard).
agent-recipe-page DEPRIORITIZED to queue bottom (design still settling).
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

→ `queue.md` (prospective memory). Nothing in progress. Front ≡ the
strictness arc (tag gate), 3/6 done: registration-guards →
config-unset-semantics → manual-malli-schemas.
