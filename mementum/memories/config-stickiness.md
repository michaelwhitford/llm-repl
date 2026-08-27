---
type: insight
symbol: 💡
title: open! persists config — poison keys survive "fixed" retries
related: [knowledge/design/architecture]
---

`open!` PERSISTS its config into the session; later clean opts merge AROUND
previously-persisted poison keys (merge only overwrites keys PRESENT in the
new opts — absent keys keep their old, possibly broken, values).

Symptom: the identical error after a retry you believed fixed. The fix
"took" in your opts but the session still carries the bad key.

Remedy: `drop!` resets the session (or explicitly override the poison key).

Design consequence (v0.3.0): config stickiness is a FEATURE (a repl
remembers its interpreter) — the trap is forgetting that absence ≠ reset.
Docstrings on open!/eval! should keep saying so.
