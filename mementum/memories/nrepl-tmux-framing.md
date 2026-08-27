---
type: memory
symbol: 💡
title: nREPL tmux framing — sessions ≡ windows whose history is a forkable value
related: [nrepl-extension-assessment, design/architecture]
---

# the tmux framing

llm-repl ≡ "a clojure repl version of tmux with nicer semantics" (human's
words, 2026-08-27 — ratified as the wire posture; MCP facade skipped).

The mapping: daemon ≡ server · session ≡ window · attach/detach ≡ nREPL
connect ∧ TUI attach · send-keys ≡ eval!/bounce! · capture-pane ≡ snapshot ·
status line ≡ events (D3). The NICER part: a tmux window's scrollback is
mutable text; a tape is an immutable VALUE — so `fork! {:at N}` and `ab!`
fork the PAST, which tmux cannot do at all, and `bounce!` probes without
touching history.

The load-bearing consequence: for a Clojure-aimed tool, **eval IS the op
protocol** — forms are the messages, `ns-publics ≡ the op table`, D3
long-poll is the streaming. No custom nREPL ops, no MCP: an entire agent
session (this one) drove every experiment over plain eval. Non-Clojure
agents get a recipe page, not a component.

Use this framing for the README when the refactor stabilizes — it explains
the instrument to a Clojure audience in one sentence.
