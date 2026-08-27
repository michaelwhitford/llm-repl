---
type: memory
symbol: 💡
title: interrupt ghost — client interrupt orphans the server-side tool loop
related: [design/architecture, self-eval]
---

# interrupt ghost race

An nREPL client timeout that sends `:interrupt` kills the CLIENT's deref, not
the driver's tool loop. The orphaned `eval!`/tool-loop keeps running
server-side — dispatching tools, sending completions, and finally `store!`-ing
its result. A client that pops the dangling user turn and retries then RACES
its own ghost (interleaved receipts and doubled `✓@N` events observed live,
2026-08-27; the llama log showed both loops' requests interleaved).

**Practice until designed away:** never drive long completions synchronously
over a timeout-bearing client. Dispatch server-side and poll:

```clojure
(def r* (atom :pending))
(future (reset! r* (repl/eval! :slug "…")))
;; poll @r* cheaply; the completion is never hostage to client policy
```

**Design input** (recorded in architecture § D2): any future cancel/ticket
surface must cancel SERVER-side, or cancel is a lie that leaves a zombie
macrostep racing its replacement.
