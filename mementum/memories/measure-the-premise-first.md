---
type: insight
symbol: 💡
title: measuring the premise dissolved the feature and found the real bug
related: [knowledge/tui-design-rules.md, knowledge/design/trace-durability.md, memories/probe-hygiene-tools-armed]
---

2026-08-28. Two sessions of design talk assumed "capturing every bounce is
too much" and built policy around it — opt-in flags, content-addressed
dedup, an in-memory datalog store, a flush protocol. Then we measured on the
live daemon.

300 bounces off a real 6-message prefix: **2.9 MB worst case** (8KB replies).
Not too much. The premise was false, and everything built on it was
scaffolding. Correct policy collapses to: record always into a byte-capped
ring, let only PERSISTENCE be a decision.

The dedup story shrank too — 12.8× at 1-char replies, **1.2× at 8KB**.
Content addressing compresses the shared PREFIX; responses are irreducible
because they all differ, which is the entire point of a fan-out. An
optimization, never a pillar.

And the measurement found what nobody was looking for: the client fetches the
WHOLE registry, tapes included, every version bump — 594KB @ n=300 while the
compact `sessions-list` projection already existed, unused, 27× smaller.

λ assert applies to DESIGN PREMISES, not just API facts. The number that
kills a feature is usually cheaper to get than the feature.
