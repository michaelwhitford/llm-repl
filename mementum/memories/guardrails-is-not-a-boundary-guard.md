---
type: insight
symbol: 💡
title: "guardrails >defn is a dev contract, not a boundary guard — different job"
related: [knowledge/design/architecture, memories/thinking-false-polarity]
---

Reaching for function-level malli schemas, the obvious move is guardrails'
`>defn` — it's Kay's house style, escapement uses it throughout
`tools/protocol.cljc`, and it loads under bb (proven: our tool dispatch runs
through that ns). **Don't** — it does a different job than a command boundary:

| | guardrails `>defn` | boundary guard (D8) |
|---|---|---|
| audience | programmer (dev contract) | the model (λ mirror) |
| on bad args | throws/logs per config | returns `{:repl/error …}` data |
| production | compiles away by design | ALWAYS ON — the boundary IS prod |

The structural blocker: guardrails cannot change a function's RETURN value —
its callback reports, then throw-or-proceed. Errors-as-data-to-the-caller is
outside its design. Corollary trap: borrowing its inline gspec syntax
(`[Slug => :map]`) for always-on data-returning semantics would make a
familiar shape mean something different — attr-map style chosen instead
(D8, ratified 2026-08-29).

Escapement itself has ~zero def-macros (one unrelated `embedded-catalog`);
its "macro style" IS guardrails. The reusable part is the discipline, not
the tool: expansion ≡ hand-writable code, declarative part validated at
compile time, plain fns underneath.
