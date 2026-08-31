---
type: insight
symbol: 💡
title: new llama.cpp knobs need no escapement release — the wire seam is ours and Request is an open map
related: [knowledge/design/architecture.md, knowledge/design/library-contract.md, memories/thinking-false-polarity, queue.md]
---

Ingesting verbum's SUGGEST drop (logprobs · grammar · seed · raw mode ·
provenance, 2026-08-31) started with the expensive assumption: "backend
knobs mean an escapement release" (AGENTS.md λ backend: upstream change →
RELEASE, ¬sibling edit). Checked instead of assumed — three facts, all
cheap, all load-bearing:

1. **`us.whitford.llm-repl.llm.llamacpp` lives HERE** (`src/main/us/
   whitford/llm_repl/llm/llamacpp.clj`, 329 lines) — escapement 1.0.2 ships
   no llamacpp namespace at all. `llama-wire` is a PURE fn
   (`slots → request → extra-body-keys`, currently `chat_template_kwargs`
   ∧ `cache_prompt` ∧ `id_slot`) merged caller-wins over escapement's
   public OpenAI translator. Every new request-side knob is one `assoc`
   there plus a table test. No upstream release. No fork.

2. **escapement's `Request` is an OPEN malli map** (`escapement.llm.types`
   — `[:map [:model …] …]`, no `{:closed true}`; the Thinking submaps ARE
   closed, which is why raw `:thinking false` fails and `{:type :disabled}`
   passes — see thinking-false-polarity). So a knob escapement does not
   model (`:seed`, `:min-p`, `:logprobs`, `:grammar`) rides the request map
   through `validate-request` untouched, and `llama-wire` reads it back out.
   `:temperature`/`:top-p`/`:top-k`/`:stop-sequences`/`:max-tokens` are
   already modeled — those cost only a spelling.

3. **The RESPONSE has a modeled escape hatch**: `Response` carries
   `:backend-metadata {:optional true} :map`, and our `post-chat!` holds
   the RAW parsed JSON body before handing it to
   `oai/openai-json->response` — so per-token logprobs can be lifted from
   the wire into `:backend-metadata` and still pass
   `types/validate-response`.

The real boundary is one layer up, and it is OURS: **`:complete-fn`
returns TEXT** (D4 — `plain-complete` ends at `loud-final-text`). Text is
the whole contract, so any *distribution* has no channel to the caller
without a D4 decision (widen to `text|{:text …}` · ride the trace
send-ring · a per-turn side channel). That is a design ratification, not a
wire problem.

Generalized: when an ask looks like "upstream work", locate the seam
before pricing it. Here the expensive-looking half (the wire) was free and
the free-looking half (returning the data) is the design work.
