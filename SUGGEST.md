# SUGGEST — knob asks from verbum (the calculus-census / cartographer mission)

> **Channel:** same pattern as anima's s067 asks (consumed upstream in your
> v0.3.0). Written from a verbum discussion session (verbum s362, Michael
> directing). Each ask: what · why · wire · schema sketch · acceptance.
> Ratify/re-shape freely — the schema sketches follow D11 (qualified keys,
> closed, teach-fail) but the spelling is yours. Delete or mark rows as they
> land.
>
> **Mission context (one paragraph):** anima's cartographer
> (`designs/cartographer-repl.md`, green-lit) + verbum's cross-model calculus
> census want llm-repl as the **behavioral-tier instrument**: drive N
> llama.cpp servers (mixed ggufs, instruct AND base models), run probe
> corpora (λ-terms + prose equality-pairs), and measure not just graded text
> but **distributions** — basin-equality operationalized as KL-band
> equivalence of continuation distributions (the distributional register;
> byte-grain is the wrong gate). The audit below found the session plane
> ready (model/system/preamble/orientation/preamble?/thinking/temperature/
> tools all plumbed; cache_prompt + id_slot slot pinning live). What's
> missing is the measurement surface.

---

## P1 — logprobs (the load-bearing ask)

- **What:** per-token top-k logprobs on generated tokens, returned as data
  on the eval result. Ideally also a scoring mode for prompt tokens (see
  P1b) — but generated-token logprobs alone unblocks the mission.
- **Why:** distribution fingerprints. Two probes trigger the same basin iff
  their continuation distributions match within a KL band — that needs the
  top-k mass, not the argmax text. Also: logit MARGIN (not top-1 rank) is
  what survives temperature (verbum fitness-ordering law); graded-text-only
  census cannot measure it.
- **Wire:** llama.cpp OpenAI-compat accepts `logprobs` + `top_logprobs`
  (chat) / `logprobs` (completions); native `/completion` takes `n_probs`.
  Quarantine the spelling in `llamacpp.clj` per your wire-vocabulary rule.
