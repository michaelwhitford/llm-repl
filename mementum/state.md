---
type: Working Memory
title: Project State
---

# Project State

> Bootloader — a real 30-second read. Architecture ≡
> [design/architecture](knowledge/design/architecture.md) (authoritative).
> History ≡ `git log --oneline`. Verdicts ∧ intentions ≡ `queue.md`.
> Traps ≡ `memories/` (~30 pages, one insight each — grep BEFORE
> re-deriving). Frontier only lives here; when an arc closes, its ledger
> goes to queue.md § complete and this file SHRINKS.
>
> Detail, so a search is never blind: `knowledge/design/` ≡ architecture ∧
> library-contract ∧ trace-durability (RATIFIED targets — the code refactors
> TO them). `knowledge/` ≡ container · attach-topology · self-eval ·
> tui-design-rules · wire-protocol · compaction · frames · fan-out-lineage ·
> state-audit · upstream/escapement (VSM ≡ a generative seed, λ contracts).

## What this is

llm-repl — an LLM chat completion as a branchable continuation: the tape
(`messages[]`) is an immutable, forkable VALUE; the repl is the PLACE tapes
live. Humans (TUI), models (self-eval), editors ≡ equal nREPL clients of one
persistent core. Two consumers: the standalone tool (`bb llm-repl`) and the
library (`us.whitford/llm-repl`, `:complete-fn` injection).

## Where it stands (2026-08-31)

**v0.3.0 is code-complete; the tag is the human's move** — gate CLEAR, CI
green on main (twin suite ∧ kondo-with-the-hook ∧ config schema). Suite
**231/799** under bb ∧ JVM. The code ≡ the ratified design (architecture.md
D1–D11); eleven arcs' ledgers ≡ queue.md § complete.

**Consumers, both live.** anima re-migrated 2026-08-30 onto the D8/D10/D11
core — no issues, and the changes mostly made ANIMA smaller (the contract
absorbing host code; a consumer waiting on a real jar, `:local/root` until
then — the ~/.m2 alpha was CLEARED, nothing stale can shadow the source).
verbum/cartographer is consumer #2 and asks for the MEASUREMENT SURFACE.

**Work front ≡ the measurement surface** (6 ⚪ in queue.md, ingested
2026-08-31 from verbum's drop ≡ `git show dcfa1d6:SUGGEST.md`): drive N
llama.cpp servers (instruct AND base ggufs), run probe corpora, measure
DISTRIBUTIONS — basin-equality ≡ KL-band on continuation distributions,
byte-grain is the wrong gate. logprobs (P1) is load-bearing; +seed (P3)
+provenance (P5) make runs RECORDABLE; grammar (P2) ∧ raw-mode (P4)
complete it. Cost RE-PRICED against the code, not assumed
(memories/llama-wire-is-ours-request-is-open): the wire is ours ∧ pure,
Request is an OPEN map, `:backend-metadata` is the return slot ⇒ NO
upstream release. The one hard boundary is **D4: `:complete-fn` returns
TEXT**, so a distribution has no channel to the caller — ratification
first, three options named in the ticket.

**Use case (human, 2026-08-29), weight accordingly:** PRIMARY ≡ a smarter
model drives a dumber model through the repl for testing — headless, TUI
barely used. nREPL/eval ergonomics ∧ receipts-over-wire ∧ manual/recipe
surface > TUI polish.

**Instrument LIVE ∧ CURRENT:** container on the D8/D10/D11 core @
127.0.0.1:7899, mount `~/llm-repl-work` → `/work` — **NOT this repo**; no
daemon. Before ANY probe read `memories/probe-hygiene-tools-armed` ∧
`entry-point-decides-armedness` — armed-ness is a fact about the PROCESS,
not the code. Open unknown, instrumented but unexplained: the HTTP 400
that motivated error-capture (queue.md § complete, tapeless-error-capture).

**Other human moves open:** the projection's TUI pass (Tab: right tape, no
stale pane — agents have no TTY).

## Live invariants (violable tomorrow — the rest live in the design)

- Registry stays EDN: `:complete-fn` injected per-call, NEVER stored; no
  fn/atom/record in a session (serialized over nREPL every view refresh).
- The wire sends a PROJECTION (`registry/view`), never `@sessions*`; index ∧
  focused tape ride ONE payload (split the payload, never the round-trip).
  Focus is the CLIENT's; 3 sockets is the FLOOR — bb serializes a
  connection's evals, so sockets are the only multiplex.
- rf G1: eval-rf keeps the 1-arity completer. G2: eager drivers only.
- bb.edn ≡ deps.edn (bb primary, JVM superset). escapement ≡ Clojars: an
  upstream change needs a RELEASE — but `llm.llamacpp` is OURS, in-repo,
  and its `Request` is an open map (see the memory: llama.cpp knobs are
  local work, not upstream work).
- NO nucleus in the repo (AGENTS.md λ scope); THIS AGENTS.md stays UNTRACKED.
- nREPL ≡ unauthenticated eval — `:bind "0.0.0.0"` only behind a wall.
- Lint: `.clj-kondo/config.edn` PINS its levels (a home config merges UNDER
  it). Config stickiness: open! persists; absence ≠ reset; `unset!` ≡ the
  release valve, `:defaults` ≡ the creation-only seed (never stored).
- Knowledge scope: mementum here ≡ llm-repl the INSTRUMENT; findings ABOUT
  models belong to anima. This llm-repl is BLACK-BOX (HTTP, text in/out);
  verbum's same-named llm-repl attaches as a TENSOR — forward-pass questions
  go THERE. Cost of missing it: one session (🚫 extension-horizon-pilot).
- Two-tier membrane (verbum's drop states it as NON-asks; load-bearing):
  llm-repl carries TEXT ∧ DISTRIBUTIONS, nothing else. No white-box (hidden
  states · per-layer · attention ≡ verbum's python tier) · no reducer/oracle
  (grading lives with the caller) · no fleet management (roster models
  aliases, not processes).

## Queue

→ `queue.md`. Nothing in progress. Front ≡ the measurement surface
(⚪ logprobs-surface first — its D4 ratification gates the rest), then the
older seam tickets (trace-capture-hook · tool-loop-knobs) ⊕
⚪ plain-loop-quit-synonyms (human ruling wanted).
