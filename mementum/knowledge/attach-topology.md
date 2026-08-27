---
type: Reference
title: attach topology — daemon, TUI-as-client, the attach contract
status: active
related: [container, design/architecture]
---

# Attach topology

> The core is a PERSISTENT separate process; every surface — humans (TUI),
> editors, models, agents — attaches over nREPL. There is no in-process
> TUI+core path (`--plain` is the one deliberate in-process debug loop).

## Per-project daemon (local default)

Keyed by CWD like a normal Clojure repl. Files:

```
<project>/.nrepl-port           port only (standard; editors read it too)
<project>/.llm-repl/daemon.edn  {:pid :port :cwd :started-at}  (spawner-owned)
<project>/.llm-repl/daemon.log  detached daemon stdout/stderr
```

- `discover` cleans stale state (pid gone ∨ port dead → both must hold:
  `alive? ≡ pid-alive? ∧ reachable?`) → callers spawn fresh.
- `ensure!` → `[state fresh?]`; `stop!` SIGTERMs the recorded pid — never a
  container. Quit of the TUI ≡ DETACH; the daemon keeps running; `bb stop`
  ends it; reattach finds tapes intact.

## The verified macOS detach incantation

No setsid on macOS. Under `/bin/sh`:

```
nohup bb --config <cfg> --deps-root <cfg-parent> nrepl > daemon.log 2>&1 & echo $!
```

nohup ⇒ ignore SIGHUP; `&` ⇒ background; sh exits ⇒ grandchild reparents to
launchd (PPID 1); sh's stdout ≡ the pid. VERIFIED: survives spawner exit AND
terminal SIGHUP; SIGTERM stops it. The spawner captures `$!`, polls
`.nrepl-port` for the OS-assigned port, then writes daemon.edn.

## The spawn convention

`spawn!` reinvokes the SAME bb.edn via `System/getProperty "babashka.config"`
(the --config path), deps-root ≡ its parent dir — that is how it finds bb.edn
from a foreign CWD. No babashka deps-root property exists; parent-of-config
is the convention. **JVM runtime has no such property** — v0.3.0: fail loud
with instructions (see design D6); v0.2.0 NPEs.

## The attach contract (fail loud)

An EXPLICIT attach request — `--attach` flag or `:attach` config — is a
CONTRACT: unreachable ⇒ fail loud ∧ exit. NEVER silently fall back to local:
a fresh empty session would mask a down container as lost state — the worst
failure mode. Local is only ever the DEFAULT, chosen when no attach is
requested at all.

The contract extends MID-SESSION (v0.3.0 refactor step 5): the client's
poll loop carries wire failures as DATA (`fetch → {:ok v} | {:err reason}` —
never a nil that reads as "no change"); at 3 consecutive failures the
client's `status` deref-able flips `{:attach :lost}`, the notify callback
wakes the wire layer, and the TUI tears down → prints the reason → exits 1.
A dead core never renders as a live one
(memories/tui-dead-daemon-silent — the live-found bug that motivated this).
Live-verified: `bb stop` under an attached RemoteCore → `:lost "Broken
pipe"` within ~2.5s.

`:attach` shapes: `"host:port"` | `"port"` | `{:host :port}` | `true`
(≡ read ./.nrepl-port) | false/absent (≡ local). A project's ./config.edn
`{:attach false}` opts OUT of a global container attach (config chain:
later wins). Resolution split by layer: `roster/attach-spec` (config→spec)
∘ `daemon/attach-target` (spec→[host port]).

## Task ∧ flag semantics

- `bb start`/`stop`/`status` — ONLY the local per-project daemon. `status`
  also shows the `:attach` remote + REACHABLE/UNREACHABLE (what
  `bb llm-repl` would attach to); config pulled lazily so daemon stays a
  low-level ns (~0.02s).
- `--headless` ≡ the daemon body (also `bb nrepl` ∧ the container): start
  nREPL, open scratch, park. NEVER consults `:attach`.
- `--plain` ≡ in-process debug loop, the one no-wire escape hatch.

## Cross-machine knobs

`:nrepl {:bind}` (default 127.0.0.1) threads through BOTH runtime branches;
banner reports the ACTUAL bind. `"0.0.0.0"` only ever behind a wall.
`:model/host` (default localhost) builds roster's base-url — a containerized
repl names the host gateway, a LAN llama.cpp box its hostname.
