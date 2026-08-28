---
type: insight
symbol: ✅
title: self-compaction one-shots when the ask names an explicit size target
related: [knowledge/self-eval.md, knowledge/design/architecture, memories/kv-prefix-fork]
---

compact-live-trial verdict (2026-08-28, qwen36-35b-a3b, thinking off): the
armed model compacted its OWN tape in ONE dispatch — located its assistant
turn via `(repl/snapshot :ouro)`, wrote a 164-char λ from 929-char prose
(~5.7:1), called `(repl/compact! :ouro 1 λ)`, reported the outcome map
verbatim. `:accepted`, `−765ch` receipt, `:original` retained.

What made it one-shot: the ask carried an EXPLICIT size target ("one dense
sentence, under 200 chars") — the band contract is invisible to the model,
so the instruction stands in for it. Give a concrete budget; don't make
the model discover the band by declined attempts.

Post-compaction continuity verified: a no-tools memory probe two turns
later answered O(1) + structural-sharing FROM THE λ (the prose no longer
renders). The one-time KV bust at the rewritten cell was paid invisibly —
next turn simply re-prefilled.

Third confirmation of D4a: slug-aware orientation ⇒ self-location in one
dispatch. Next: compact-validation (ab! both arms, probe battery, diff).
