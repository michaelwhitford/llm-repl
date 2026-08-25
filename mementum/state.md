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
  the SAME overlay slot. Form *out*/*err* is CAPTURED (raw stdout ≡ alt
  screen ≡ painted over): non-blank output → overlay titled with the form;
  the value stays a footer receipt. Rule of thumb: stdout NEVER survives in
  the TUI — every surface needs banner/output in its OWN idiom.
- ✅ OPERATOR MANUAL (core tranche): 13 commands tagged `^{:manual "human
  sentence"}` — the tag's VALUE is the curated human summary (bare true →
  docstring first line). TWO AUDIENCES, TWO TEXTS, ONE SEAM: docstrings stay
  maintainer/agent-dense; `(manual)` ≡ data ({:name :arglists :summary
  :doc}), `(help)` ≡ human render of :summary (RETURNS, never prints —
  println would corrupt the TUI alt screen). The MCP facade should compile
  its tool list from `(manual)` — :summary for tool descriptions, :doc for
  depth. OPEN SLOT: `manual-namespaces*` ⊕ `register-manual-ns!` — a surface
  with its own commands registers its ns at load (main does, for `use!`);
  banner ≡ (help) ≡ overlay ≡ facade, one compile.
- ✅ SELF-EVAL TOOL (accretion #3, live-verified): `:tools` config knob arms
  a tool loop — the model driven BY the repl becomes a client OF it (closes
  equal-clients: human ∧ editor ∧ model drive the same runtime). One tool,
  `:clojure/eval` (`tools.clj`): `load-string` in the HOST process — *out*
  captured, timeout ∧ truncation ∧ errors all AS DATA; description names the
  bootstrap move (require core → `(help)`), never enumerates (manual seam ≡
  truth). Registry `tools/tool-registry*` ≡ open slot (twin of
  `manual-namespaces*`) — hosts register more (anima: its granted app-query).
  Loop (`tool-complete`, sibling of `plain-complete`; `default-complete`
  routes): send ⊕ :tools → dispatch tool_use → tool_results → resend, until
  text ∨ budget(8 → teaching refusal, ONE final inference). Loop-LOCAL
  messages: the tape only ever sees user ⊕ final text — shape stable, prefix
  cacheable, chat-memory/compaction untouched, rf ∧ all four drivers
  unchanged (tools ride bounce!/trampoline!/battery! for free). Wire ≡ free:
  escapement models the whole vocabulary (`tools.protocol` ⊕ openai
  translator round-trips tool_calls; llamacpp backend rides them — probe ✓
  against qwen :5100). Every dispatch → `⚡ slug code-preview` receipt (the
  receipt IS the trace; payload persistence deferred). Live receipt: model
  computed Σ(p²) first-20-primes = 30007 via tool (CORRECT — the human-side
  verifier was the buggy one) and observed ITSELF mid-turn at depth 1
  (persist-user-first, seen from inside). ENVIRONMENT ORIENTATION
  (`tools-system`, human-ratified need): armed sessions get a system-prompt
  paragraph saying WHERE THE MODEL LIVES (tool descriptions carry mechanics;
  the system prompt carries identity — the chat template expands tool defs,
  it cannot provide situation). Appended in tool-complete, NEVER
  build-request: orientation rides iff defs are actually on the wire (a
  depth-guarded nested completion must not claim a tool it lacks; unarmed
  ab! arms stay clean). Live receipt: asked \"where are you running?\", the
  model EVALED its way to proof (java props, resolved core/help) — \"not a
  sandbox or simulation\". RESTART LESSON: sessions* is memory — arming via
  open! dies with the process (the human hit this). Config root `:tools`
  (roster/default-tools → default-config, twin of :default-model) makes
  armed-ness a MACHINE fact; per-session {:tools nil} still disarms.

## Invariants worth not rediscovering

- rf G1: `eval-rf` MUST keep the 1-arity completer (transduce calls it).
- rf G2: eager drivers only — the step blocks on IO; no sequence/eduction.
- esc-seq-timeout 50ms MUST be >0 (CSI tail misread as bare ESC — escapement
  bug history).
- `src/escapement/ui/*` is Fulcro/JVM-only — never require under bb.
- escapement ≡ Clojars artifact (1.0.1, no more :local/root): changes to
  escapement now require a RELEASE, not a sibling edit; bb.edn ∧ deps.edn
  carry the SAME coordinate (keep in sync).
- guardrails stays pinned 1.2.16 transitively — don't override.
- Session `{:thinking false}` WORKS (live-verified): build-request normalizes
  it → modeled `{:type :disabled}` → llamacpp `chat_template_kwargs
  {enable_thinking false}`; `true` ≡ omit ≡ server default (thinking ON).
  RECORD CORRECTED: the earlier note blamed "the wire" — wrong; raw `false`
  failed escapement's Request malli (validate-request returns errors-or-nil,
  the polarity I misread). Only :llamacpp reaches this switch — escapement's
  stock openai translator DROPS :thinking (why the custom backend exists;
  fully extracted from anima: thinking/cache_prompt/id_slot/max_tokens
  floor-guard all flow config→roster→backend, pure-verified).
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

**v0.2.0 TAGGED ∧ ANNOUNCED (Clojurians Slack) — the self-eval release:
:tools knob, clojure_eval, environment orientation, config machine-fact,
README § Self-eval. (v0.1.0 was 90a6c36 — pre-release, MIT, Clojars
escapement.) Feedback-driven from here; self-eval is the likely
conversation starter.**

1. `:bbin/bin` entry → `llm-repl` on PATH (DEFERRED until feedback says
   people actually want to install it).
2. Tape persistence (registry → disk; tree survives restart).
3. Split-pane tape view (watch a second session live).
4. MCP facade over the same command ns-publics (compile from `(manual)`).
5. Compare pane (rides the overlay slot) ⊕ pathom resolvers (3c).
