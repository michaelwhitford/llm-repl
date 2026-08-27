---
type: memory
symbol: 🎯
title: nREPL extension assessed and declined — bb's stack is sealed; MCP facade covers the need
related: [design/architecture, interrupt-ghost-race]
---

# nREPL extension — assessed 2026-08-27, declined

Question: custom `llm/*` nREPL ops (typed commands, streamed receipts)?

**Empirical findings (bb 1.13.219, babashka.nrepl 0.0.6-SNAPSHOT builtin):**
- Protocol: friendly — unknown ops fail clean, `describe` advertises ops,
  multi-response-per-id is NATIVE (the receipt-streaming shape, free).
- Library (babashka.nrepl 0.0.8): extension designed in —
  `middleware/default-middleware-with-extra-ops {:op handler}`, handlers may
  emit many responses per request.
- **bb builtin server: sealed.** `:xform` accepted but REPLACES the whole
  stack; `babashka.nrepl.server.middleware` not on userland classpath; the
  library-as-dep fails to load (`sci.impl.vars/push-thread-bindings` not
  exposed to userland SCI). Composing with the default stack is impossible
  in bb today.
- JVM: standard middleware, easy — but bb-primary makes JVM-only a twin
  violation.

**Verdict:** decline. D3's `wait-for-event!` long-poll delivers the async
need over plain eval; the MCP facade is the structured, capability-scoped,
model-facing wire (ops-not-eval ≡ a real sandbox boundary — that insight
transfers to MCP). nREPL stays the human/editor eval wire; every surface
speaks its own idiom.

Revisit only if: bb exposes the middleware ns upstream, or a second
full-eval-free wire is needed before the MCP facade exists.
