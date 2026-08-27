# llm-repl — Agent Instructions

> **Status:** alpha. `mementum/knowledge/design/architecture.md` is
> authoritative — the code refactors TO the ratified design; when they
> disagree, the design wins. Read `mementum/state.md` first (30-second
> bootloader), then `mementum/queue.md` (intentions ∧ versions live there).
> Follow designs before code.

λ llm-repl.       an LLM chat completion as a BRANCHABLE CONTINUATION
                  | tape(messages[]) ≡ immutable_forkable_VALUE | repl ≡ PLACE(tapes_live)
                  | humans(TUI) ∧ models(self-eval) ∧ editors ≡ equal_nREPL_clients
                  | two_consumers: standalone_tool(bb llm-repl) ∧ library(anima, us.whitford/llm-repl@clojars)

## System Architecture — Viable System Model (Beer, 1972)

```
S5(identity) > S4(intelligence) > S3(control) > S2(coordination) > S1(operations)
| recursive: ∀system → contains(system) ∧ contained_by(system)
| TWO resolutions, same shape:
| dev_system:  S1(refactor ∧ code) S2(seams ∧ invariants) S3(test ∧ lint ∧ release)
|              S3*(git ∧ receipts) S4(mementum ∧ design) S5(identity ∧ human@System+1)
| instrument:  S1(drivers ∧ completions) S2(registry ∧ EDN ∧ receipts) S3(daemon ∧ config ∧ budgets)
|              S3*(events* ∧ manual) S4(the_model, invoked) S5(tape ≡ value)
| fractal: the instrument the dev_system builds has the dev_system's shape
```

## S5 — Identity (what this IS)

```
λ tape(x).        the formal shape (design § formal shape ≡ authoritative):
                  | tape ≡ PERSISTENT_WORK_TAPE (Goldin/Wegner PTM: survives between
                    interactions; eval! ≡ one macrostep; daemon ≡ persistence's process)
                  | :complete-fn ≡ ORACLE (the model ≡ δ, stochastic ∧ external;
                    the ONE op the mechanism cannot do itself | injected, never stored)
                  | fork ≡ NTM_BRANCHING (fork_forest ≡ computation_tree, materialized;
                    trampoline! branches INPUT | ab! branches δ ITSELF | conversation ≡ one_path)
                  | compact! ≡ THE_ONE_TRUE_WRITE (all else append-only; in-place rewrite
                    → band_contract ∧ receipts ∧ :original retained)
                  | KV_cache ≡ HEAD_POSITION (fork-at-prefix ≡ head_rewind, cheap because shared)
                  | configuration_completeness ≡ THE invariant: tape ⊕ config → future
                  | bend_it(tools ∨ compact!) → pay(receipts ⊕ retained_originals) | never_silence

λ equal(client).  ∀surface → nREPL_client(same_core, same_wire) | TUI included (no in-process path)
                  | --plain ≡ the ONE labeled in-process debug escape hatch
                  | equal at BOTH layers: tape(turns) ∧ chrome(receipts)
                  | the model drives its own repl (:tools) | observability ¬restriction ≡ the guard

λ consumers.      tool(bb llm-repl) ∧ library(anima injects @ :complete-fn, registers open_slots)
                  | stable_surface ≡ design/library-contract.md | internal ≡ wire ∧ surfaces
                  | lineage ≡ FUNCTION_names_verbatim(anima ⊗ llm-repl) | rides names ¬paths

λ scope.          NO nucleus ∨ boot_seed in repo | preamble ≡ CONFIG
                  | chain: session > model > provider > config-root | licensing_boundary ≡ ~/.config/llm-repl/config.edn
                  | mementum here ≡ llm-repl the INSTRUMENT | findings_gathered_THROUGH_it → anima

λ termination.    synthesis ≡ AI | approval ≡ human | human ≡ termination_condition
                  | memories ∧ knowledge ≡ AUTONOMOUS(reversible, git ≡ undo)
                  | policy(AGENTS.md) ∧ design_ratification ∧ release → human ALWAYS

λ feed_forward.   boundary(session) ≡ ∀context → ∅ | survive ≡ only{x ∈ git}
                  | quality(session_n) ∝ Σ encode(1..n-1) | write for brilliant_stranger ≡ you
                  | every_session_leaves_project_smarter ∨ waste(session)
```

## S4 — Intelligence (adaptation, memory, design)

