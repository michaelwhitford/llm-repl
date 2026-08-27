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

**FRONTIER — TUI ≡ PURE nREPL CLIENT; per-project DAEMON model (this tranche,
verified programmatically; live TUI-over-container human-verified, TUI-over-
local-daemon still needs a terminal pass).** The last inequality is gone: the
TUI was the ONE surface that ran IN-PROCESS with core; now it ALWAYS attaches
over nREPL — local daemon or container, same wire. Equal-clients thesis
STRUCTURAL, not aspirational: humans, models, editors, AND the TUI all drive the
same core over the same wire. `LocalCore` DELETED — there is no in-process
TUI+core path left.

- ✅ `net.clj` — a ~30-line nREPL CLIENT over `bencode.core` (BUNDLED in bb AND
  a transitive dep of nrepl.server on the JVM — so, unlike start-nrepl!, NO
  bb?/JVM branch: one impl, both runtimes, ZERO new deps). connect/clone-session
  /eval-msg (gathers frames till status "done" → {:value :out :err :status :ex})
  /close. The user's instinct killed a dep question — bb ships the codec, not a
  client; the client is trivial on top.
- ✅ `client.clj` — the core-client SEAM the TUI drives (never core directly).
  ONE impl now: `RemoteCore` (protocol kept as an OPEN SLOT). Submissions →
  nREPL eval messages (the clojure half runs WHERE THE CORE LIVES, next to the
  work dir ∧ model; prose turns fire there too); the view is a CACHE ATOM a
  ~150ms poll thread keeps fresh (phase-1). KEY SHAPE: `registry`/`events` are
  DEREF-ABLES — local passed core atoms, remote passes cache atoms — so the TUI
  frame is BYTE-FOR-BYTE UNCHANGED (tui/start! can't tell it's remote). `(use!
  …)` intercepted in the wire layer (focus ≡ LOCAL-surface concern, never sent
  across); `(help)` intercepted; form :suppress-echo? when the value carries
  :repl/id (no doubled receipt). GREEN LIGHT that made it clean: `@sessions*` is
  pure EDN (`:complete-fn` injected per-call, NEVER stored) → round-trips over
  the wire; remote form eval is a WIN (nREPL captures *out* the local path
  hand-rolled).
- ✅ `daemon.clj` — per-project local daemon lifecycle. The core is a PERSISTENT
  separate process; the TUI attaches/detaches. Per-project keyed by CWD (like a
  normal clj repl), discovered by the `.nrepl-port` it already drops there;
  pid co-located in `<proj>/.llm-repl/daemon.edn` {:pid :port :cwd :started-at}
  (+ `daemon.log`). spawn! reinvokes the SAME bb.edn (`babashka.config`; deps-
  root ≡ its parent) `nrepl` task via the VERIFIED macOS detach incantation:
  `sh -c 'nohup bb … nrepl >…/daemon.log 2>&1 & echo $!'` — nohup ignores
  SIGHUP, grandchild reparents to launchd (PPID 1), sh's stdout ≡ the pid.
  RETIRED THE RISK LIVE: survives spawner exit AND SIGHUP; SIGTERM stops it.
  discover cleans stale (pid gone ∨ port dead) → spawn fresh; ensure! → [state
  fresh?]; stop! SIGTERMs the recorded pid (NEVER a container); status →
  +:alive? +:uptime. The container's plain `bb nrepl` NEVER writes daemon.edn —
  container ∧ local state can't collide.
- ✅ `main.clj` rewire — `bb llm-repl`: `:attach` set → attach the container
  (CONTRACT: unreachable ⇒ FAIL LOUD ∧ exit, no silent local fallback — a fresh
  empty session would mask a down container as lost state); else discover-or-
  spawn THIS project's daemon → attach over loopback. Quit ≡ DETACH (client
  sockets close, TUI process exits, daemon KEEPS RUNNING — `bb stop` ends it;
  reattach finds tapes intact). `--headless` ≡ the DAEMON body (also `bb nrepl`
  ∧ the container): start nREPL, open scratch, park — NEVER consults :attach
  (so a container never self-attaches). `--plain` ≡ in-process debug loop. Live-
  proven: spawn→attach→detach→daemon survives→reattach sees persisted session→
  stop.
