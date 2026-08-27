---
type: Architecture
title: llm-repl v0.3.0 architecture — the design the alpha refactors to
status: designing
related: [design/library-contract, upstream/escapement]
---

# llm-repl v0.3.0 — architecture

> The design produced by the 2026-08-27 meta session: a full read of every ns
> plus everything learned building v0.1.0–v0.2.0 (v0.2.0 is tagged and on
> GitHub — the last of the accrete-as-we-go era), folded into one target
> shape. The v0.3.0 code refactors TO this document. Alpha rules: no
> compatibility debt.

## What this is

llm-repl treats an LLM chat completion as a branchable continuation: the tape
(`messages[]`) is an immutable, forkable VALUE; the repl is the PLACE tapes
live. Humans (TUI), models (self-eval tool), editors — equal nREPL clients of
one persistent core. Two consumers, one artifact:

1. **Standalone tool** — `bb llm-repl` (TUI over a per-project daemon or a
   container), published as `us.whitford/llm-repl` on Clojars.
2. **Library for anima** — anima requires the api ns, injects its arbitered
   backend at the `:complete-fn` seam, registers tools and manual namespaces
   into the open slots. See [library-contract](/knowledge/design/library-contract.md).

## Formal shape — what kind of machine this is

The "tape" borrowed the Turing intuition (linear memory computation walks);
the structure that actually emerged is more precise, and naming it sharpens
the design commitments:

```
tape          ≡ PERSISTENT WORK TAPE (Goldin/Wegner Persistent Turing Machine:
                the work tape survives between interactions; eval! ≡ one
                macrostep input → consult tape → output → extended tape).
                The daemon is the persistence given a process to live in.
:complete-fn  ≡ ORACLE — the model is δ, but stochastic and external; each
                completion is an oracle query, the ONE op the mechanism
                cannot do itself. Injected, per-call, never stored.
fork          ≡ NONDETERMINISTIC BRANCHING — the fork forest IS an NTM
                computation tree, materialized. trampoline! branches on
                INPUT (same δ, varied symbols); ab! branches on δ ITSELF
                (varied interpreters — a counterfactual even an NTM lacks).
                The tree pane renders the computation tree; the
                conversation is one path (the accepting path).
compact!      ≡ THE ONE TRUE WRITE — everything else is append-only log;
                compaction is in-place cell rewriting, the head revisiting
                an old position. That is WHY it needs the band contract and
                receipts: it is a self-modifying machine rewriting its own
                memory, deliberately.
KV cache      ≡ HEAD POSITION, materialized — a cached prefix means the head
                does not re-walk the tape; fork! {:at N} is a head REWIND,
                cheap because the prefix is shared, not copied; id_slot pins
                a head to a physical location. Discovered as performance
                facts; structural in this reading.
```

The load-bearing invariant the analogy names: **configuration-completeness**
— tape ⊕ config determine the future (`messages[] ≡ truth`, completion pure
in the tape). Two operations BEND it on purpose and must stay visible when
they do: armed tool turns (effects unreproducible from the tape; the receipt
stream is the trace) and `compact!` (the original is retained in `:original`,
truth demoted to history). Anything else that would bend it needs the same
treatment: receipts ⊕ retained originals, never silence.

## Layers — dependency arrows only point downward

```
surfaces    main (launcher/dispatch)      tui.frame (pure)  tui.term (impl)
wire        client (CoreClient/long-poll) net (bencode)     daemon (lifecycle)
api         us.whitford.llm-repl  ≡ THE library surface (^:manual commands)
io          completion            tools   roster   llm.llamacpp
runtime     registry              — the ONE mutable place
values      tape                  — pure tape algebra
```

