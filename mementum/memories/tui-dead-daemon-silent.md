---
type: mistake
symbol: ❌
title: TUI renders a dead daemon as live — poll swallow masks attach loss
related: [knowledge/design/architecture, knowledge/attach-topology, memories/interrupt-ghost-race]
---

Live-found (tui-local-daemon-human-pass, 2026-08-27): `bb stop` killed the
daemon but the attached TUI kept rendering — frozen at the last snapshot,
indistinguishable from "nothing happened".

Cause is structural, in `client.clj`: `fetch` collapses ALL failures to nil
(`catch Throwable _ nil`), and the poll loop reads nil as "no change"
(`when (and reg ...)`). Dead socket → nil forever → caches frozen → stale
frames at 150ms, no signal, no exit. The docstring's goal was right (a
dropped connection must not crash the poll thread); the resolution wrong
(it destroyed the error signal entirely).

This violates the attach contract (unreachable → fail_loud ∧ exit) and is
the exact λ escalate anti-pattern: silent fallback masking down_container
as lost_state.

Fix rides refactor step 5 (the poll loop is deleted there anyway): fetch
returns `{:ok v} | {:err e}`; N consecutive errors → attach-lost surfaces
in the TUI — banner + loud exit. Don't patch the old loop; encode the
requirement so step 5 can't miss it.
