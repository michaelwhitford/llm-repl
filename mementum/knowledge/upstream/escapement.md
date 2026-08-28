---
type: mementum/knowledge
title: Escapement — what llm-repl consumes, and how
description: Map of escapement's three UI stacks, the bb classpath facts, the λ API contracts for the pure primitives llm-repl builds its TUI on, the two patterns copied (dirty-ticker render loop, byte→key decoder), the backend/protocol seam the core rides, and the capture layer (ArtifactStore ⊕ single-writer JSONL transcript ⊕ replay) the trace-durability design rides. Source-verified against ~/src/escapement HEAD during llm-repl increments 1-2; capture layer source-read ⊕ bb-round-trip-verified 2026-08-28 (HEAD d68c146 ≡ release 1.0.1).
tags: [escapement, tui, jline, llamacpp, backend, babashka, classpath, upstream, capture, transcript, replay, durability]
status: active
category: upstream
related:
  - ../../state.md
---

# Escapement — what llm-repl consumes, and how

λ assert(source > docs): everything here was grep/read-verified against
`~/src/escapement` source (and bb-load-verified for the classpath claims)
during llm-repl increments 1-2. Re-verify against escapement HEAD on upgrade.

## TL;DR

```
escapement has THREE UI stacks, not one:
  A src/escapement/tui.clj + tui/*   JLine, bb-first  → app-specific; primitives REUSABLE
  B src/escapement/ui/*              Fulcro-RAD       → JVM-only; NOT bb-loadable; ignore
  C tui/opentui/                     Bun sidecar      → out-of-process; needs tui/ path; ignore
llm-repl uses: escapement.llm.* (backend seam) ⊕ escapement.tui.{theme,compositor} (pure)
             ⊕ statecharts.promise — ALL on deps.edn :paths ["src" "resources"] → :local/root suffices
             ⊕ capture layer (capture ⊕ transcript ⊕ storage.{disk,disk-read,memory} ⊕ replay)
               — ALL in the 1.0.1 jar, ALL bb-loadable (round-trip-verified 2026-08-28)
llm-repl copies (private/app-specific, pattern not code):
             render loop (atom ⊕ :render-dirty ⊕ 33ms daemon ticker)
             byte→logical-key decoder (ours adds full CSI params → bracketed paste)
```

## The classpath fact (why :local/root just works)

```
deps.edn :paths ["src" "resources"]   ← what a :local/root consumer gets
bb.edn   :paths [… "tui" …]           ← adds ONLY opentui sidecar + stress charts
```

Everything llm-repl needs lives under `src/`. The top-level `tui/` dir is NOT
framework code — `tui/opentui/` (Bun/SolidJS sidecar + `opentui.sidecar`
spawner) and `tui/stress/*` (example charts). Never needed.

- bb-clean: all of `src/` EXCEPT `src/escapement/ui/*` (Fulcro/RAD — JVM-only,
  deps only under `:cljs`/`:ui-test` aliases; will not load under bb at all).
- OpenTUI cannot run in-process under bb (Zig core over Bun FFI; bb has no
  C-FFI) — it is a WebSocket sidecar. Irrelevant to llm-repl.
- guardrails MUST stay pinned 1.2.16 (escapement deps.edn comment: Pathom's
  transitive 0.0.12 breaks bb). Comes transitively; don't override.

## λ contracts — the LLM seam (core.clj rides these)

```
λ(proto).  escapement.llm.protocol/send-turn : LLMBackend × Request → promise(Response)
           Request  ⊇ {:model str :messages [{:role :text}] :system str
                       :conversation/id kw :system-cache-control {:type :ephemeral}
                       :thinking {:type :disabled}? :temperature n?}
           Response ⊇ {:content [{:type :text|:thinking|… :text str}]}
λ(await).  com.fulcrologic.statecharts.promise/await! : promise → val | throw
λ(providers). escapement.llm.providers/build-credential-backend : descriptor → LLMBackend
           (the stock factory — llm-repl's roster/build-backend falls through
            to it for every :kind except :llamacpp)
λ(stock_openai_translator). DROPS :thinking ∧ no id_slot/cache_prompt home
           → WHY the vendored llamacpp backend exists (ours reaches
             chat_template_kwargs{enable_thinking false}, id_slot, cache_prompt)
```

`escapement.llm.{http-transport,openai,types}` are consumed by the vendored
llamacpp backend (ported anima→llm-repl; its private HTTP glue is deliberately
COPIED/frozen there — tracks only the stable public backend contract,
re-verified against escapement 3636e85).

## λ contracts — the capture layer (trace-durability rides these)

Source-read whole (820 lines, 7 nses) ⊕ bb-round-trip-verified 2026-08-28:
write → read-back → transcript → read-events*, all from the **1.0.1 jar
already on llm-repl's classpath** (escapement HEAD d68c146 ≡ release 1.0.1
c304c98 — no upstream release needed). Design intent lives in escapement's
`io-refactor-plan.md` §0/§5b (cited in the docstrings).

