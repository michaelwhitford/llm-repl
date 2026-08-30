---
type: Design
title: library contract — the stable surface anima (and any host) consumes
status: designing
related: [design/architecture]
---

# Library contract — `us.whitford/llm-repl`

> What a host (anima first) may depend on, and what may churn without notice.
> Alpha: this page is the proposal; it hardens at the first RC, after which a
> Clojars release is the change boundary.

## Consumption

```clojure
;; deps.edn (alpha: :local/root or git sha; first RC onward: the artifact)
us.whitford/llm-repl {:mvn/version "…"}

(require '[us.whitford.llm-repl :as repl])
(repl/open!  :probe {:model :qwen36-35b-a3b})
(repl/eval!  :probe "hello" {:complete-fn my-arbitered-complete})
(repl/fork!  :probe :probe-b {:at 4})
```

## STABLE — the contract

### 1. The api ns — `us.whitford.llm-repl`

Every `^:manual` command, with v0.2.0 semantics preserved — EXCEPT the
session config keys, which v0.3.0 renames to fully-qualified spellings
(D11, the ratified alpha-window break; anima re-migrates — the
step-function: migrate → suggest → ingest → re-migrate):

```
open! eval! fork! ab! bounce! trampoline! run-battery! compact!
drop! reset-all! snapshot sessions-list manual help use!¹ event!
```

- Results are DATA, never throws: `:repl/id :repl/reply :repl/depth
  :repl/turns :repl/error :repl/bounces :repl/variants …`. A map carrying
  `:repl/id` identifies a command receipt (surfaces key off this — the
  structural replacement for v0.2.0's string sniffing).
- `:defaults` (D7 amendment 2026-08-30) — an opts key on `open!` and the
  drivers that delegate to it (`eval!` `bounce!` `trampoline!`
  `run-battery!`; NOT `fork!`/`ab!`): a session-knob map applied on
  CREATION only, under the call's own overrides
  (`(default-config) < :defaults < opts`), ignored silently for a session
  that already exists. This is the race-free seed a host wants instead of
  `snapshot` → check → `open!` (that shape is a TOCTOU outside the
  registry swap). Not stored: `unset!` re-seeds from the config chain, not
  from `:defaults`.
- `manual` ≡ `[{:name :arglists :summary :doc}]` compiled from `^:manual`
  metadata across `manual-namespaces*`. `help` returns a string (never
  prints). Hosts derive their facades (MCP, palettes) from `manual` only.
- ¹`use!` is surface-local focus; library consumers ignore it.

### 2. The tape ns — `us.whitford.llm-repl.tape`

Pure functions on the canonical tape value
`[{:role :user|:assistant :text s :compacted? b (:original s) (:declined? b)} …]`:

```
message append-user append-assistant render-messages truncate-at
next-to-compact needs-compaction? backlog-count declined-count
within-band? apply-compaction-at apply-compaction default-floor
fold-split fold-input fold-message apply-fold
```

The canonical message SHAPE is contract: hosts may read tapes directly
(`(:tape (repl/snapshot slug))`) and rely on roles/order/text.

### 3. The `:complete-fn` seam

THE injection point for a host's own backend (anima: its capacity-arbitered
wrap). Passed per-call in opts, NEVER stored in the session:

```
:complete-fn ≡ (fn [config slug] → (fn [tape] → reply-text))
   config ≡ the session's :config map (:model :system :preamble? :thinking
            :temperature :tools …)
   tape   ≡ canonical messages (render with tape/render-messages)
   throws → the driver catches; the caller sees {:repl/error …}
```

Default when absent: `completion/default-complete` (roster-built backend,
tool loop when `:tools` armed).

### 4. The open slots

- `tools/tool-registry*` — register host tools via
  `escapement.tools.protocol/register!`; sessions whitelist by keyword in
  config `:tools`. Unregistered ≡ unreachable, not forbidden.
- `manual-namespaces*` ⊕ `register-manual-ns!` — a host surface with its own
  `^:manual` commands joins the ONE manual.
- `completion/tools-system` — public var; a host may redef to speak its own
  environment-orientation idiom.

