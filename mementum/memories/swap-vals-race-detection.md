---
type: Memory
symbol: 💡
title: swap-vals! [old new] ≡ TOCTOU-free race detection; interloping stubs ≡ deterministic race tests
related: [design/architecture, memories/interrupt-ghost-race]
---

# swap-vals-race-detection

Two techniques from refactor step 2 (registry, D2) that generalize:

1. **`swap-vals!`'s `[old new]` pair is exactly the before/after of the ONE
   successful application of `f`** — not merely "some prior value." So
   post-hoc checks against `old` are equivalent to detecting inside the swap
   fn, with zero TOCTOU window and no need to smuggle detection state out
   through the map. eval!'s raced-append (`old`'s tape ≠ completion
   snapshot), fork!'s which-no-op (`old`'s keys), open!'s created-vs-merged
   (`old` lacks slug) all ride this one fact.

2. **Race tests need no threads.** A stub `:complete-fn` that itself calls
   `registry/mutate!` mid-completion IS a concurrent client, deterministically
   interleaved at the exact seam the race lives in. 100% reproducible, zero
   flake. Reuse for compact! (step 4) and any future mutation seam.

Bonus: `add-watch` fires synchronously on the writer's thread under bb and
JVM alike — `wait-for-event!` parks/wakes with plain core, no j.u.c.
