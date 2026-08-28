---
type: memory
symbol: 💡
title: escapement node-ids must be keywords — a string silently loses its first char
related: [../knowledge/upstream/escapement, ../knowledge/design/trace-durability]
---

`escapement.capture/encode-node-id` assumes keyword PRINT form: it strips
the leading char (the `:`). Pass `:ouro` → paths say `nodes/ouro/…` ✓.
Pass `"ouro"` → paths silently say `nodes/uro/…` — no error, just a
misfiled tree you discover when reads return nil.

Runtime-pinned 2026-08-28 (bb REPL, escapement 1.0.1) during the
trace-durability build. Two companion pins from the same session:

- `kind` args are STRINGS (`"response"`, `"tool-results/<id>"`) — a
  keyword kind renders its colon into the filename (`:response.edn`).
- Only `capture-request!` is first-write-wins; `capture-blob!` OVERWRITES
  (last wins). Multi-round captures need distinct kinds
  (`rounds/<k>-response`), or they eat each other.

Matters cross-repo: anima will ride this same capture layer. llm-repl's
slugs are already keywords, so the trap is invisible there — until someone
"helpfully" calls `(name slug)` at the seam. Don't.
