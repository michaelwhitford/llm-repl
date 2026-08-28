---
type: Reference
title: self-eval — the model as a client of its own repl (:tools)
status: active
related: [design/architecture, design/library-contract, container]
---

# Self-eval

> `:tools` config arms a tool loop: the model driven BY the repl becomes a
> client OF it — closing the equal-clients thesis (human ∧ editor ∧ model
> drive the same runtime). One tool ships: `:clojure/eval`.

## The executor (`tools` ns)

Per-form `read` ⊕ `eval` in the HOST process — full power, NO in-process
sandbox (the container wall is the sandbox; see
[container](/knowledge/container.md)). Contract, all error paths as data:

- EVERY top-level form echoes `=> v`, interleaved with captured `*out*` in
  temporal order — nREPL's exact frame shape (one `value` frame per form;
  memories/nrepl-streams-out-and-values-per-form is the measurement).
  Copied, not invented: with last-value-only the model burned 4 of 6
  rounds re-asking for values it had computed non-finally.
- `(ns foo)` persists across forms WITHIN a call, never leaks out
  (`binding [*ns* *ns*]` ≡ `load-string`'s discipline)
- `*out*` captured per call (output travels IN the result — same rule as the
  TUI alt screen: raw stdout never reaches a surface)
- future ⊕ timed deref ⊕ best-effort cancel — timeout as data (bounds the
  model's wait, not the host's CPU; bb has no hard thread kill); timeouts ∧
  errors carry everything echoed so far, partial values included
- each value pr-str'ed, the whole echo truncated at a marked char budget
  (an unmarked clip reads as a complete value and teaches wrong facts)
- errors `{:result … :is-error true}` — the model reads and corrects

Tool description names WHERE the model is and the bootstrap move (require →
`(help)`) — never enumerates the surface (the manual seam is truth;
enumeration would drift). `tool-registry*` ≡ open slot: hosts register more;
unregistered ≡ unreachable, not forbidden.

## The loop (`completion` ns in v0.3.0; core in v0.2.0)

send(request ⊕ :tools) → tool_use blocks? → dispatch each → append
assistant(content) ⊕ user(tool_results) to LOOP-LOCAL messages → resend,
until text-only ∨ budget. Branches on block PRESENCE, not :stop-reason
(robust to template drift).

- **Loop-local messages**: the tape only ever sees user ⊕ final text — shape
  stable, prefix cacheable, compaction untouched, rf ∧ all four drivers
  unchanged (tools ride bounce!/trampoline!/battery! for free).
- **Budget 8**: at the boundary every pending call is refused with a
  TEACHING tool_result (make the wrong next move unreachable — the only
  reachable act left is final text) and the model gets exactly ONE more
  inference. If it still calls tools, the empty reply is loud in the tape.
- **Depth guard** `*tool-depth*`: the eval tool hands the model eval!
  itself. Binding conveys through the eval future (bb futures convey
  bindings — live-verified) into any nested driver call, which completes
  PLAIN. Depth 1 of self-reference is the feature; depth 2+ is the fork
  bomb.
- **Receipts**: every dispatch → `⚡ slug code-preview` — the receipt IS the
  trace; attached surfaces watch the model work. Honest caveat: a tool turn
  bends `messages[] ≡ truth` (evals are effectful; replay from the tape
  alone won't reproduce) — the receipt stream is the trace; payload
  persistence is a deferred fork.

## Environment orientation (`tools-system`)

Armed sessions get a system-prompt paragraph saying WHERE THE MODEL LIVES —
tool descriptions carry mechanics; the system prompt carries identity (the
chat template expands tool defs; it cannot provide situation). Appended in
tool-complete, NEVER build-request: orientation rides iff defs are actually
on the wire — a depth-guarded nested completion must not claim a tool it
lacks; an unarmed ab! arm stays clean. Public var — hosts redef to their own
idiom.

## The restart lesson

`sessions*` is memory — arming via `open!` dies with the process (human hit
this live). Config root `:tools` (twin of `:default-model`) makes armed-ness
a MACHINE fact surviving restarts; per-session `{:tools nil}` still disarms.
The counterfactual is one fan away:
`(ab! :s {:bare {:tools nil} :armed {:tools true}} probe)`.

## Orientation findings (2026-08-27 container experiments)

Six-turn self-modification ladder ⊕ orientation A/B on qwen3.6-35b, driven
over nREPL. Instrument-relevant results (model-profile detail → anima):

- **Self-location is a runtime gap, not a prompt gap.** Telling the model
  "this conversation is a tape held by that process" does NOT produce the
  belief it can touch its own session; being walked to FIND itself does.
  Interpolate the slug ("You are session `:x`; `(repl/snapshot :x)` returns
  this conversation") and self-location collapses to one dispatch —
  A/B-verified. Ships as the `:orientation` config template (design § D7/D4).
- **Orientation fixes location, not reach.** Naming raw primitives
  (`swap!`, `alter-var-root`) in prose did not produce their use; "state
  persists" did not stop re-requiring. Reach needs template-level feedback
  (the chat template's escalating tool-error warnings demonstrably work) or
  task scaffolding (offer a strategy menu; open-ended invention spirals).
- **Two empty-reply modes, now distinguishable:** budget exhaustion
  (`⚡ budget! 8↯` receipt present) vs reasoning-only termination (thinking
  emitted, zero content blocks — observed via a degenerate repetition loop;
  check sampling: `repeat_penalty 1.0` ∧ `dry 0` on the wire). Design fixes:
  strip `:tools` from the post-budget request; loud marker for empty finals.
- **The verbose llama.cpp log reconstructs everything** the tape discards —
  loop-local tool exchanges AND thinking blocks (`D Parsed message` entries,
  full rendered prompts). It is the payload trace until trace-durability
  lands; it gets purged, so mine it promptly.

## Live receipts worth remembering

Σ(p²) over first 20 primes = 30007 computed via tool (the human-side
verifier was the buggy one); the model observed ITSELF mid-turn at depth 1
(persist-user-first, seen from inside); asked "where are you running?", it
EVALED its way to proof — "not a sandbox or simulation".
