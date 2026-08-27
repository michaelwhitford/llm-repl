---
type: mistake
symbol: ❌
title: "{:thinking false} failure was misdiagnosed — validate-request polarity"
related: [knowledge/design/architecture]
---

Session `{:thinking false}` WORKS: build-request normalizes it → modeled
`{:type :disabled}` → llamacpp `chat_template_kwargs {enable_thinking
false}`; `true` ≡ omit ≡ server default (thinking ON).

The earlier record blamed "the wire" — WRONG. Raw `false` failed
escapement's Request malli: `validate-request` returns **errors-or-nil**,
and I misread that polarity (nil ≡ valid). Lesson doubled: (1) normalize
human-friendly knobs to modeled shapes at the boundary; (2) check a
validator's return convention before trusting a diagnosis built on it.

Only `:llamacpp` reaches this switch — escapement's stock openai translator
DROPS `:thinking`, which is why the custom backend exists.