```
λ(protocols). escapement.protocols — three small storage-agnostic protocols:
  TranscriptStore : append-event!(store,sid,ev) ∧ read-events(store,sid,query)
  ArtifactStore   : write-artifact!(store,sid,path,content,meta) → summary
                    ∧ read-artifact(store,sid,path) → str|nil ∧ list-artifacts(store,sid)
  SessionIndex    : list-sessions(store) → [summary]

λ(capture). escapement.capture — HOST-PORTABLE (bb/CLJ/CLJS): NO filesystem,
  only ArtifactStore calls + string work. Locator ≡ THE opaque id (path IS
  the address, no translation table; disk tree literally walkable):
    nodes/<node-id>/<visit>/seed.edn
    nodes/<node-id>/<visit>/turns/<turn>/{request,response,output,tool-results/<id>}.edn
  payloads ≡ pr-str EDN → round-trips losslessly (keywords/enums/nesting survive)
  capture-blob!    : capture_ctx × turn × kind × data × snippet-text → {:io/ref :io/snippet}
                     capture_ctx ≡ {:store :session-id :node-id :visit}
  capture-request! : FIRST-WRITE-WINS — a logical turn may issue several physical
                     requests (fallback, :max_tokens continuation w/ prefill);
                     only the BASE request kept, so replay tunes the real prompt
  capture-seed!    : replayable seed (resolved params ⊕ initial messages) per (node,visit)
  seed-visit-counts: store × sid → {node-id max-visit} — restart-collision guard:
                     resume seeds visit ← max+1 so a new run never overwrites prior blobs
  snippet ≡ ≤80ch human-correlation slice; the event carries the ref, the blob the weight
  encode-node-id: :a/b → "a_b" — NOT perfectly reversible; authoritative coords
                  ride the transcript event, not the path

λ(transcript). escapement.transcript — CLJ, daemon-side: single-writer JSONL.
  ∀threads → LinkedBlockingQueue → ONE daemon writer thread owns the BufferedWriter
  ⊨ order(FIFO) ∧ no-interleave ∧ monotonic(:seq, writer-owned counter)
  open-transcript : {:path req :append? true :fsync? false} → sink
                    append-resume CONTINUES :seq past last row on disk
                    (one gap-checkable timeline across restarts) | parent dirs auto
  write! : sink × event → bool  (non-blocking, any thread, never throws to caller)
  close! : drain → join(5s) | idempotent CAS
  fault-tolerant: unserializable value → :transcript/serialize-error row ⊕
                  sanitized retry row (pr-str offenders) — NEVER crashes the caller
  make-transcript-fn : sink → (fn [ev] …) — the injectable emission slot
  uses escapement.threads/unstarted-daemon (virtual on Java 21+/bb, falls back platform)

λ(disk). escapement.storage.disk — bb/CLJ, ONE session's dir:
  new-artifact-store : session-dir → DiskArtifactStore
  writes ATOMIC (temp + ATOMIC_MOVE rename — a concurrent reader never sees
  a partial blob) | path ≡ addressing key | list-artifacts re-derives coords
  from paths (best-effort; class :author under artifacts/, :captured-io under nodes/)

λ(disk_read). escapement.storage.disk-read — bb/CLJ, MULTI-session READ store
  rooted at a work-dir: <work-dir>/<sid>/{transcript.jsonl, artifacts/, nodes/}
  new-store : work-dir → TranscriptStore(read) ⊕ SessionIndex ⊕ ArtifactStore(delegates)
  append-event! THROWS (live writer owns the append path — by design)
  read-events* : query {:types :node-id :from-seq :to-seq :limit} — transducer,
                 :limit short-circuits the read; rows normalized bare-key JSON →
                 namespaced vocab (:io/ref ∧ :io/snippet surfaced top-level)
  list-sessions* : one constant-memory scan/session → summaries, newest first

λ(memory). escapement.storage.memory — CLJC stub ⊕ ephemeral backend:
  ALL protocols in one atom (⊕ statecharts WorkingMemoryStore) — the test seam

λ(replay). escapement.replay — CLJC, backend INJECTED (never reached-for):
  refine-turn : store × sid × node × visit × turn × {:backend req :overrides}
    → {:request :response :original-request}   ; load captured request →
      deep-merge overrides → send-turn* → caller diffs. THROWS if no capture.
    deep-merge: maps recurse, non-map colls (:messages) REPLACED wholesale
  refine-node : CLJ-only (reader conditional) — re-derives the OPENING turn
    from seed.edn through escapement.llm/run-turn (llm-repl doesn't need it;
    refine-turn suffices — our llm.llamacpp backend implements LLMBackend,
    so refine-turn works against it TODAY)
```

