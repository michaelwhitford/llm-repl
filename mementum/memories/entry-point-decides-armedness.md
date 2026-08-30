---
type: insight
symbol: 💡
title: how you enter the process decides whether your probe is armed
related: [memories/probe-hygiene-tools-armed, knowledge/design/architecture.md, knowledge/self-eval.md]
---

Found while live-smoking `open! :defaults`, 2026-08-30. D10 (library inert
at require) split armed-ness by ENTRY POINT, and nothing on the surface
says so:

```clojure
;; bb -e … / any library host: INERT — no config read, ::tools nil
(repl/default-config)                       ;=> {::tools nil …}
;; the entrypoints read the chain (main's FIRST act; bb status too)
(roster/init! {:files (roster/config-sources)})
(repl/default-config)                       ;=> {::tools true …}  ; this machine
```

So the SAME probe is a plain completion under `bb -e` and a tool-armed
agent under `bb llm-repl` — the hygiene page's "disarm by hand" now has two
cases, and the dangerous one is the one that looks most like normal use.

Evidence, not inference: piping `:quit` into `--plain` (the loop's quit
token is `:q`; `:quit` is CHAT) reached an armed model, which dispatched
`clojure_eval` at the repl — `⚡ (require '[us.whitford.l…` in the events —
and one run ended `error: session dropped mid-completion`: the model
answered the word by ending the session, and D2's ✗ path caught it. The
same input under `bb -e` returned prose and touched nothing.

Corollary for the primary use case (a model driving this repl headlessly):
assert `(:us.whitford.llm-repl/tools (repl/default-config))` at probe start.
Armed-ness is a fact about the PROCESS, not the code.
