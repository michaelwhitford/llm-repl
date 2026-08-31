---
type: Architecture
title: The measurement surface — how a distribution leaves the instrument (D12, proposed)
status: PROPOSED 2026-08-31 — awaits human ratification. Nothing built. The wire facts are RUNTIME-VERIFIED against the live qwen36-35b server (port 5100, 2026-08-31); the contract facts are source-read at the file:line named. Build only after § Rulings wanted is answered.
related: [design/architecture, design/library-contract, design/trace-durability, wire-protocol, upstream/escapement]
---

# The measurement surface — D12 (proposed)

> Drafted 2026-08-31 while ingesting verbum's SUGGEST drop (§ complete ≡
> `git show dcfa1d6:SUGGEST.md`). The drop asks for five knobs; four are
> mechanical once ONE question is answered, and this document is only about
> that question: **a distribution has no way out of this instrument.**
> Expands ⚪ logprobs-surface (queue.md) — its D4 ruling gates the rest.

## The problem, exactly

`:complete-fn` is the injected-IO seam and its published contract is TEXT
(library-contract § 3):

```
:complete-fn ≡ (fn [config slug] → (fn [tape] → reply-text))
```

Text is not a narrow *encoding* of the return — it is the whole return.
Every site agrees, and they are few:

| site | file:line | what it does with the return |
|---|---|---|
| `plain-complete` | completion.clj:198 | `loud-final-text` → String |
| `tool-complete` | completion.clj:375 ∧ 387 | `loud-final-text` → String (both exits) |
| `eval-rf` | llm_repl.clj:209 | `(tape/append-assistant t (complete t))` |
| `eval!` | llm_repl.clj:566 | `(complete snapshot)` → `append-assistant` |
| `bounce!` / `trampoline!` | llm_repl.clj:657 / 687 | via `eval-rf`, then read the last text |
| `run-battery!` | llm_repl.clj:617 | `transduce` over `eval-rf` — acc ≡ the TAPE |

So the answer is small in code and large in consequence: five call sites,
one published contract, one library consumer (anima) already injecting
across it.

**The framing that decides everything else:** the tape is the PROGRAM; a
distribution is a measurement *of a step*, not a part of it. The repo
already has a name for this split — `events ≡ chrome ¬tape`, S3\* audit vs
S5 identity. Measurements belong on the RESULT and in the TRACE plane, and
must never enter the tape, the session map, or `registry/view`. Two
existing invariants make that non-negotiable rather than tasteful:

- **the registry stays EDN and crosses the wire** — `registry/view` was
  built to get a 300-session index from 623.8KB down to 36.0KB
  (wire-protocol.md). Per-token top-5 logprobs for ONE turn are larger than
  that entire payload. A tape that carries distributions un-does the
  projection arc completely.
- **`compact!` rewrites the tape** — the one true write. Measurements
  attached to turns would either be rewritten (lying about which run
  produced them) or orphaned.

## What the live server actually says (runtime, 2026-08-31, port 5100)

Four probes, because the ask's wire claims were worth checking before
designing around them. **Three of the four changed something.**

1. **Generated-token logprobs: CONFIRMED, richer than asked.**
   `{"logprobs": true, "top_logprobs": 5}` on `/v1/chat/completions`
   returns `choices[0].logprobs.content` ≡ a vector of
   `{id, token, bytes, logprob, top_logprobs[…]}`. Token STRINGS *and*
   BYTES *and* token IDs ride along ⇒ the drop's P6 "token echo" is free,
   and cross-model comparison can key on IDs where tokenizers agree.
   Note the shape: ONE session knob (`::logprobs N`) maps to TWO wire keys
   (`logprobs true` ⊕ `top_logprobs N`), not one.
2. **Prompt scoring (P1b): NOT available on this build.** `echo: true`
   with `logprobs` on `/v1/completions` returned only the GENERATED token's
   distribution — no prompt-token logprobs. And `n_predict: 0` on the
   native `/completion` still emitted a token (`" Paris"`). So the
   "zero-generation read" does not exist here; the **1-token continuation
   distribution is the mode**, on all three endpoint paths. The drop
   pre-authorized this as a degraded mode *if recorded as such* — it must
   be recorded as such.