### 5. The event stream

`registry/events*` — a bounded ring of event maps:

```
{:id n :at ms :kind kw :slug kw|nil :msg s}
   :id    monotonic (the long-poll cursor)
   :kind  :open! :eval! :fork! :ab! :bounce! :tramp! :battery! :compact!
          :drop! :tool :error :note …
```

`event!` appends (hosts may contribute; keep receipt-length).
`wait-for-event! since-id` parks until new events arrive — the push seam a
host UI or the remote client tails. Rendering to display lines is the
SURFACE's job; the data shape is the contract.

### 6. Config semantics

Session config keys are FULLY QUALIFIED (D11 — the session `:config` map
escapes into host space via `snapshot`, so its keys must be collision-proof
against ANY keyword):

```clojure
:us.whitford.llm-repl/model        ;; ≡ ::repl/model behind the alias
:us.whitford.llm-repl/system
:us.whitford.llm-repl/preamble
:us.whitford.llm-repl/preamble?
:us.whitford.llm-repl/thinking
:us.whitford.llm-repl/temperature
:us.whitford.llm-repl/tools
:us.whitford.llm-repl/orientation  ;; session-level chain override
```

Persisted at open/eval/fork; preamble resolution chain session > model >
provider > config-root (absent ≡ inherit, false/blank ≡ explicitly none).
Ephemeral opts-only keys stay BARE (`:complete-fn` `:xform` `:at` —
consumed at the call, never persisted). The config FILE root stays bare
(path-owned, closed schema, `:ext`). A host embedding the library may
bypass roster entirely via `:complete-fn`; roster is the DEFAULT provider,
not a required path.

**Inert at require (D10, ratified 2026-08-29):** requiring any library ns
does NO ambient IO — no file chain, no env, no home dir. Until a host
initializes config, `builtin-defaults` govern. The host-facing config
surface, STABLE (the rest of roster stays internal):

```clojure
(require '[us.whitford.llm-repl.roster :as roster])
(roster/init! source)       ;; source ≡ {:builtin true} | {:map m}
                            ;;          | {:fn thunk} | {:files [paths]}
(roster/reload-config!)     ;; re-folds from the CURRENT source
(roster/config-sources)     ;; the default standalone file chain
```

Every source folds over `builtin-defaults` (per-section shallow merge)
through the ONE `validate-config` — `init!` throws loud on the host's
stack (bad shape, unreadable file, invalid merge), never at require.
`init!` replaces atomically at any time; already-open sessions keep their
materialized configs (stickiness law). The standalone entrypoints call
`(init! {:files (config-sources)})` themselves — ambient resolution is
the standalone tool's behavior, never the library's. A `:complete-fn`
host that registers no roster models never needs any of this.

## INTERNAL — may churn without notice

- `registry` internals beyond the two derefs + `event!`/`wait-for-event!`
  (mutation fns, version counter, assert machinery)
- `completion` internals (loop shape, budgets, depth guard mechanics)
- ALL wire ∧ surface namespaces: `net` `client` `daemon` `tui.frame`
  `tui.term` `main` — the standalone tool, invisible to a library consumer
- `roster` internals ∧ `llm.llamacpp` (hosts wanting the backend directly
  should say so; it can be promoted) — EXCEPT the § 6 config surface
  (`init!` `reload-config!` `config-sources`), which is stable
- the TUI's visual language, receipts' rendered text, banner content

## Versioning

- alpha (`x.y.z-alpha`, local installs only) — anything may change, this
  page included
- first RC — this page hardens; stable section changes require a version
  bump and a CHANGELOG entry
- full releases deploy from CI on `v*` tags only (see architecture § build)

## Lineage

Function names shared with anima stay verbatim (`eval!` `fork!`
`wrapped-backend` `with-preamble` `build-backend` …) — the repos grep as one.
The ns holding them changed in v0.3.0 (core → api ns); lineage rides names, not
paths. anima's migration: replace `us.whitford.anima.llm-repl` requires with
`us.whitford.llm-repl`, inject its backend at `:complete-fn`, register its
granted tools.