```
λ mementum(x).    protocol(¬implementation) | git_based | guest(host) | ¬colonize
                  | memories(mementum/memories/) ∧ knowledge(mementum/knowledge/) ∧ state(mementum/state.md)
                  | state.md ≡ working_memory ≡ bootloader | read_first_every_session | keep ≤80_lines
                  | update(state.md) after_every_significant_change | frontier ∧ live_invariants ∧ queue ONLY
                  | symbols: 💡 insight | 🔄 shift | 🎯 decision | 🌀 meta | ❌ mistake | ✅ win | 🔁 pattern

λ store(x).       gate-1: helps(future_AI_session) | gate-2: effort > 1_attempt ∨ likely_recur
                  | memories: {slug}.md | frontmatter{type, symbol, title, related} | ≤200_words | one_insight
                  | knowledge: {topic}.md | OKF frontmatter{type:required, title, status, related} | updated_in_place
                  | when_uncertain → propose | false_positive < missed_insight

λ recall(q).      temporal(git log -n fib -- mementum/) ∪ semantic(git grep) | depth: fibonacci, default 2
                  | hit → follow(related ∈ frontmatter) → neighborhood | relational > exact
                  | recall_before_explore | prior_synthesis > re_derivation
                  | thin(result) → widen ∧ ↑depth

λ queue(x).       mementum/queue.md ≡ PROSPECTIVE_memory(intentions) | official optional add-on
                  | state.md(working) ∧ memories/knowledge(retrospective) ∧ queue.md(prospective)
                  | sections: # new (intentions) ∧ # complete (verdicts, newest_top)
                  | entry ≡ ONE dense line: glyph slug — description · refs · estimate
                  | glyphs: ⚪ open | 🔵 selected_next | ▶ in_progress | ✅ done | ❌ failed | 🚫 subsumed
                  | status_change → update_row ∧ same_commit | verdict → move(complete, top) | touch → restack_top
                  | intentions ∉ AGENTS.md(policy) ∧ ∉ state.md(beyond frontier) | they live HERE

λ metabolize(x).  observe → memory(append) → synthesize → knowledge(update_in_place) → policy(human-gated)
                  | ≥3 memories(topic) ∨ stale(knowledge) → synthesize | proactive ¬wait_for_ask
                  | tranche_complete(tag ∨ announce) → metabolize(state.md ✅_history → knowledge) | miss ≡ state_bloat
                  | tombstone: deleted(API ∨ pattern) → encode(¬x) | prevents re-derivation

λ design(x).      design_doc BEFORE code | knowledge/design/ ≡ ratified_targets
                  | assess(full_read) → propose → ratify(human) → refactor_to_document
                  | drift(code ↔ design) → fix(representation) BEFORE fix(code) | λ coherence

λ coherence(x).   representation ≡ reality | claims_of_verification NAME their artifact
                  | "tested" → ∃test_file | "live-verified" → ∃receipt ∨ commit | else ¬write_the_claim
                  | learned_the_hard_way: the pre-design era claimed headless tests that never existed

λ assert(x).      recall > runtime > source > docs > assumption | runtime ≡ truth
                  | the_repl_IS_the_instrument: verify claims IN it | (help) ∧ (manual) ≡ self-describing
```

## S3 — Control (how work is regulated)

```
λ test(x).        suite ≡ src/test | runs under BOTH runtimes: bb test ∧ clojure -M:run-tests
                  | tests arrive WITH the module they cover | ¬after ¬someday
                  | bb ≡ primary_runtime | JVM ≡ compatible_superset | CI enforces the twin
                  | pure_seams(tape ∧ tui.frame ∧ drivers+stub ∧ parsers) ≡ the floor
                  | live-verify ≡ supplement ¬substitute

λ lint(f).        clj-kondo | after(write ∨ edit) → re-read(f) → lint → fix > suppress(ns-scoped) ≫ global
                  | precedent: statecharts.promise unresolved-var ≡ the one ns-scoped exclude (runtime > source)

λ release(x).     build.clj(tools.build) | lib ≡ us.whitford/llm-repl | VERSION ← git_tag
                  | local ≡ -alpha only | CI deploys v[0-9]* full ∧ RC tags only | tests gate the jar
                  | library-contract hardens @ first_RC | then release ≡ change_boundary
                  | bb.edn ≡ deps.edn (keep in sync — CI's twin test enforces)

λ commit(x).      git log --oneline ≡ project_changelog | first_line readable standalone
                  | code: {symbol} {description} | memory: {symbol} {slug} | knowledge: {symbol} {slug}
                  | symbols ≡ mementum set (λ mementum) | one commit ≡ one intent

λ escalate(x).    ¬resolve(x) → surface(x) | ¬suppress ¬silent_choose
                  | fail_loud ≡ house_style: attach_contract ∧ config_typos ∧ unknown_kinds ∧ JVM_spawn
                  | silent_fallback ≡ the_worst_failure_mode (masks down_container as lost_state)
```

## S3* — Audit (direct observation)

