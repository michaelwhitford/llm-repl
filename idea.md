# llm-repl

should run under babashka or clojure jvm

nrepl
fulcro escapement
fulcro statecharts
~/src/anima llm-repl tool

## UI

- tui (escapement)

The llm-repl tool uses the messages array for a chat-completion as a continuation that can branch and bounce on any turn.

Allow starting an llm-repl as easy as starting a clojure repl, nrepl allows any clojure app to attach and drive a session. nrepl should be trivial to implement in any language that does not already have a lib.

MCP?  I am not a fan of MCP but it does give an easy interop win for existing tools that can be MCP clients.

config file to specify providers/api details? env vars?

tui is the first interface, `bb llm-repl` task can start the repl and output to the terminal

sessions?  need to find an app that does these well to model.

repl commands?  A set of functions that can be called with normal parens behavior?  Can the ui extract only the few commands from the context?
