---
type: insight
symbol: 💡
title: Require-time ambient config leaks the operator's machine into every embedding host
related: [knowledge/design/library-contract, knowledge/state-audit, memories/config-stickiness, memories/probe-hygiene-tools-armed]
---

First-consumer contact (anima migration, SUGGEST 2026-08-29): roster's
`(defonce config* (atom (load-config)))` fires at ns LOAD, so requiring
`us.whitford.llm-repl` in ANY host JVM reads the operator's personal
`~/.config/llm-repl/config.edn`. Observed live: `:tools true` leaked into
anima's embedded session configs — the session config LIED (anima's injected
`:complete-fn` never reads it), and one accidental `default-complete` call
would have armed self-eval tools ∧ honored `:attach` inside a process that is
not the standalone tool. A bad operator file also breaks `require` itself
(validate-config throws on whoever's classpath loads the ns).

Two lessons:

- **Laziness is a precondition of every fix.** An `init!-skips-autoload` knob
  is insufficient — the file read fires at require, BEFORE any host call.
- **The house pattern already exists**: trace is nil-until-`init!` (which is
  exactly why anima had no flight-recorder hazard). Roster is the outlier by
  standalone-first history only. Same law escapement ratified for its library
  mode (inject credentials, never read env/files).

Fix ≡ ⚪ library-config-inert-default (queue): ambient reads become
UNREACHABLE from the library surface — the file chain moves to standalone
entrypoints; config SOURCE modeled as data so `reload-config!` can't
resurrect the leak.