| namespace                      | layer   | responsibility |
|--------------------------------|---------|----------------|
| `us.whitford.llm-repl`         | api     | the ^:manual command surface: open! eval! fork! ab! bounce! trampoline! run-battery! compact! drop! reset-all! snapshot sessions-list manual help ⊕ the ONE submission grammar |
| `us.whitford.llm-repl.tape`    | values  | pure tape algebra: message, append-user/assistant, render-messages, truncate-at, the compaction band (apply-compaction-at, within-band?, due-indices…), the session fold |
| `us.whitford.llm-repl.registry`| runtime | `sessions*` `events*` `version*` + the mutation chokepoint (EDN assert, event ids, wait-for-event!) |
| `us.whitford.llm-repl.completion` | io   | build-request, plain-complete, tool-complete, default-complete, `*tool-depth*`, tools-system — the `:complete-fn` contract |
| `us.whitford.llm-repl.tools`   | io      | `:clojure/eval` executor + `tool-registry*` open slot (unchanged) |
| `us.whitford.llm-repl.roster`  | io      | config chain, model→backend construction, preamble resolution (config access becomes dynamic — D7) |
| `us.whitford.llm-repl.llm.llamacpp` | io | modeled-knob llama.cpp backend (unchanged) |
| `us.whitford.llm-repl.net`     | wire    | ~130-line bencode nREPL client (unchanged) |
| `us.whitford.llm-repl.client`  | wire    | CoreClient protocol; RemoteCore with version-poll + long-poll (D3) |
| `us.whitford.llm-repl.daemon`  | wire    | per-project daemon lifecycle; JVM spawn fails loud (D6) |
| `us.whitford.llm-repl.tui.frame` | surface | PURE: frame, key-from-bytes, edit-step, wrap-text, tree-lines |
| `us.whitford.llm-repl.tui.term`  | surface | IMPL: JLine, raw mode, ANSI, ticker, input loop, signal/shutdown |
| `us.whitford.llm-repl.main`    | surface | entry dispatch, wire layer (tui-submit!, use! intercept, overlays) |

`core.clj` ceases to exist: its contents split across the api ns, `registry`,
and `completion`. Lineage with anima is carried by FUNCTION names (`eval!`,
`fork!`, `wrapped-backend`, `with-preamble`), not by the ns they live in.

## Decisions

### D1 — tape absorbs chat-memory; compaction becomes `compact!`

All pure functions on the tape value live in ONE ns, including the
ouroboros-lineage compaction machinery (the band, decline-as-permanent,
explicit-index apply) and the session fold. They are no longer dormant weight:
their destination is **self-compaction** — the model, as an equal client,
compacting its own history. The api grows `^:manual compact!` routing through
`tape/apply-compaction-at`: the band contract holds, a receipt is emitted
(`⚡ compact! :s @4 −312ch`), the act is visible in the tree footer.
Observability, not restriction, is the guard — raw `swap!` stays possible;
the reachable-by-manual path preserves the invariants.

Caveat carried forward: `:original` retained on compacted messages doubles
those messages' weight in any full-registry fetch — D3's delta protocol is the
mitigation.

### D2 — registry writes are append-only swap!s; the eval! race dissolves

v0.2.0's `eval!` read the session, completed against the stale snapshot, then
stored a whole derived value — concurrent evals on one slug lost turns
(last-write-wins). v0.3.0 discipline: **every registry mutation is a `swap!` with
a pure function of CURRENT state.**

```
eval!  ≡  swap!(append-user)  →  complete(snapshot)  →  swap!(append-assistant)
```

Interleavings append rather than clobber. If the tape moved between send and
append, append anyway and emit a `⚡ raced` receipt — the reply answered a
prefix; visible, never silent. No locks, no agents. `:turns` is DERIVED from
the tape inside the swap (never read-stale-and-add).

Live-confirmed (2026-08-27), twice over:

- A model self-modifying its OWN session mid-turn (via its eval tool) was
  silently clobbered by `(store! slug done)` at turn commit — the race,
  observed from inside by its victim, which then traced the mechanism from
  source: "the persistence isn't external — it's the turn's own atomicity."
  Under v0.2.0 semantics a model's own session is structurally
  un-self-modifiable; D2's swap!-discipline is what makes self-modification
  (and `compact!` self-compaction) possible at all.
