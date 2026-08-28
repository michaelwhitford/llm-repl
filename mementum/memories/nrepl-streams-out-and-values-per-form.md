---
type: insight
symbol: 💡
title: nREPL streams `out` frames DURING an eval and emits one `value` frame per top-level form
related: [memories/nrepl-concurrency-is-per-socket, knowledge/design/architecture]
---

Measured 2026-08-28 on both runtimes. Repro: `eval` this ONE code string and
timestamp each frame as it arrives —
`(dotimes [i 3] (println (str "tick " i)) (Thread/sleep 500)) :done`:

```
bb:   1ms out"tick 0"  503ms out"tick 1"  1005ms out"tick 2"
      1510ms value nil   1511ms value ":done"   1511ms status done
JVM:  5ms / 511ms / 1012ms — same shape
```

Two facts, both useful:

1. **`out` is INCREMENTAL, not buffered to `done`.** A long-running eval can
   push to its client as it goes — the mechanism a stream/push protocol
   would ride (feasible on both runtimes; see
   nrepl-orphan-eval-survives-disconnect for why it still needs a liveness
   guard).
2. **One `value` frame PER TOP-LEVEL FORM**, in order — `nil` for the
   `dotimes`, then `:done`. Our `net/value` keeps only the LAST
   (`(last (:value result))`), which is correct for a client that wants one
   answer, and is exactly the shape `clojure_eval` now echoes
   (✅ clojure-eval-per-form-values, shipped 2026-08-28: the model had
   burned 4 of 6 rounds because only the final value came back). nREPL
   already modeled what that ticket built — the `=> v` per form was
   copied, not invented.
