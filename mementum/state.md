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
public; v0.3.0 code-complete — tag is the human's; anima rides `:local/root`
until an official v0.3.0 jar is cut (~/.m2 alpha CLEARED 2026-08-29, no
stale artifact to shadow the live source).

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
stdout interleaved, nREPL shape). Suite now 210/709.

**Knowledge:** `knowledge/design/` ≡ architecture ∧ library-contract ∧
trace-durability. `knowledge/` ≡ container ∧ attach-topology ∧ self-eval ∧
tui-design-rules ∧ wire-protocol ∧ compaction ∧ frames ∧ fan-out-lineage ∧
upstream/escapement. Traps ≡ `memories/` (26 pages, one insight each — grep
before re-deriving; recall > re-derivation).

**First consumer landed 2026-08-29:** anima migrated to the library
(`:local/root`, thin adapter) — CLEAN: its full pre-migration llm-repl test
file passed UNCHANGED, live qwen probes work with `:complete-fn` injected.
Three seam findings ingested → queue; the HIGH one is SHIPPED:
✅ **library-config-inert-default** (D10 ratified ∧ built 2026-08-29 —
library INERT at require, config source ≡ DATA
`{:builtin}|{:map}|{:fn}|{:files}`, `init!` ≡ the one read (throws loud,
installs nothing on failure), `reload-config!` re-folds from the CURRENT
source; entrypoints read the chain explicitly; `init!`/`reload-config!`/
`config-sources` promoted to library-contract § 6; PRE-RC gate CLOSED).
✅ **open-defaults-create-only** (2026-08-30, D7 amendment ratified before
code): `:defaults` ≡ opts key seeding the CREATE branch only, under the
call's own overrides, inside the mutate! fn — the host's
snapshot→check→open! TOCTOU is gone; rides eval!/bounce!/trampoline!/
run-battery! (fork!/ab! excluded), NOT stored (unset! still re-seeds from
the chain), value schema ≡ session-opts-schema so a typo inside teaches.
Suite **231/799** bb ∧ JVM. Remaining: trace-capture-hook. Traps ≡
memories/ambient-config-leaks-into-embedding-hosts ∧
memories/entry-point-decides-armedness (D10 split armed-ness by ENTRY
POINT — `bb -e` inert vs `bb llm-repl` chain-read; assert
`(::repl/tools (default-config))` at probe start).

**Human moves open:** v0.3.0 tag (gate CLEAR, CI green on main) — anima
now rides the D8/D10/D11 core (re-migrated 2026-08-30), so the tag has a
consumer waiting on the jar · the projection's TUI
pass (Tab: right tape, no stale pane — agents have no TTY). Container
REBUILT on the D8/D10/D11 core (human, 2026-08-29); no daemon.

**Use case (human, 2026-08-29):** PRIMARY ≡ a smarter model drives a
dumber model through the repl for testing — headless, TUI barely used.
Weight accordingly: nREPL/eval ergonomics ∧ receipts-over-wire ∧
manual/recipe surface > TUI polish (tui-left-pane-collapse PARKED in
queue with findings; agent-recipe-page's eventual audience ≡ exactly
this use case).

