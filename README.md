# llm-repl

A REPL where the *tape* — the `messages[]` array of a chat completion — is the
value. A chat completion is a pure function of `messages[]`; the tape is an
immutable reduction accumulator, so **fork is free** and the "conversation" is
one path through a tree of continuations.

The repl isn't a tool a model uses — it's a *place* where tapes live. Humans
(TUI), models, and editors are all just nREPL clients on equal footing: the
same commands, the same registry, the same activity stream.

Gen-1 was a Python repl that attached *to* a parent model. This inverts it:
clients attach to *it* — including, if you arm it, the hosted model itself:
with `:tools` on, the model gets a `clojure_eval` tool that evaluates in the
very process running its conversation. The model becomes a client of its own
repl (see **Self-eval** below).

## Run

```sh
bb llm-repl             # interactive terminal → TUI (nREPL port in .nrepl-port)
bb llm-repl --plain     # line-oriented prompt loop instead of the TUI
bb nrepl                # headless: nREPL server only (attach-and-drive)

clojure -M:llm-repl     # the same, on JVM Clojure (same flags pass through)
```

Runs under **babashka** (primary) or JVM Clojure. Depends on
[escapement](https://clojars.org/com.fulcrologic/escapement) from Clojars —
clone and `bb llm-repl`, nothing else to check out.

The nREPL server starts *first* in every mode — you can attach even while a
completion is in flight.

## The TUI

Two panes at ≥70 columns:

```
┌ tree ─────────┐┌ llm-repl · :scratch · qwen ─────────────┐
│ :scratch      ││ you: hello                              │
│  ↰:scratch@2  ││ qwen: Hello to the human watching…      │
│   :redo       ││                                         │
│ ····· ········││                                         │
│ tramp! :s 3✓  ││                                         │
│ fork! :s→:redo││                                         │
└───────────────┘└─────────────────────────────────────────┘
> _
```

**Left** — the fork forest (every session, its branch point, the current one
highlighted) with a live receipt footer: the last five things that happened,
*whoever* did them — your keystrokes and an attached agent's `trampoline!`
tick the same stream. **Right** — the current session's tape, conversation
only.

| key | |
|---|---|
| `Enter` | chat: send the buffer as a turn on the current session |
| `(form)` | starts with `(` → evaluated as Clojure (worker thread, UI stays live) |
| `Tab` | walk to the next session in tree order |
| `?` (empty buffer) or `(help)` | command manual, popped over the tape pane |
| `Esc` | dismiss overlay, else clear the input |
| `↑`/`↓` | input history; line-scroll when an overlay is up |
| `PgUp`/`PgDn` | page the tape or overlay |
| `Ctrl-C`/`Ctrl-D` | quit (terminal restored) |

Multi-line paste lands as **one** submission (bracketed paste).

## Attach

The launcher writes `.nrepl-port`. From any nREPL client — CIDER, Conjure,
a babashka socket, another model's agent loop. Both runtimes serve the
editor ops (completion, lookup/eldoc, load-file — verified over the wire);
under bb the cider-style ops are native, on the JVM add cider-nrepl to your
own alias if you want the full middleware experience:

```clojure
(require '[us.whitford.llm-repl.core :as repl])
(println (repl/help))   ; the human manual
(repl/manual)           ; the same manual as data — {:name :arglists :summary :doc}
```

Everything you do appears live in any running TUI: tape turns via the
registry watch, everything else via the receipt stream (`events*`) —
in-flight markers included (`eval! :scratch …`).

## Configure

Copy `config.example.edn` to `~/.config/llm-repl/config.edn` (or `./config.edn`)
and point the model roster at your llama.cpp servers (an OpenAI-subscription
provider is also supported). Resolution: defaults < XDG < repo-local <
`LLM_REPL_CONFIG` env var. Add `:tools true` at the top level to boot every
session with the self-eval tool (see **Self-eval**).

## Drive

The command namespace is the contract — `(manual)` compiles it from
`ns-publics`, and every surface (TUI overlay, `(help)`, a future MCP facade)
renders that one compile:

```clojure
(open! :s)                     ; get or create a session (opts: model/system/…)
(eval! :s "hello")             ; chat: ONE turn, tape advances
(bounce! :s "probe")           ; ONE input, tape UNCHANGED
(trampoline! :s ["a" "b" "c"]) ; MANY inputs off the fixed tape; nothing saved
(run-battery! :s [...])        ; fold a probe sequence, tape advances
(fork! :s :variant)            ; copy tape+config — counterfactuals are cheap
(fork! :s :redo {:at 2})       ; branch an OLDER turn (first 2 messages)
(ab! :s                          ; N-arm counterfactual from a common parent:
     {:a {::system "Be terse."}  ;   fork per variant (children PERSIST as
      :b {::model :gemma}}       ;   :s-a, :s-b), same probe on each —
     "the probe")                ;   replies differ ONLY by config
(sessions-list)                ; the registry index (depth, turns, fork edges)
(snapshot :s)                  ; the full session map, tape included
(drop! :s)                     ; delete a session
```

Session config keys are **namespace-qualified** (`:us.whitford.llm-repl/model`
etc.) so they can never collide with your own keys once a session map lands in
your code. At the repl prompt you're *in* that namespace — `::model ::tools
::preamble?` — and from your own code `(require '[us.whitford.llm-repl :as
repl])` gives you `::repl/model`.

Every driver returns **data** — errors included (`{:repl/error "…"}`, never a
throw mid-experiment). `fork!`/`ab!` children record `:forked-from`/`:forked-at`,
so the registry is a complete tree: branch any turn, fan variants, continue
the winner, fan again — the tree is the experiment record.

The model-drives-model loop is the point: an agent attaches over nREPL,
`trampoline!`s probes against a fixed context (KV prefix reused, bounces
discarded), `ab!`s the interesting ones across configs, and grades the
receipts — while a human watches the same registry from the TUI.

## Self-eval — the model as its own client

Arm a session with `::tools` and the model gets **`clojure_eval`**: it
evaluates Clojure *in the process hosting its own conversation*. This is not
a sandbox bolted on the side — the loop assembling its prompt, the tape that
is its memory, and the runtime it evals in are one live image. Armed
sessions are also told, in their system prompt, where they live. The model
can compute instead of guessing, inspect its own tape mid-turn, list itself
in the registry, `fork!` its own history and `bounce!` probes off it:

```clojure
(open! :s {::tools true})            ; arm one session (true ≡ every registered tool)
(open! :s {::tools [:clojure/eval]}) ; …or an explicit whitelist
(open! :bare {::tools nil})          ; disarm — or run the counterfactual directly:
(ab! :s {:bare {::tools nil} :armed {::tools true}} "the probe")
```

Make it a machine fact with `:tools true` at the top level of your config
file — every fresh session boots armed, restarts included.

Mechanics worth knowing:

- **The tape stays clean.** The tool exchange is loop-local; the tape only
  records your turn and the final reply (shape stable → the KV prefix cache
  holds; forks and compaction are untouched). The receipt stream is the
  trace.
- **You watch it work.** Every eval ticks the TUI footer as
  `⚡ :s (code…)` — the model's moves appear live, same stream as everyone
  else's (equal clients, equal chrome).
- **Bounded.** 8 tool round-trips per turn, then a teaching refusal and one
  final inference. A model-initiated `eval!`/`bounce!` completes *plain*
  (depth guard): self-reference at depth 1 is the feature, unbounded nesting
  is not.
- **Errors are data.** Eval errors, timeouts, and truncations return as
  text the model reads and corrects — never a throw.
- **Open slot.** The registry rides `escapement.tools.protocol`; a host
  `register!`s more tools and sessions whitelist them by keyword. Full
  runtime, no sandbox — the model gets exactly what any attached nREPL
  client gets, receipts watching.

## Preamble

Text glued to the top of every system prompt, resolved through an inheritance
chain — **first-present wins** (a level replaces, never concatenates):

```
session ::preamble  >  model :model/preamble  >  provider :provider/preamble  >  config :preamble
```

Absent ≡ inherit upward; `false`/`""` ≡ explicitly none. Values: a literal
string or `{:file "~/path.txt"}`. The tool ships only a bland generic default —
your machine's boot seed belongs in *your* config file, not in this repo.
`{::preamble? false}` on a fork disables the layer entirely (the counterfactual
boot probe); `{::preamble "..."}` on a fork A/B-tests the preamble itself.

## Lineage

Extracted from anima (`us.whitford.anima.llm-repl`), which ported `chat-memory`
and the llama.cpp backend from ouroboros. Function names are kept verbatim
across repos so the lineage greps as one.

## License

[MIT](LICENSE). The tool contains no third-party prompt text; whatever
preamble a machine boots lives in that machine's config file.
