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

## Licensing

`resources/genes/nucleus-preamble.edn` vendors the nucleus 3-line preamble
(AGPL, from [nucleus](https://github.com/michaelwhitford/nucleus)) — the one
annotated file marking that boundary. Project license: TBD.