**STRICTNESS ARC COMPLETE 6/6 (2026-08-29) — THE v0.3.0 TAG GATE IS
CLEAR** (tag ≡ human move). Final tranche, one session:
✅ manual-malli-schemas (D8: `guard/errors` pure fn ⊕ `defcommand`
defn-grammar, 16 conversions, coverage table-test over (manual) itself —
a future command cannot ship unguarded; kondo needs the macroexpand HOOK,
not :lint-as — D8 amended in-build) ⊕ ✅ **D11 ratified ∧ built same
session**: session config keys → `:us.whitford.llm-repl/*` (the :config
map ESCAPES via snapshot; collision-proof vs ANY keyword; api spells
`::model`, prompt users `::tools`, hosts `::repl/model`; file root ∧
:repl/* ∧ index ∧ tape keys stay bare — deferred by human rule).
BREAKING: v0.2.0 key spellings dead; anima re-migrated 2026-08-30 (✅, the
ratified step-function closed). Earlier tranche: ✅ tap-failure-receipts ·
✅ daemon-state-hygiene · ✅ term-state-chokepoint · ✅ registration-guards
· ✅ config-unset-semantics — full ledgers ≡ queue.md § complete. Suite
**227/780** at arc close (231/799 now — see :defaults above). New traps: memories/
jvm-macroexpand-wraps-macro-throws (CompilerException wrap, JVM-only).
Pushed 2026-08-29: CI GREEN (twin suite ∧ kondo WITH the hook — the
gitignore near-miss verified dead in the pipeline that would have hit it).
agent-recipe-page DEPRIORITIZED to queue bottom (design still settling).
Instrument LIVE ∧ CURRENT: container rebuilt on D8/D10/D11 core @
127.0.0.1:7899, mount `~/llm-repl-work` → `/work` — **NOT this repo**.
Read `memories/probe-hygiene-tools-armed` ∧ `entry-point-decides-armedness`
FIRST — armed-ness is a fact about the PROCESS, not the code.

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
  it). Config stickiness: open! persists; absence ≠ reset; `unset!` ≡ the
  release valve, `:defaults` ≡ the creation-only seed (never stored).
- Knowledge scope: mementum here ≡ llm-repl the INSTRUMENT; findings ABOUT
  models belong to anima. This llm-repl is BLACK-BOX (HTTP, text in/out);
  verbum's same-named llm-repl attaches as a TENSOR — forward-pass questions
  go THERE. Cost of missing it: one session (🚫 extension-horizon-pilot).
- Two-tier membrane (verbum's SUGGEST drop states it as NON-asks, and they
  are load-bearing): llm-repl carries TEXT ∧ DISTRIBUTIONS, nothing else.
  No white-box (hidden states · per-layer · attention ≡ verbum's python
  tier) · no reducer/oracle (grading lives with the caller: anima's kernel,
  verbum's lambda_ast) · no fleet management (launching/pointing N servers
  ≡ anima-side ops; the roster models aliases, not processes).

## Queue

→ `queue.md` (prospective memory). Nothing in progress. Strictness arc
DONE 6/6 — the tag gate is clear. ✅ anima-re-migration CLOSED 2026-08-31
(human's report: anima migrated to the current core 2026-08-30 — D11 keys
· D10 inert config · D8 error maps; evidence lives in anima's repo). The
step-function ran full circle: migrate → suggest → ingest → re-migrate.

**NEW WORK FRONT — the measurement surface (2026-08-31):** verbum's
SUGGEST drop ingested → six ⚪ entries (drop verbatim ≡ `git show
dcfa1d6:SUGGEST.md`). A SECOND consumer (verbum's calculus-census ⊕
anima's cartographer) wants llm-repl as the BEHAVIORAL-tier instrument:
drive N llama.cpp servers (instruct AND base ggufs), run probe corpora,
measure DISTRIBUTIONS (basin-equality ≡ KL-band on continuation
distributions — byte-grain is the wrong gate). The session plane audited
READY; what's missing is measurement. Priority per the drop:
⚪ logprobs-surface (P1, load-bearing) · ⚪ sampling-spec-knobs (P3, seed ≡
reproducibility gate) · ⚪ server-provenance (P5) make it RECORDABLE;
⚪ grammar-passthrough (P2) ∧ ⚪ raw-completion-mode (P4) complete it;
⚪ per-eval-caps (P6) minor. Cost re-priced by reading the code, NOT
assumed — memories/llama-wire-is-ours-request-is-open: the wire is ours
(`llama-wire`, pure) ∧ escapement's Request is an OPEN map ∧ `Response
:backend-metadata` is the modeled return slot ⇒ no upstream release for
any of them. The one HARD boundary is D4: `:complete-fn` returns TEXT,
so distributions have no channel to the caller — a ratification, not a
wire problem. Then the older seam tickets (trace-capture-hook ·
tool-loop-knobs) ⊕ ⚪ plain-loop-quit-synonyms (human ruling wanted).
