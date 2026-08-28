---
type: Design
title: The wire protocol — what crosses, on how many sockets, and why not push
status: active
related: [design/architecture, tui-design-rules, attach-topology,
          memories/nrepl-concurrency-is-per-socket,
          memories/nrepl-orphan-eval-survives-disconnect,
          memories/tui-dead-daemon-silent]
---

# The wire protocol

> What an attached surface actually sends and receives. D3 (architecture.md)
> ratified the CADENCE — version counter ⊕ long-poll. This page owns the
> PAYLOAD, the socket count, and the protocol road not taken.
> Built 2026-08-28 (`registry/index` ∧ `registry/view`, `client/view` ∧
> `focus!`); measurements below are from that build.

## The payload ≡ a projection, not the registry

```
(registry/view <focus>) → {:index {slug → {:slug :model :preamble?
                                           :depth :turns
                                           :forked-from :forked-at}}
                           :slug  <focus>
                           :tape  [messages…]}    ; the FOCUSED session only
```

Every registry-wide consumer wants edges ∧ counts; only the pane you are
looking at needs bodies (audit ≡ tui-design-rules.md). `registry/index` is
the one definition of that shape — `sessions-list` (the public api command)
is `(vec (vals (index …)))`, so the human surface and the wire can never
drift apart.

**Measured on identical fixtures** (300-char messages, 6-message tapes;
`pr-str` bytes, the same method as the audit):

| n | before (`@sessions*`) | after (`view`) | ratio |
|---|---|---|---|
| 10 | 20.7 KB | 3.1 KB | 6.7× |
| 50 | 103.6 KB | 7.6 KB | 13.7× |
| 300 | 623.8 KB | 36.0 KB | 17.3× |

`pr-str` 19ms → 1ms and `read` 11ms → 2ms at n=300, per refresh.

**Where this saturates, and the next move if it ever matters:** at n=300 the
payload is 34.1 KB of INDEX ⊕ 2.0 KB of tape. The bodies are gone; what is
left is O(sessions) metadata re-sent whole on every change. If a fan-out ever
makes that hurt, the next step is an index DELTA keyed on `version*` (the
counter already exists), not a smaller index. Nobody needs this yet — it is
recorded so the measurement doesn't have to be redone to find the direction.

### The rule that outranks the bytes

> **Split the PAYLOAD, never the round-trip.**

Index and focused tape come from ONE deref inside `registry/view`, and land
in ONE client atom read by ONE deref at render. Two fetches would be two
points in time: the tree rendering depth N beside a tape of N−1 messages — a
torn read that looks like a rendering bug and isn't. Atomicity is free here;
over the wire it would not be.

## Three sockets, and why the count is not negotiable

`babashka.nrepl` runs ONE thread per CONNECTION and serializes that
connection's messages **regardless of session** (measured: a second session
on a busy socket waited 3012 ms; a second SOCKET answered in 313 ms — the JVM
differs, and bb is primary. memories/nrepl-concurrency-is-per-socket).

```
pconn   poll    parks in wait-for-event!, wakes on change
sconn   submit  blocks for a whole completion
fconn   focus   on-demand view fetches (Tab, use!) — must answer while the
                other two are busy, which is the entire reason it exists
```

Under bb, **sockets ARE the multiplex.** A client-side frame demultiplexer
(share one socket, route by id) is a JVM-shaped idea that buys nothing here.

## Focus is the client's, and the race is a pure function

Focus (Tab, `use!`) never becomes registry state — attached clients each look
where they like. The client holds it as the parameter of the next fetch, and
two fetchers (poll thread ∧ `focus!`) can land answers in either order, so
`client/apply-view` — pure, table-tested — decides:

```
index  ALWAYS applied                              (focus-independent)
tape   applied iff fetched FOR the current focus
       else keep ours if ours is for the current focus
       else nil ≡ NOT-A-TAPE
```

`nil` tape ≡ not fetched yet → the pane renders a loading placeholder.
`[]` ≡ an open session with no turns → the welcome banner. Collapsing the two
would either flash the banner at a session with history or, worse, paint the
session you just left under the new title.

## A stale core says so

There is no fallback for a payload (unlike `wait-for-event!`, whose absence
degrades to version-polling), so `client/remote` asks ONCE at attach whether
the core resolves `registry/view` and fails loud with the next move. A core
outliving its client is normal — persistence is the point — so this is a
contract, not an edge case. Found by attaching a new client to the live
container; the pre-fix reason string was `class clojure.lang.ExceptionInfo`,
because `fetch` preferred nREPL's `:ex` (exception CLASS) over `:err` (the
message). `:err` first now.

## Push (server-streams-to-client): evaluated, declined

Probed 2026-08-28 before building, on both runtimes. Four facts:

1. **`out` frames DO stream incrementally** during a long eval (1/503/1005ms)
   — a printing eval is a usable push channel. Push is FEASIBLE.
2. **bb serializes per connection** — so a push stream parks its socket
   forever and still needs submit ⊕ control sockets. Push does not reduce the
   socket count, which was its main attraction.
3. **A parked long-poll already wakes on the first event** — latency is
   already ~0. Push saves 2 tiny evals per change, nothing more.
4. **An orphaned eval loop OUTLIVES its client** on both runtimes (ticks kept
   incrementing after the socket closed; the Broken pipe never reaches the
   evaluating thread) — a push stream leaks a thread per attach until an
   explicit heartbeat/TTL exists. `wait-for-event!`'s bounded 25s park
   self-heals by construction.

Verdict: **the long-poll IS push, with a self-terminating bound.** If push is
ever revisited, fact 4 names the first thing to build (liveness), and the
projection above is a prerequisite — streaming whole tapes on every change is
the v0.2.0 sin at a higher frequency.
