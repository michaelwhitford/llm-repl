# llm-repl

A REPL where the *tape* — the `messages[]` array of a chat completion — is the
value. A chat completion is a pure function of `messages[]`; the tape is an
immutable reduction accumulator, so **fork is free** and the "conversation" is
one path through a tree of continuations.

The repl isn't a tool a model uses — it's a *place* where tapes live. Humans
(TUI), models, and editors are all just nREPL clients on equal footing.

Gen-1 was a Python repl that attached *to* a parent model. This inverts it:
anima built this substrate against local llama.cpp servers, and now clients
attach to *it*.

## Run

```sh
bb llm-repl        # terminal prompt loop + nREPL attach port (.nrepl-port)
bb nrepl           # headless: nREPL server only
```

Runs under **babashka** (primary) or JVM Clojure. Requires
[escapement](../escapement) as a sibling checkout (`:local/root`).

## Configure

Copy `config.example.edn` to `~/.config/llm-repl/config.edn` (or `./config.edn`)
and point the model roster at your llama.cpp servers. Resolution: defaults <
XDG < repo-local < `LLM_REPL_CONFIG` env var.

## Drive

Every surface projects the same command namespace (`ns-publics` is the
contract — TUI palette, nREPL, and a future MCP facade all enumerate it):

```clojure
(open! :my-session)            ; get-or-create a tape
(eval! :my-session "hello")    ; commit ONE turn (tape advances)
(fork! :my-session :variant)   ; copy tape+config — counterfactuals are cheap
(fork! :my-session :redo {:at 2}) ; branch an OLDER turn (first 2 messages)
(bounce! :my-session "probe")  ; one completion off the FIXED tape (unchanged)
(trampoline! :my-session [...]); fan varied inputs off the fixed point
(run-battery! :my-session [..]); fold a probe sequence (tape advances)
(sessions-list)                ; the registry index
(snapshot :my-session)         ; the full session map
```

## Lineage

Extracted from [anima](../anima) (`us.whitford.anima.llm-repl`), which ported
`chat-memory` and the llama.cpp backend from ouroboros. Function names are kept
verbatim across repos so the lineage greps as one.

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

## Licensing

Project license: TBD. The tool contains no third-party prompt text; whatever
preamble a machine boots lives in that machine's config file.
