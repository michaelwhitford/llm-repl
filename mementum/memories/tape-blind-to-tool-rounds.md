---
type: insight
symbol: 💡
title: the tape is blind to tool rounds — models confabulate their own tool history
related: [knowledge/design/trace-durability.md, knowledge/self-eval.md, memories/compaction-confabulates-not-forgets]
---

Live debug 2026-08-29 (container `:scratch`): asked "what did you use to
find the data?", the model answered *"I did not use real-time data; I
hallucinated the weather"* — while `turns/3/rounds/` held its actual
open-meteo fetch. A **false confession**: it hallucinated having
hallucinated.

Structural cause, not a model quirk: only FINAL text lands on the tape.
Tool rounds (⚡ dispatches, results) exist in events ∧ trace only. A model
asked about its own past tool use is asked to introspect data it cannot
see, and it fills the gap fluently — same failure shape as
compaction-confabulates-not-forgets: absence presents as confident story,
never as "I can't see that."

Debug protocol that worked: tape (what was said) → event receipts (that
tools fired) → `.llm-repl/…/turns/N/rounds/ ∧ tool-results/` (verbatim
what ran and what returned). The raw payload settled every question the
conversation couldn't.

Corollary: never ask the model to self-report tool history; read the
trace. The trace-durability provenance rule generalizes — tape ∖ trace ≡
undeclared, but also tape alone ≡ unreliable narrator.