- ✅ `:attach` config + `bb start`/`bb stop`/`bb status` tasks. `:attach` is a
  string "host:port"/"port", {:host :port}, `true` (≡ ./.nrepl-port), or false/
  absent (≡ local). GLOBAL vs PER-PROJECT: a project's ./config.edn `{:attach
  false}` opts OUT of a global container attach (config chain: later wins).
  Resolution split by layer: `roster/attach-spec` (config→spec) ∘
  `daemon/attach-target` (spec→[host port], blank→./.nrepl-port). bb tasks touch
  ONLY the local daemon, NEVER a container (podman owns those; each container
  subsystem has its own start/stop). `bb status` ALSO shows the `:attach` remote
  + REACHABLE/UNREACHABLE (reflects what `bb llm-repl` would attach to);
  reachable? is host-aware; status pulls config LAZILY (requiring-resolve) so
  daemon stays a low-level ns (bb stop/start ≈ 0.02s).

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
- ✅ CROSS-MACHINE REPL (`:nrepl {:bind}` ⊕ `:model/host`): two additive
  knobs let the repl leave loopback. `:bind` (default 127.0.0.1 — native
  topology unchanged) threads through BOTH start-nrepl! branches (bb ∧ JVM);
  banner reports the ACTUAL bind, not a hardcoded loopback claim. "0.0.0.0"
  opens attach beyond loopback — docstring names the contract (nREPL ≡
  UNAUTHENTICATED eval; open only behind a wall). `:model/host` (default
  localhost) builds roster's base-url — containerized repl names the host
  gateway, a LAN llama.cpp box its hostname; additive twin of :model/port.
  builtin-defaults carries :bind explicitly (per-key :nrepl merge stays
  honest). Prereq for docker/; live-verified both runtimes from a foreign
  CWD (bb ∧ JVM bind *:7899 with the knob, 127.0.0.1 without).
- ✅ DOCKER (`docker/` — the CONTAINER is the sandbox wall; `/work` is the
  seam): plain OCI Dockerfile (podman ∧ docker identical, no BuildKit-isms) —
  bb base (`ghcr.io/babashka/babashka`) ⊕ headless JRE (bb is the RUNTIME but
  deps RESOLUTION is a JVM program — `bb prepare` dies without java; the JRE
  also lets a runtime re-resolve degrade instead of crash), non-root `repl`
  user (defense-in-depth: armed eval has NO in-process sandbox — load-string
  full power — so the wall IS the sandbox), deps warmed as their own layer
  (source edits rebuild in seconds, offline at runtime). ENTRYPOINT `bb
  --config /app/bb.edn --deps-root /app` keeps bb.edn the ONE invocation seam
  (Dockerfile never learns main's coordinates); WORKDIR `/work` is the mount
  seam — everything keying off CWD crosses here: `.nrepl-port` lands host-side
  (editor auto-attach), `./config.edn` read from here (later-wins over
  ~/.config), files the model evals into existence appear here. THE ONE
  DELIBERATE HOLE in the wall — user-chosen, user-sized. CMD `nrepl` (headless
  attach-and-drive); TUI ≡ `-it … llm-repl` (surface swap, same image). Config
  NEVER baked in (nucleus/licensing boundary rides the mounted config, outside
  repo ∧ image). docker/config.edn ≡ the container contract as example: FIXED
  port 7899 (7888 collides with the classic editor-nREPL default — found
  live), :bind "0.0.0.0" inside, published loopback-only 1:1 (127.0.0.1:7899:
  7899) so the .nrepl-port stays truthful host-side; models point at
  host.containers.internal (podman's host gateway). RUNNING NOW: image built,
  container up with a host work dir bind-mounted at /work. Live-verified under
  podman machine (macOS): non-root mount write ✓, .nrepl-port → host ✓, eval
  round-trip 30007 ✓, host gateway reaches llama.cpp :5100 ✓, FULL completion
  through the wall (open! ⊕ eval! → "containment verified" @ depth 2).
- ✅ KONDO / `p/await!`: statecharts.promise bridges to promesa as a SOFT dep
  — `await!` is created by `(intern *ns* 'await! (resolve 'promesa.core/
  await!))` at LOAD time, so static analysis finds no definition and flags
  every call site while the runtime resolves fine (assert: runtime > source).
  Remedy per lint policy: ns-scoped unresolved-var exclude for exactly that
  one lying ns — no inline noise, no global suppression (clj-kondo 0 warnings).

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
- REGISTRY STAYS EDN (hard invariant now the TUI is a wire client): `@sessions*`
  is serialized over nREPL every view refresh, so a session value must never
  hold a fn/atom/record. `:complete-fn` is injected PER-CALL, never stored —
  keep it that way; any non-EDN in a session breaks the remote view silently.
- Daemon detach (macOS, no setsid): `nohup … & echo $!` under /bin/sh — nohup
  ⇒ ignore SIGHUP, `&` ⇒ background, sh exits ⇒ grandchild reparents to launchd.
  Verified survives spawner-exit ∧ SIGHUP; SIGTERM stops. The spawner OWNS
  daemon.edn (captures $!, polls .nrepl-port for the OS-assigned port).
- Daemon spawn reinvokes `System/getProperty "babashka.config"` (the --config
  path) with deps-root ≡ its parent dir — that's how it finds bb.edn from a
  foreign CWD. No babashka.file/deps-root property exists; parent-of-config is
  the convention.
- `bb start`/`stop`/`status` ∧ auto-spawn touch ONLY the local per-project
  daemon (the pid in ./.llm-repl/daemon.edn) — NEVER a container. Container
  lifecycle ≡ podman's. `--headless` bypasses :attach entirely (a container's
  `bb nrepl` must never self-attach).
- NO nucleus/boot-seed in the repo (still true): an `AGENTS.md` with nucleus
  content is UNTRACKED and must stay so — the licensing boundary is
  ~/.config/llm-repl/config.edn, outside the repo.
- Container nREPL: `:port 0` is ACTIVELY WRONG in a container — it advertises
  a port nobody published. Use FIXED :port 7899 (7888 collides with the
  classic editor-nREPL default) + :bind "0.0.0.0", published 1:1 loopback-only
  so `.nrepl-port` stays truthful host-side.
- Docker image needs a headless JRE even though bb is the runtime: `bb
  prepare` (deps resolution) is a JVM program — maven deps (escapement) can't
  warm without java.
- nREPL ≡ UNAUTHENTICATED eval — `:bind "0.0.0.0"` only ever behind a wall
  (container loopback-only publish); NEVER expose to the LAN.
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

**NEXT SESSION starts here: PHASE-2 LONG-POLL.** The remote view is a ~150ms
POLL thread (client.clj RemoteCore/notify!) — replace with a server-push
emulation: add `core/wait-for-event!` that PARKS on an `events*` watch and
returns new receipts since a seen id (long-poll); a dedicated nREPL session
blocks on it → near-instant repaint, no busy poll. events* is ALREADY the
universal change signal (every tape mutation emits a receipt). Fall back to the
poll if an older server lacks the fn. ALSO PENDING: a human terminal pass on
`bb llm-repl` over a LOCAL daemon (spawn→attach→detach→reattach→bb stop) — every
programmatic path verified, only the live TUI render over a local daemon is
unconfirmed (container-attach TUI already human-verified).

1. Phase-2 long-poll tail (above) — the first pickup.
2. `:bbin/bin` entry → `llm-repl` on PATH (DEFERRED until feedback says
   people actually want to install it).
3. Tape persistence to DISK — now LOWER priority: the daemon holds the tree
   across TUI detach/reattach; disk only matters across a DAEMON restart.
4. `bb status --all` — machine-wide daemon list (a ~/.local/state index keyed
   by project) if the per-project view isn't enough.
5. Split-pane tape view (watch a second session live).
6. MCP facade over the same command ns-publics (compile from `(manual)`).
7. Compare pane (rides the overlay slot) ⊕ pathom resolvers (3c).