llm-repl mapping (design proposal `design/trace-durability.md`): work-dir ≡
`<proj>/.llm-repl/` (under the container's `/work` hole → survives restarts) ·
node-id ≡ session keyword (the fork forest IS the node tree) · visit ≡ daemon
incarnation (seed-visit-counts on boot) · turn ≡ eval index (compact! is
index-stable). Receipts grow `:io/ref`; `registry/event!` feeds
`make-transcript-fn`.

## λ contracts — the pure TUI primitives (tui.clj builds on these)

Both namespaces are explicitly bb/SCI-safe, zero coupling to escapement's
runner/state — designed-for-reuse, use directly, do NOT vendor:

```
λ(theme).  escapement.tui.theme
  theme-for : (:truecolor|:256|:16|:none) → theme_map     ; :none ⇒ all "" ⇒ paint ≡ identity
  color-capability : tty? → capability_kw
  paint : theme × semantic_key × body → str               ; sgr-wrap through theme lookup
  sgr-wrap : sgr_code_str × body → str                    ; "" ∨ nil code ⇒ no-op
  esc : s → "\e[" + s | reset-attrs-s | CSI | ESC-CHAR
  role codes: human-color "97" chart-color "90" error-color "31" debug-color "90"

λ(compositor).  escapement.tui.compositor
  draw-box : StringBuilder × {row col w h title scroll{:pos :total} focus? theme body-lines} → buf
             ; abs-positioned per cell (draws at any offset); body truncate-padded to (w-2)×(h-2)
  display-width : s → cols       ; SGR ≡ 0-width, CJK/emoji ≡ 2 — use for ALL width math
  truncate-display : s × n → s   ; exact n cols, clips with …, never splits an SGR
  move-to-s : row × col → str    ; 1-based | clear-eol-s | reverse-on-s
```

## Patterns COPIED from escapement.tui (app-specific ∨ private — pattern, not dep)

```
λ(render_loop). state_atom ⊕ :render-dirty ⊕ daemon_ticker(33ms, paint iff dirty)
  ∀thread: swap!(state) → request-render!(flag) | ONE thread paints | lock serializes
  → registry add-watch rides this: attached nREPL client's eval! visible ≤33ms
λ(terminal). TerminalBuilder.builder().system(true).build()
  input_thread: .enterRawMode → .reader → NonBlockingReader
  boot:  \e[?1049h 2J ?25l   (alt-screen, clear, hide cursor)
  stop:  reset ?1049l cnorm  (idempotent CAS; SIGINT handler AFTER .build —
         JLine swallows SIGINT — ⊕ JVM shutdown hook; escapement's exact belt)
λ(keys). key-from-bytes ≡ PRIVATE in escapement.tui → copied shape:
  read! : timeout_ms → int(byte) | esc-seq-timeout-ms ≡ 50 (MUST >0: unbuffered
  CSI tail misreads as bare ESC — escapement bug-history, encoded)
  OURS EXTENDS: full CSI param accumulation → ESC[200~/201~ ≡ :paste-start/:paste-end
  (bracketed paste — multi-line paste ≡ ONE turn; escapement's decoder lacks this)
  ⊕ chars ≥128 pass through as [:char c] (JLine reader yields chars, not bytes)
λ(headless). interactive-terminal? via babashka.terminal/tty? (bb) ∨ System/console (JVM)
  ¬interactive → NEVER boot the TUI (escapement returns a no-op handle; we route --plain)
```

## What we did NOT reuse, and why

| escapement piece | verdict | reason |
|---|---|---|
| `escapement.tui/start!` + panes | ✗ | event vocabulary hardwired (`:runner/*` `:llm/*` case), no screen extension point |
| `HumanRenderer` protocol | ✗ (imitate later) | covers prompting only; pulls escapement.threads/chart machinery |
| `escapement.ui/*` RAD explorer | ✗ | Fulcro, JVM-only — the real multi-target framework, but unusable under bb |
| OpenTUI sidecar | ✗ | out-of-process Bun; needs `tui/` path + Bun runtime |
| input editor | ∄ | escapement has no multi-line/history editor — we wrote our own |

## How llm-repl's implementation hangs together

```
core.clj      tape ≡ immutable acc | eval-rf ≡ 3-arity rf (G1: 1-arity completer
              REQUIRED by transduce; G2: eager drivers ONLY — step blocks on IO)
              drivers: eval!(thread) battery!(fold) bounce!/trampoline!(map, fixed pt)
              IO seam :complete-fn injected — default rides roster
roster.clj    config chain (builtin < ~/.config/llm-repl < ./config.edn < LLM_REPL_CONFIG)
              model-target/build-backend verbatim from anima llm.clj;
              wrapped-backend ≡ IDENTITY wrap (name kept for lineage; anima's wraps
              CapacityBackend — a host injects its arbitered backend at :complete-fn)
tui.clj       purity seam: frame(reg ⊕ ui-state ⊕ theme ⊕ w/h) → ansi_string — headless-testable;
              impl half only moves bytes (ticker/terminal/signals per patterns above)
main.clj      ONE process: nREPL first (.nrepl-port), then TUI ∨ --plain loop ∨ --headless
              ns-publics(core) ≡ THE command contract (loop, TUI palette, future MCP)
```

Lineage: function names verbatim anima↔llm-repl (fork cheap in both directions);
`git diff --no-index` of core.clj vs anima's llm_repl.clj ≡ requires + 3 docstrings.
