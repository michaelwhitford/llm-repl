---
type: insight
symbol: 💡
title: babashka.nrepl serializes evals PER CONNECTION — the socket is the multiplex, not the session
related: [memories/nrepl-extension-assessment, knowledge/tui-design-rules, knowledge/design/architecture]
---

Measured 2026-08-28 against the live container (bb) and a stock JVM
`nrepl/nrepl` 1.3.1. Repro (bencode by hand, no helpers): clone two
sessions, `eval` a 3s `Thread/sleep` on the first, 300ms later `eval`
`(+ 1 1)` on the second, time its answer — once with both sessions on ONE
socket, once with a socket each.

| channel | bb | JVM |
|---|---|---|
| 2nd **session**, same socket, while one parks 3s | **3012ms** (served AFTER) | 311ms |
| 2nd **socket**, while one parks 3s | 313ms | 311ms |

babashka.nrepl runs ONE thread per client connection and processes its
messages in order — cloned sessions on that socket buy `*ns*`/`*out*`
isolation, NOT concurrency. The JVM's `SessionThread` per session does give
concurrency, so this is a TWIN DIVERGENCE, and bb is primary.

Consequences, load-bearing:

- RemoteCore's poll/submit socket split is not fussiness — it is the ONLY
  concurrency primitive available. N concurrent channels ⇒ N sockets.
- A client-side frame demultiplexer (route by id, share a socket) buys
  nothing under bb. Don't build one.
- Any parked eval (`wait-for-event!`) makes its whole SOCKET unavailable.

Corollary: "three sockets is a smell of a missing multiplex" is an
assumption from JVM-shaped intuition. Here the runtime says sockets ARE the
multiplex.
