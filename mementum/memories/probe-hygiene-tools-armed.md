---
type: insight
symbol: ❌
title: measuring a model through this repl requires disarming :tools by hand
related: [knowledge/self-eval.md, knowledge/container.md, memories/thinking-false-polarity, memories/entry-point-decides-armedness]
---

Pre-flight for the (later 🚫'd) extension-horizon pilot, 2026-08-28: the
container's `config.edn` carries `:tools true`, and `roster/default-tools`
therefore returns true for every session `open!`s. Nothing in the driver
surface warns you.

The trap: **any measurement of what a model can do WITHIN one forward pass
is void if the model can reach `clojure_eval`.** It stops being a
measurement of the model and becomes a measurement of the model plus a
Clojure runtime — and the receipts look identical either way unless you
read them (⚡ dispatch receipts are the only tell). A β-reduction probe
would have been "answered" by the model shelling out to actual evaluation.

Hygiene for any probe session on this instrument, all of it per-session and
none of it default:

```clojure
;; keys re-spelled for D11 (2026-08-30): v0.2.0's bare :tools/:temperature/…
;; are DEAD — the closed session-opts schema rejects them now, teaching.
(repl/open! :probe {::repl/tools nil ::repl/temperature 0
                    ::repl/thinking false ::repl/preamble? false})
```

`:tools nil` disarms (self-eval.md: the config-root twin makes armed-ness a
machine fact, per-session nil still wins) · `:preamble? false` removes the
boot text · `:thinking false` → `{:type :disabled}` (polarity trap: raw
`false` fails Request validation — see thinking-false-polarity).

Banked while there: temp-0 completions off a fixed tape are byte-identical
(3/3), direct ≈170 ms vs traced ≈11 s (65×) on qwen3.6-35B-A3B.

Second-order lesson, cheaper than the first: the default that makes the
instrument *good* (armed, self-evaluating, the equal-clients thesis) is
exactly the default that makes it a *bad measuring device*. An instrument
that participates is not an instrument that observes.