- **The interrupt ghost:** a client-side nREPL interrupt kills the client's
  deref, NOT the driver's tool loop — the orphaned loop keeps dispatching
  server-side (interleaved receipts observed), and a client retry then RACES
  its own ghost. Any future cancel surface must cancel server-side; recorded
  as input to the parked dispatch/await (ticket) idea.

### D3 — registry chokepoints make the invariants structural

- **EDN assert** at the single mutation fn (dev-mode: round-trip `pr-str`/
  `read-string`, or a cheap fn?/atom?/record? scan). The "non-EDN session
  breaks the remote view silently" failure becomes unreachable.
- **`version*`** — a monotonic counter bumped on every mutation. Clients poll
  the tiny number; fetch the registry only on change. Fixes both poll latency
  AND the v0.2.0 sin of serializing every tape body 6×/second.
- **events as DATA with ids**: `{:id n :at ms :kind :eval! :slug :s :msg "✓@6"}`
  (bounded ring, last ~200). Surfaces render lines; the MCP facade and anima
  get structure. `wait-for-event! since-id` parks on a watch and returns new
  events — the phase-2 long-poll, designed in rather than bolted on. Client
  fallback when the server lacks the fn: version-poll.
- `event!` stays public; `:kind :note` for surface-contributed receipts.

### D4 — completion is its own layer; suppress-echo dies

`build-request`, `plain-complete`, `tool-complete` (bounce budget, depth
guard, tools-system orientation), `default-complete` extract whole from core.
The contract — `config ⊕ slug → (tape → text)` — IS the anima injection
point, now a named layer. Command results are identified structurally
(api results carry `:repl/id`); the client never regex-sniffs printed strings.

Amendments from the 2026-08-27 self-eval experiments (live A/B, qwen3.6-35b):

