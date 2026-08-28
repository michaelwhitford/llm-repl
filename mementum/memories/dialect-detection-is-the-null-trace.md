---
type: mementum/memory
symbol: 💡
title: Dialect detection is what auditing looks like when there is no trace layer
related: [../knowledge/design/trace-durability.md, ../knowledge/compaction.md]
---

# Dialect detection is the null-trace audit

louisabraham.github.io/load-bearing (read 2026-08-28, data pulled and
re-computed locally): 1,000 GitHub PRs/day since 2025-01 (461k docs, 51M
words), KL-divergence k-means → 10 vocabulary clusters. One cluster ARRIVED
2026-02-16 (baseline ~29 docs/wk → 2,582/6,216 last week) and now covers
~37% of human-attributed PRs. Its top words — load-bearing (39× lift),
plainly, quietly, refusal, survived, re-derived, asserted — are Claude's
dialect. This repo is saturated with the same cluster: seam(×61) loud(×63)
verbatim(×31) ratified(×25) chokepoint(×15) all rank in its thousand.

The insight for trace-durability: "human-attributed" ≡ tape attribution
with no generation behind it. With no trace layer, forgery detection
degrades to stylometry — aggregate-only, never per-document, and unable to
distinguish laundering from genuine absorption (a human who learned the
dialect writes identical text). `tape ∖ trace ≡ undeclared edits` observed
at planetary scale. Structure beats stylometry: a capture layer makes
provenance a lookup, not an inference. Secondary: the prior AI dialect
(enhancing/streamlining/enterprise-grade) is DECLINING 12%→4% as this one
rises — fingerprints displace, they don't erase.