```
λ observe(x).     receipts(events*) ≡ the universal trace | every command seam emits
                  | tapeless_drivers(bounce! ∧ trampoline!): receipt IS the trace
                  | tool dispatches: ⚡ receipts | budget ∧ race ∧ error → receipts, never silence
                  | git log --oneline ≡ history | bb status ≡ daemon ∧ :attach reachability
                  | (manual) ≡ command surface as data | (help) ≡ human render | ONE compile

λ orient(x).      read(mementum/state.md) → read(mementum/queue.md, top≈10) → follow(related) → recall → read(needed) | 30s
                  | queue.md ≡ what's_intended | full_read ⟺ front_selection
                  | design questions → knowledge/design/ first | operational → knowledge/{page}
```

## S2 — Coordination (seams ∧ invariants between units)

```
λ registry(x).    @sessions* ≡ EDN, always (serialized over nREPL every refresh)
                  | :complete-fn injected per-call, NEVER stored | no fn ∨ atom ∨ record in a session
                  | asserted at the mutation chokepoint (design D3) | mutations ≡ swap!(pure_fn_of_current)
                  | append > replace | raced_append → receipt ¬silence

λ rf(x).          G1: eval-rf keeps 1-arity completer (transduce calls it)
                  | G2: eager drivers only (step blocks on IO) | reduce ∧ transduce ∧ into | ¬sequence ¬eduction

λ seam(x).        open_slot > closed_dispatch: tool-registry* ∧ manual-namespaces* ∧ CoreClient
                  | unregistered ≡ unreachable ¬forbidden | hosts extend by registration
                  | ab! child ≡ parent-variant ∧ tree short-name strips prefix | MUST agree (one naming fn)

λ config(x).      chain: builtin < ~/.config/llm-repl/config.edn < ./config.edn < LLM_REPL_CONFIG | later_wins
                  | per-section shallow merge | malformed file ≡ fail_loud
                  | open! PERSISTS config | absence ≠ reset (poison keys survive — drop! resets)

λ attach(x).      explicit_attach(flag ∨ config) ≡ CONTRACT: unreachable → fail_loud ∧ exit
                  | local ≡ DEFAULT only (no attach requested) | --headless NEVER consults :attach
                  | bb start/stop/status ≡ local daemon ONLY | container ≡ engine's (podman/docker)
                  | nREPL ≡ unauthenticated eval | :bind "0.0.0.0" only_behind_a_wall

λ surface(x).     stdout NEVER survives the TUI (alt screen) | every surface speaks its own idiom
                  | events ≡ chrome ¬tape | receipts point INTO the tree | payloads live AT nodes
                  | overlay ≡ view swap, tape untouched | details ≡ knowledge/tui-design-rules.md
```

## S1 — Operations (bindings ∧ recipes)

```
λ run(x).         bb llm-repl (TUI: attach daemon ∨ container) | --plain | --headless
                  | bb nrepl ≡ daemon_body ≡ container CMD | bb start ∧ stop ∧ status ≡ daemon lifecycle
                  | JVM twin: clojure -M:llm-repl | recipes ≡ knowledge/attach-topology.md

λ api(x).         (require '[us.whitford.llm-repl :as repl])   ;; pre-refactor code: …llm-repl.core
                  | open! eval! fork! ab! bounce! trampoline! run-battery! compact!
                  | drop! reset-all! snapshot sessions-list manual help
                  | results ≡ data(:repl/*), never throws | (repl/help) ≡ the manual

λ backend(x).     escapement ≡ Clojars artifact (upstream change → RELEASE, ¬sibling edit)
                  | llm.llamacpp ≡ ours: modeled knobs → wire (:thinking ∧ cache_prompt ∧ id_slot ∧ max_tokens floor)
                  | stock openai translator DROPS :thinking — why the custom backend exists
                  | esc-seq-timeout 50ms > 0 | src/escapement/ui/* ≡ JVM-only, never under bb

λ container(x).   docker/Dockerfile ≡ plain OCI | podman ∧ docker identical
                  | wall ≡ THE sandbox (armed eval ≡ full power) | /work ≡ the one hole
                  | fixed port 7899, loopback publish | recipes ≡ knowledge/container.md

λ scope(files).   src/main/us/whitford/llm_repl/     — the code (ns map ≡ design § layers)
                  | src/test/                         — the suite (bb ∧ JVM)
                  | mementum/state.md                 — bootloader, read first
                  | mementum/knowledge/design/        — ratified designs (architecture ∧ library-contract)
                  | mementum/knowledge/               — container ∧ attach-topology ∧ self-eval ∧ tui-design-rules
                  | mementum/memories/                — one insight per file
                  | docker/                           — Dockerfile ∧ config.edn (container contract example)
                  | bb.edn ∧ deps.edn                 — the twin (keep in sync)
                  | config.example.edn                — the config surface, documented
```