- **Slug-aware orientation.** Interpolating the session slug into the
  orientation ("You are session `:x`; `(repl/snapshot :x)` returns this very
  conversation") collapsed self-location from a multi-turn guided walk to ONE
  dispatch — the model trusts the given slug and acts immediately. Self-location
  is a RUNTIME gap, not a prompt gap: `sessions-list` carries no current-marker,
  so no honest orientation can direct self-discovery without the slug (the
  drafting model proved this by failing to write one). Conversely, prose naming
  raw primitives (`swap!`, `alter-var-root`) did NOT change primitive usage —
  orientation fixes *location*, not *reach*. Ships as the `:orientation` config
  template (D7), `{slug}` substituted in tool-complete.
- **Budget becomes structural and visible.** At the budget boundary, STRIP
  `:tools` from the final request — final text becomes the only reachable act
  (unreachable > forbidden), replacing teach-and-hope (observed: the model
  called tools anyway; the reply landed empty and its unstated strategy was
  lost forever). Append the remaining count to every tool result
  (`[3 dispatches remain]`) — a model cannot budget inside an invisible budget.
- **Reasoning-only termination is loud.** A thinking model can return zero
  content blocks (thinking-only; observed live via a degenerate reasoning
  loop). An empty-text final must emit a receipt and a tape marker — never a
  silent `""` reply.
- **`^:manual` commands carry malli input schemas.** Bad args → humanized
  errors as data (the model reads and corrects — the teaching-feedback pattern
  the chat template's escalating warnings already proved effective on this
  model class). One source, four surfaces: validation, teaching errors, MCP
  tool defs (escapement's `malli->json-schema`), docs.

### D5 — one grammar, one deliberate in-process exception

The submission grammar (`"(" → form | bare text → chat | :q → quit`) is
defined ONCE in the api ns and consumed by both the plain loop and the TUI
wire layer. `--plain` remains the ONE in-process path, explicitly labeled the
no-wire debug escape hatch; every other surface attaches over nREPL.
The TUI splits pure/impl into two files — the testable cut becomes file
topology, not an in-file comment divider.

Cross-ns convention now written down: `ab!` names children `parent-variant`;
`tui.frame/short-name` strips that prefix for display. The two must agree
(single naming fn exported from the api ns).

### D6 — tests are part of the design; both runtimes in CI

The v0.2.0 purity seams claimed headless tests that never existed (coherence
violation, corrected here: claims of verification must name their artifact).
v0.3.0 ships the suite WITH the refactor, module by module:

- `tape` — full coverage (the band's bug history deserves regression locks)
- `tui.frame` — frame/key-from-bytes/edit-step against byte vectors & snapshots
- drivers — stub `:complete-fn`
- `daemon/attach-target`, config chain — parse tables
- `net`/`client` — against an in-process bb nREPL server

CI runs the suite under BOTH runtimes — `bb test` (bb.edn task, clojure.test)
and a JVM `:run-tests` alias — structurally enforcing the bb/JVM twin
invariant that v0.2.0 held only by comment. JVM `daemon/spawn!` (no
`babashka.config` property) fails loud with instructions instead of NPEing.

### D7 — config is formal, dynamic, and fails loud (amended 2026-08-27, live troubleshooting)

`default-config` becomes a function (v0.2.0's `def` captured `default-model` and
`default-tools` at load, silently defeating `reload-config!`). Reload is an
operator seam a DAEMON wants: edit config, `(reload-config!)` over the wire,
no restart, tapes intact. Live-verified: a container's preamble fix landed via
`(reload-config!)` over nREPL with tapes intact, no restart.

Amendments from the 2026-08-27 container session:

- **EOF-assert in `read-edn-file`.** Live bug: a stray `}` closed the top-level
  map early; the trailing `:preamble` was valid-EDN-plus-garbage. `edn/read-string`
  silently reads the FIRST form — the key vanished with no error, and reload
  couldn't help (disk ≠ runtime for 40 minutes of mystery). "Malformed fails
  loud" must also mean *trailing forms fail loud, with position*: read all
  forms; more than one → throw naming the offending content.
- **malli schema at `load-config`.** The whole config validated on load;
  failures humanized (`malli.error/humanize`) with key paths —
  `{:preamble ["should be a string"]}` instead of a mystery roster. The schema
  doubles as the config contract; CI validates `config.example.edn` against it.
  malli already rides the classpath via escapement; bb-load-verified in the
  container.
- **The prompt stack becomes config** (three layers; today three different
  mechanisms, only one of them config):
  `:preamble` (exists — the boot-seed slot, full chain) ⊕ `:system-prompt`
  (root default for session `:system`, replacing core's baked
  `"You are a precise assistant."`) ⊕ `:orientation` (replacing the
  `tools-system` def: a template string, `{slug}` substituted by tool-complete
  where the slug is in scope). No nucleus default anywhere — prose ships;
  boot seeds are a machine's config.
  Open (needs ratification): chain scope for the new keys (lean: full chain
  for `:system-prompt`, root-only for `:orientation`); closed vs open schema
  (lean: closed ⊕ an `:ext` escape hatch for embedding hosts).

## Build ∧ release ∧ CI (modeled on fulcro-rad-datalevin — copy, then adapt)

- `build.clj` — tools.build; `lib 'us.whitford/llm-repl`; `VERSION` env from
  the pushed tag (local default carries `-alpha`); version-less jar name (the
  pom inside carries coordinates); MIT + scm pom-data; basis from root deps
  only. Thin source jar — runtime-neutral, bb and JVM consumers alike.
- deps.edn aliases — `:build`, `:deploy` (deps-deploy in an ISOLATED
  classpath; documented maven-resolver conflict), `:run-tests`, `:outdated`.
- `ci.yml` — test (bb ∧ JVM) + lint jobs; the prime-deps-before-`-Spath`
  clj-kondo step (paid-for bug, kept); dependency caching.
- `release.yml` — deploys ONLY on `v[0-9]*` full/RC tags; version derived from
  `GITHUB_REF_NAME`; tests gate the jar; gh release with `--generate-notes`;
  `-alpha`/`-beta` never deploy from CI.
- anima rides `:local/root`/git-sha during alpha; switches to the Clojars
  artifact at the first RC. From then on a release is the change boundary
  (escapement 1.0.1 lesson).

## Placeholders (named seams, not yet designed)

- **Tape persistence** — daemon-owned disk snapshots (matters only across a
  daemon restart; the daemon already survives TUI detach). Likely
  `<proj>/.llm-repl/tapes/` EDN, written at mutation or on SIGTERM. The
  session fold (`tape/apply-fold`) is the cross-session compression when it
  lands.
- **MCP facade** — SKIPPED by decision (2026-08-27): llm-repl is squarely
  aimed at Clojure, and nREPL is THE wire — "a clojure repl tmux with nicer
  semantics" (sessions ≡ windows that are immutable forkable VALUES; attach ≡
  many equal clients; fork-the-past has no tmux analog). For Clojure-speaking
  clients eval IS the op protocol: forms are the messages, `ns-publics ≡ the
  op table`, D3's version counter ∧ `wait-for-event!` are the streaming.
  Non-Clojure agents get a RECIPE page (connect via bencode/bb one-liner,
  eval, long-poll) — documentation, not a component; the `(manual)` seam
  feeds it. Un-skip only on demonstrated MCP-client demand; it still sits on
  the api ns only, so nothing depends on its absence. D4's manual schemas
  keep `malli->json-schema` available if that day comes.
- **Trace durability** — escapement's capture layer (`capture.cljc`
  ArtifactStore ⊕ `transcript.clj` single-writer JSONL ⊕ `storage/disk` ⊕
  `replay`) as the durable per-turn payload store: full request/response/
  tool-results as EDN blobs, receipts carry `:io/ref` into the tree. Enables
  self-compaction with total recall — `compact!` rewrites the tape while every
  step stays retrievable (the model reads its own past traces). Motivated
  live: the `:self-mod` experiment tape died with a container restart, and the
  llama.cpp verbose log (the only remaining trace, which reconstructed the
  whole experiment including thinking blocks) gets purged regularly.
  **Second justification — provenance (the `:implant` experiment):** every
  legitimate assistant turn has a captured generation behind it; a forged
  turn (raw `swap!` tape edit — demonstrated invisible from inside, the model
  absorbs it as its own) exists on the tape with NO generation in the trace.
  `tape ∖ trace ≡ undeclared edits`; compact! declares its edits (receipt ⊕
  `:original`); silence in the trace is the tell. The capture layer is an
  AUDIT surface, not just recall — raw mutation stays possible (observability
  ¬restriction), but with durable traces observability extends to
  forgery-after-the-fact.
- **Compare pane** — rides the TUI overlay slot.

## Invariants carried forward unchanged

- rf G1 (1-arity completer) ∧ G2 (eager drivers only)
- registry is EDN — now ASSERTED at the chokepoint, not just documented
- `:complete-fn` injected per-call, never stored
- esc-seq-timeout 50ms > 0; `src/escapement/ui/*` never under bb
- escapement via Clojars coordinate, bb.edn ≡ deps.edn
- nREPL ≡ unauthenticated eval — `:bind "0.0.0.0"` only behind a wall
- container: fixed port 7899, never self-attaches; bb tasks touch only the
  local daemon; attach is a fail-loud contract, local only ever the default
- no nucleus/boot seed in the repo — preamble ≡ config, resolved
  session > model > provider > config-root
- stdout never survives in the TUI — every surface speaks its own idiom
- events ≡ global UI chrome, never tape content; receipts index, payloads
  live at the nodes

## Refactor order

1. `tape` (extract + absorb chat-memory) ⊕ its tests — pure, zero risk
2. `registry` (chokepoints: swap!-discipline, EDN assert, version, event ids,
   wait-for-event!) ⊕ tests
3. `completion` extraction ⊕ driver tests with stub complete-fn
4. api ns `us.whitford.llm-repl` (commands + grammar + compact!) — delete core
5. `client` long-poll/version protocol; structural suppress-echo
6. `tui.frame`/`tui.term` split ⊕ headless tests; `main` rewire
7. `daemon` JVM fail-loud; `roster` D7
8. build.clj + workflows; first `-alpha` local install; anima `:local/root`
