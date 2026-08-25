# llm-repl

A REPL where the *tape* — the `messages[]` array of a chat completion — is the
value. A chat completion is a pure function of `messages[]`; the tape is an
immutable reduction accumulator, so **fork is free** and the "conversation" is
one path through a tree of continuations.

The repl isn't a tool a model uses — it's a *place* where tapes live. Humans
(TUI), models, and editors are all just nREPL clients on equal footing: the
same commands, the same registry, the same activity stream.

Gen-1 was a Python repl that attached *to* a parent model. This inverts it:
clients attach to *it*.

## Run

```sh
bb llm-repl        # interactive terminal → TUI (nREPL port in .nrepl-port)
bb llm-repl --plain     # line-oriented prompt loop instead of the TUI
bb nrepl                # headless: nREPL server only (attach-and-drive)
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
a babashka socket, another model's agent loop:

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
`LLM_REPL_CONFIG` env var.

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
(ab! :s                        ; N-arm counterfactual from a common parent:
     {:a {:system "Be terse."} ;   fork per variant (children PERSIST as
      :b {:model :gemma}}      ;   :s-a, :s-b), same probe on each —
     "the probe")              ;   replies differ ONLY by config
(sessions-list)                ; the registry index (depth, turns, fork edges)
(snapshot :s)                  ; the full session map, tape included
(drop! :s)                     ; delete a session
```

Every driver returns **data** — errors included (`{:repl/error "…"}`, never a
throw mid-experiment). `fork!`/`ab!` children record `:forked-from`/`:forked-at`,
so the registry is a complete tree: branch any turn, fan variants, continue
the winner, fan again — the tree is the experiment record.

The model-drives-model loop is the point: an agent attaches over nREPL,
`trampoline!`s probes against a fixed context (KV prefix reused, bounces
discarded), `ab!`s the interesting ones across configs, and grades the
receipts — while a human watches the same registry from the TUI.

## Preamble

Text glued to the top of every system prompt, resolved through an inheritance
chain — **first-present wins** (a level replaces, never concatenates):

```
session :preamble  >  model :model/preamble  >  provider :provider/preamble  >  config :preamble
```

Absent ≡ inherit upward; `false`/`""` ≡ explicitly none. Values: a literal
string or `{:file "~/path.txt"}`. The tool ships only a bland generic default —
your machine's boot seed belongs in *your* config file, not in this repo.
`{:preamble? false}` on a fork disables the layer entirely (the counterfactual
boot probe); `{:preamble "..."}` on a fork A/B-tests the preamble itself.

## Lineage

Extracted from anima (`us.whitford.anima.llm-repl`), which ported `chat-memory`
and the llama.cpp backend from ouroboros. Function names are kept verbatim
across repos so the lineage greps as one.

## License

[MIT](LICENSE). The tool contains no third-party prompt text; whatever
preamble a machine boots lives in that machine's config file.