3. **The resolved sampling spec comes BACK, per request.** The chat
   response carries `__verbose.generation_settings` — `seed` (42 echoed
   back verbatim), `temperature`, `top_k`, `top_p`, `min_p`, `n_predict`,
   `grammar` — plus `id_slot`, `tokens_evaluated`, `tokens_cached`, and
   `timings`. This **retires P3's premise**: "server default is not a
   number" was true of what we SEND and false of what we can READ. Record
   what the server resolved, not what we intended.
4. **`/props` answers** (both live ports): the server's own defaults and
   identity — P5's provenance read is reachable and cheap.

Evidence tier for everything above: RUNTIME, one machine, one llama.cpp
build, 2026-08-31. A different build may answer (2) differently — which is
precisely why the mode that landed must be recorded with the measurement.

## Constraints any answer must respect

- `:complete-fn` is PUBLISHED (library-contract § 3) and anima injects
  across it TODAY. A change that breaks a host's existing fn is a break of
  the contract we just watched a consumer shrink onto.
- The registry stays EDN; no fn/atom/record in a session (D2/S2).
- `eval-rf`'s accumulator IS the tape, and G1/G2 pin its arity and eagerness.
- Receipts, never silence — a measurement that fails to attach must say so.
- bb ∧ JVM twin; the send-ring is memory-only and byte-capped (8MiB).

## Option space

**A — widen the completion return (`text | {:text … :measurements …}`).**
One normalization fn, called at the two consumption sites (`eval-rf`,
`eval!`). A String stays valid forever ⇒ anima's injected fn is untouched;
a host that wants to report measurements returns a map instead. Cost: the
drivers must each decide where the extra data lands, and `run-battery!`
has nowhere to put it (below). Buys: the seam is explicit, typed, and
symmetrical for hosts and for our own completion path.

**B — ride the trace send-ring.** `ring-record!` already captures EVERY
physical send with its verbatim response (trace.clj:279), tapeless or not,
✓ ∧ ✗ alike. If llamacpp lifts logprobs into `:backend-metadata`, the data
is *already* in the ring with zero contract change. Cost: entries are
`{:at :slug :ok? :request :response :bytes}` — **no correlation id**, so a
caller can only guess which entry was its call (slug ⊕ recency); the ring
is memory-only and byte-capped, so a census pulling top-20 logprobs over
300 bounces will silently EVICT its own evidence. Buys: nothing new to
design; excellent as a *supplement*, unfit as the primary channel.

**C — a per-turn collector the driver binds** (dynamic var ∨ atom in opts).
Uniform across all drivers including the fold, no contract change. Cost:
an INJECTED complete-fn must write to the collector explicitly, so the
seam only works for hosts that opt in — the identical shape to the queued
⚪ trace-capture-hook (`:io/ref` rides ✓ only when we own the completion).
Two tickets, one seam: worth solving once.

**D — a separate measurement verb** (`measure!`/`probe!` that never touches
a tape). Cleanest conceptually — distributions are a different question
than conversation. Cost: a new command and a second path through
completion; and it duplicates what `bounce!`/`trampoline!` already are —
**tapeless, fixed-prefix, per-item error-as-data**, which is exactly the
census's shape already.

## Recommendation

**A + D-by-recognition, staged; B as supplement; C deferred to its twin ticket.**

1. **Channel (A).** Add `completion/reply-of` — a pure normalizer:
   `String → {:text s}`, `map → map` (missing `:text` fails loud). Call it
   at `eval-rf` and `eval!`. A String return remains valid indefinitely;
   this is λ extend — an open slot, addition not modification, absence ≡
   today's bytes.
2. **Destination (D by recognition, not by new verb).** The tapeless
   drivers ALREADY are the measurement verb. `bounce!` gains
   `:repl/measurements`; `trampoline!` gains it per bounce (beside the
   existing per-bounce `:input`/`:output`/`:error`). `eval!` gains it on
   the RESULT map only — never on the tape, the session, or the projection.
   No new command; the census drives what already exists.
3. **Fold (`run-battery!`): explicitly out of scope for increment 1.** The
   rf's acc is the tape, and widening it to `{:tape … :measurements …}`
   would break G1/G2 and every existing rf consumer for the one driver the
   census needs least (a battery is a fold; a census is a fan-out). If it
   is later wanted, it arrives as an explicit `:collect` in opts (option C),
   never as a changed accumulator.
