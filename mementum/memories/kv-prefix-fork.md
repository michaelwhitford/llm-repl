---
type: insight
symbol: 💡
title: fork-at-prefix is cheap because the KV cache IS the head position
related: [knowledge/design/architecture]
---

`fork! {:at N}` (branch an older turn) gives good outputs cheaply because
llama.cpp reuses the KV prefix (`cache_prompt` LCP reuse ⊕ `id_slot` pin) —
human-verified live on past-point forks.

The formal reading (design § formal shape): the KV cache MATERIALIZES the
tape head's position. A completion from a cached prefix ≡ the head not
re-walking the tape; fork-at-N ≡ a head REWIND, cheap because the prefix is
shared, not copied. Discovered as a performance fact; structural in the
machine analogy.

Practical: trampoline!/ab! bounce off a fixed point for near-free after the
first completion warms the slot; sequential fan-out is about slot
contention, not prefix cost.
