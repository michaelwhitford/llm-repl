---
type: insight
symbol: 💡
title: an orphaned eval loop OUTLIVES its client — a dead socket never reaches the evaluating thread
related: [memories/nrepl-concurrency-is-per-socket, memories/interrupt-ghost-race, knowledge/tui-design-rules]
---

Measured 2026-08-28, both runtimes. Repro: `eval` a loop that
`(intern 'user 'ticks (atom 0))` then forever swaps+`println`s+sleeps 200ms;
read 3 frames; CLOSE the socket; reconnect and read `@@(resolve 'user/ticks)`
twice, 2s apart. It kept ticking — 6 → 16 — identically on bb and JVM.

The JVM does raise `Broken pipe`, but on the transport/flush path
(`nrepl.transport/safe-write-bencode` ← `SessionThread`), reported in the
SERVER's stderr — it never propagates into the evaluating thread. So
`println` into a vanished client is a silent no-op from the loop's point of
view.

Consequences:

- **Never rely on client death to stop server-side work.** A push/stream
  protocol implemented as a long-running printing eval leaks one thread per
  attach until an explicit liveness guard (heartbeat ⊕ TTL) exists.
- **A BOUNDED park self-heals.** `registry/wait-for-event!`'s 25s timeout is
  not a nicety: it is why a detached TUI leaves nothing running. Prefer
  bounded parks over infinite loops on the wire, always.
- Second instance of the same shape as the interrupt ghost: the CLIENT's
  view of a call ending ≠ the SERVER's work ending.