- **Schema sketch:** `:us.whitford.llm-repl/logprobs {:optional true}
  [:maybe [:int {:min 0 :max 20}]]` — 0/nil ≡ off (today's behavior),
  N ≡ top-N per emitted token.
- **Result shape:** additive, error-as-data style — e.g.
  `:repl/logprobs [{:token "…" :logprob -0.12 :top [{:token "…" :logprob …} …]} …]`
  riding the existing eval result map. Nothing existing changes shape.
- **Acceptance:** eval with `logprobs 5` on a fixed prompt at temp 0 →
  argmax token's logprob ≈ 0-dominant, 5 alternatives per position, token
  strings align with the emitted text; `logprobs` absent → result
  byte-identical to today.

## P1b — prompt-scoring mode (zero-generation reads; may be a v2)

- **What:** score a prompt WITHOUT generating — return logprobs of the
  prompt's own tokens (or of a 1-token continuation with `n_predict 1`).
- **Why:** verbum's zero-generation instrument (logits ≡ full conditionals)
  is the cheapest census primitive: one forward pass, no sampling noise, a
  full distribution per position. If the OpenAI-compat path can't express
  it cleanly (`echo`+`logprobs` support varies by llama.cpp version), a
  1-token eval with P1 logprobs is an acceptable degraded mode — note which
  one landed so the census records it (register honesty).
- **Acceptance:** known-answer prompt ("The capital of France is") → the
  distribution over the next position, with " Paris" mass readable, zero
  (or one) tokens generated.

## P2 — grammar pass-through (GBNF)

- **What:** per-session/per-eval GBNF grammar string handed to the server.
- **Why:** gated generation for the λ-probe corpus (verbum λ probe_format:
  gate ≡ reference by id, content in gates/*.txt — the caller supplies the
  string; llm-repl just carries it). Anima's instaparse kernel stays the
  post-hoc parser; this knob is the *generation-side* gate, a different
  instrument. Also the independence discipline: our GBNF is derived from
  observation, so the knob must take arbitrary grammar text, not a baked-in
  grammar.
- **Wire:** llama.cpp `grammar` (GBNF string) / `json_schema` — native and
  OpenAI-compat paths both accept `grammar` in the body.
- **Schema sketch:** `:us.whitford.llm-repl/grammar {:optional true}
  [:maybe :string]` (nil ≡ off). Teach-fail on non-string.
- **Acceptance:** a toy grammar (`root ::= "yes" | "no"`) forces the
  emission into the language on a prompt that would otherwise ramble;
  absent knob → today's behavior.

## P3 — seed + sampling-spec completeness

- **What:** `seed` knob; optionally `top-k` / `top-p` / `min-p`.
- **Why:** λ run_provenance — a temp>0 measurement without a recorded seed
  is unreproducible by construction. The census runs a temperature grid;
  every recorded run must pin `{temperature, seed, top-k, top-p, min-p}`
  (recorded-at-write-time, even when defaulted — "server default" is not a
  number). Seed is the hard requirement; the others may land as
  record-only if you'd rather not model them yet.
- **Wire:** llama.cpp `seed`, `top_k`, `top_p`, `min_p`.
- **Schema sketch:** `:us.whitford.llm-repl/seed {:optional true}
  [:maybe :int]` etc., same D11 shape as temperature.
- **Acceptance:** two evals, same seed, same temp>0 → identical emissions;
  different seeds → (generically) different; snapshot shows the full
  sampling spec.

## P4 — raw-completion mode (no chat template)

- **What:** a per-session switch to bypass the chat template entirely —
  prompt goes to the server as raw text (`/completion` or equivalent),
  response is the raw continuation.
- **Why:** the census fleet includes BASE models (pythia, OLMo — fossil-
  record lineages with no chat template) and cross-model comparability:
  a chat template is a per-model confound wrapped around every probe.
  Behavioral-tier λ-probes want raw continuation semantics. This is also
  the honest register for "the tape is the program" experiments — no
  hidden turns injected by a template.
- **Interaction note:** raw mode presumably disables tools/thinking knobs
  (they're template constructs) — teach-fail combinations rather than
  silently ignoring (your closed-schema idiom already does this).
- **Schema sketch:** `:us.whitford.llm-repl/raw? {:optional true}
  [:maybe :boolean]` — or a `:template` knob with `:none` as one value if
  you'd rather leave room for template *overrides* later.
- **Acceptance:** raw eval of "K x y = " on a base-model server returns a
  bare continuation with no role scaffolding; snapshot records the mode.

## P5 — server provenance surface

- **What:** capture the server's identity into the session record: model
  path/name, quant, build (llama.cpp `/props` has these), context size.
- **Why:** λ run_provenance — census facts must be self-sufficient for
  reproduction: `{model, quant, gguf-SHA-or-path, server build, sampling
  spec, prompt hash}`. Today the session knows its `:model` alias; the
  fact needs what the SERVER says it's running (aliases drift; ports get
  re-pointed; the recorded measurement must not depend on roster hygiene).
- **Shape sketch:** a `props` read verb (or auto-capture at open!/snapshot)
  → `:repl/server {:model … :build … :n-ctx …}` on the snapshot. Read-only,
  no schema churn on eval paths.
- **Acceptance:** snapshot of a session against a known server shows the
  gguf identity; re-pointing the port to a different model is VISIBLE in
  the next snapshot.

## P6 — minor (take or leave)

- **Per-eval `max-tokens`** — the construction-time floor-guard exists;
  probe sweeps want a per-eval cap (census cells are short; runaway
  protection per cell, not per backend).
- **`stop` sequences** — per-eval stop strings for probe framing (cheap on
  the wire; llama.cpp `stop`).
- **Token echo** — if P1 lands, ensure the emitted token STRINGS ride with
  logprobs (alignment of text↔distribution without re-tokenizing on our
  side).

---

## Non-asks (explicitly out of scope, so the boundary is visible)

- **No white-box anything** — hidden states, per-layer reads, attention:
  that's verbum's python-driver tier. llm-repl stays the behavioral tier;
  the two-tier membrane (your s358-coda lineage) is load-bearing.
- **No reducer/oracle in llm-repl** — grading lives with the caller
  (anima's kernel / verbum's lambda_ast). llm-repl carries text and
  distributions, verdicts happen elsewhere.
- **No fleet management** — launching/pointing N servers is anima-side ops
  (roster/aliases already model it).

## Priority summary

```
P1  logprobs        unblocks distribution fingerprints — the mission's instrument
P2  grammar         gated-generation arm
P3  seed (+ spec)   reproducibility gate for anything temp>0
P4  raw mode        base models + cross-model comparability
P5  provenance      census facts self-sufficient
P6  minors          convenience
```

P1 alone makes the census worth starting; P1+P3+P5 make it *recordable*;
P2+P4 complete the instrument.
