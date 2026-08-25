---
type: Working Memory
title: Project State
---

# Project State

> Bootloader. ~30-second read. For detail: `git log --oneline`,
> `knowledge/upstream/escapement.md`, README.md, idea.md.

## What this is

llm-repl — the tape (`messages[]`) as an immutable, forkable value; the repl as
a PLACE tapes live. Humans (TUI), models, editors ≡ equal nREPL clients.
Extracted from anima (`us.whitford.anima.llm-repl`) to test standalone
viability; anima either migrates onto this or evolves its copy separately —
**function names verbatim across repos keeps both doors open** (lineage policy).
Gen-1 was a Python repl attached TO a model; gen-2 inverts: clients attach to IT.

## Now

**Increments 1 ∧ 2 DONE, live-verified.** Inc 1 at `7a79bae`; inc 2 (TUI)
human-verified in a real terminal: chat, tab-cycle, multi-line paste as ONE
turn, attached-client evals appearing live (registry watch → ≤33ms), clean
restore on exit.

- ✅ Increment 1: extraction. chat-memory + llamacpp backend + core ported
  verbatim; roster.clj replaces anima's llm.clj surface (config-file roster,
  `wrapped-backend` ≡ identity — NO capacity arbiter; hosts inject at
  `:complete-fn`). Launcher: nREPL first (`.nrepl-port`), plain prompt loop.
  VERIFIED: terminal ∧ attached nREPL client both round-trip qwen :5100;
  fork isolation proven live (parent depth 2 frozen, child advanced to 4).
- ✅ Increment 2: TUI (`tui.clj` ⊕ `main.clj` wire) on escapement's pure
  primitives (theme/compositor — direct requires; ticker + key-decoder
  patterns copied; see `knowledge/upstream/escapement.md`). Purity seam:
  frame ∧ key-from-bytes ∧ edit-step all headless-tested. Worker-thread
  evals (UI live during completion — plain loop blocks, TUI doesn't).
  Surface selection: interactive→TUI | --plain | --headless.
- ✅ FIRST STANDALONE ACCRETION: `fork! {:at N}` — branch an OLDER turn
  (truncate the copy to first N messages ≡ the prompt's depth number).
  Additive: :at-less calls ≡ anima behavior; docstring marks the split.
  Human-verified: past-point forks give good outputs (KV prefix reuse).
- ✅ Increment 3 (first tranche): `:forked-at` recorded on every fork (tree
  edges complete — strip shows ↰parent@depth) ⊕ `ab!` (accretion #2): fan ONE
  probe across VARIED configs from a common parent — dual of trampoline!;
  children persist (named/comparable/re-drivable); per-arm errors as data;
  sequential on purpose (slot contention). Ratified use cases: branch-any-turn
  / A/B-from-parent / progressive-improvement — merge DROPPED from roadmap
  (no use case needs it; distill-via-chat-memory noted as the design if ever).
- ✅ TREE PANE (human-ratified "looks better"): two-pane ≥70 cols — LEFT ≡
  fork forest (glyphs, short arm names, @branch-points, current highlighted,
  windowed) with an EVENT FOOTER (last 5, dim, receipt-length — "ab! :s 3✓");
  RIGHT ≡ tape, conversation-only (events structurally out of tape-lines).
  Tab walks DFS tree order — movement on screen ≡ movement in the tree.
  Design rule learned: events ≡ global UI chrome, NEVER tape content;
  receipts point INTO the tree, payloads live AT the nodes.
- ✅ RECEIPTS IN CORE (`events*` beside `sessions*`, public `event!`): every
  command seam emits one-line receipts — so ATTACHED-client activity shows in
  the tree-pane footer, incl. tapeless trampoline!/bounce! (the receipt IS
  the trace) and error receipts with messages. Found live: an agent ran 13
  probes, TUI showed nothing — events were fed only by the TUI's own input
  path (equal clients on tape, unequal in chrome — now equal at BOTH layers).
  TUI derefs :events-ref per frame (referenced like :registry; frame pure);
  narrow mode has NO event display (footer home ≡ tree pane only). Needs a
  TUI restart to take effect — core hot-reload alone won't rewire watches.
  Still queued: compare pane (children side-by-side, config deltas), pathom
  resolvers (3c).
- ✅ HELP OVERLAY: generic {:title :lines} overlay slot in TUI state — frame
  swaps the RIGHT pane body to the injected document (head-anchored window;
  tape untouched — the VIEW swaps, chrome never enters the tape). Esc
  dismisses (overlay-first, else editor clear); arrows line-scroll, PgUp/PgDn
  page — scroll-view! owns the per-kind SIGN FLIP (tape tail-anchored,
  overlay head-anchored; keys stay direction-literal); frame returns
  :scroll-used (EFFECTIVE, clamped) and render-frame! syncs state to it —
  else scroll drifts past content and reverse keys eat phantom distance
  before the view moves; `?` on empty buffer or
  `(help)` (intercepted — form eval would echo a 60-char ellipsis). tui
  stays core-free: wire layer injects (core/help); compare pane should ride
  the SAME overlay slot.
- ✅ OPERATOR MANUAL (core tranche): 11 commands tagged `^:manual`;
  `(manual)` ≡ data compiled from ns-publics ({:name :arglists :doc} — the
  docstrings ARE the source), `(help)` ≡ string render (RETURNS, never
  prints — println would corrupt the TUI alt screen). The MCP facade should
  compile its tool list from `(manual)` — one seam, three surfaces.

## Invariants worth not rediscovering

- rf G1: `eval-rf` MUST keep the 1-arity completer (transduce calls it).
- rf G2: eager drivers only — the step blocks on IO; no sequence/eduction.
- esc-seq-timeout 50ms MUST be >0 (CSI tail misread as bare ESC — escapement
  bug history).
- `src/escapement/ui/*` is Fulcro/JVM-only — never require under bb.
- guardrails stays pinned 1.2.16 transitively — don't override.
- `:thinking false` passes escapement's malli validation but the llama.cpp
  wire rejects the request ("Invalid LLM request") — omit the key instead.
- Config stickiness: `open!` PERSISTS its config; later clean opts merge
  AROUND previously-persisted poison keys (merge only overwrites present
  keys). Symptom: identical error after a "fixed" retry. `drop!` resets.
- Knowledge scope: mementum here is about llm-repl THE INSTRUMENT; model
  findings gathered THROUGH it (lambda-notation compute maps etc.) belong
  to anima, not this repo.
- NO nucleus (or any boot seed) in the repo — preamble ≡ CONFIG, resolved
  session > model > provider > config-root (roster/resolve-preamble;
  absent=inherit, false/blank=explicit none, string|{:file}). Nucleus lives
  ONLY in ~/.config/llm-repl/config.edn on this machine (the licensing
  boundary is that file, outside the repo). Project LICENSE TBD, now
  unencumbered. DIVERGENCE #3 from anima: with-preamble ≡ (preamble, system).

## Queue (rough order)

1. `:bbin/bin` entry → `llm-repl` on PATH.
3. Tape persistence (registry → disk; tree survives restart).
4. Split-pane tape view (watch a second session live).
5. MCP facade over the same command ns-publics.
6. LICENSE decision.