4. **Storage (B as supplement).** When tracing is on, the verbatim response
   already lands as the `response` blob and in the ring — so measurements
   are durable *for our own path* with no new persistence. Document the
   ring-eviction hazard for census-scale runs; do not change the default.
5. **Provenance rides the same slot.** `:measurements` carries the
   server-RESOLVED `generation_settings`, `id_slot`, `tokens_cached` and
   `timings` lifted from `__verbose` — which is P3's and half of P5's ask,
   for free, on the response we already parse.

Sketch (illustrative, not ratified):

```clojure
{:repl/id     :probe
 :repl/output "yes"
 :repl/measurements
 {:logprobs [{:token "yes" :id 9405 :logprob -0.042
              :top [{:token "yes" :logprob -0.042} {:token "Yes" :logprob -3.343} …]}]
  :sampling {:seed 42 :temperature 0.0 :top-k 20 :top-p 0.95 :min-p 0.01}  ; server-RESOLVED
  :server   {:model "qwen36-35b-a3b" :id-slot 1 :tokens-cached 168}
  :mode     :generated-tokens}}   ; ≢ :prompt-scored — the honest register (finding 2)
```

## Rulings wanted (before any code — λ coherence)

1. **Nested `:measurements` map, or flat `:repl/logprobs`?** The drop
   sketched flat. RECOMMEND nested: timings, cache stats and resolved
   sampling all want the same slot, and a nested slot absorbs the next ask
   without a second contract change (λ extend). Divergence from the drop is
   deliberate and should be told to verbum if taken.
2. **Does `eval!` carry measurements at all?** RECOMMEND yes, on the result
   map only — a census may measure committed turns too, and the tape stays
   clean either way. (The alternative — tapeless drivers only — is more
   conservative and costs the census nothing today.)
3. **`run-battery!` deferred?** RECOMMEND yes, as argued in § Recommendation 3.
4. **Is `:measurements` gated by the knob, or always populated when
   available?** RECOMMEND gated: `::logprobs` (∨ a `::measure` knob) off ⇒
   the key is ABSENT, and today's results stay byte-identical. Cheap is not
   free — `__verbose` is a large map and its presence changes payload size.
5. **Knob spelling.** `::logprobs [:maybe [:int {:min 0 :max 20}]]`, 0/nil ≡
   off, N ⇒ wire `logprobs true` ⊕ `top_logprobs N` (finding 1: one knob,
   two wire keys). RECOMMEND as written; D11-qualified, in
   `session-opts-schema` so a typo teaches.
6. **P1b register.** Since prompt-scoring is unavailable on this build
   (finding 2), RECOMMEND shipping the 1-token continuation distribution
   and stamping `:mode :generated-tokens` in every measurement — so a
   census can never silently mix modes across servers or builds.
7. **Injected-`:complete-fn` hosts get nothing unless they return the map.**
   RECOMMEND documenting the bypass in library-contract § 3 and solving it
   together with ⚪ trace-capture-hook (option C), not before.

## Acceptance (the build this authorizes, once ruled)

- `reply-of` table-tested: String, map, map-without-`:text` (loud), nil (loud).
- **Backward-compat LOCK**: a stub `:complete-fn` returning a bare String
  drives eval!/bounce!/trampoline!/run-battery! with byte-identical results
  to today — the test that protects anima.
- Knob → wire: pure table test over `llama-wire` (absent ⇒ no keys; N ⇒
  both keys).
- Wire → measurements: pure lift from a CAPTURED response fixture (the
  live JSON from finding 1, checked in as a fixture — no network in CI).
- `:measurements` never appears in `registry/view`, a session map, or a
  tape — asserted, not assumed.
- Twin: bb ∧ JVM. Live smoke recorded as a live smoke (never claimed as a test).

## Non-goals (the two-tier membrane, restated)

No grading/oracle (verdicts belong to anima's kernel ∧ verbum's lambda_ast),
no white-box (hidden states/layers/attention ≡ verbum's python tier), no
fleet management. This instrument carries TEXT ∧ DISTRIBUTIONS — and now
the provenance needed to make them citable.

## Estimate

Channel ⊕ logprobs ⊕ provenance-lift: ~1 session. The remaining drop
tickets (grammar, seed/sampling, per-eval caps, `/props`) get materially
cheaper afterward — they become knob→wire additions against a seam that
already exists. Raw-completion mode (P4) stays independent and larger.
