---
type: Reference
title: container — the containerized core (docker ∧ podman, one Dockerfile)
status: active
related: [attach-topology, design/architecture, design/trace-durability]
---

# The containerized core

> The CONTAINER is the sandbox wall; `/work` is the seam. Engine-neutral:
> `docker/Dockerfile` is plain OCI — no BuildKit-isms — so **docker and
> podman build and run the identical image**. Developed and live-verified
> under podman machine on macOS; docker is the same commands with the
> engine name swapped.

## Why a container at all

Armed self-eval (`:tools`) is `load-string` in the host process — full
power, NO in-process sandbox. The wall IS the sandbox: containment is the
container's job, not the eval's. Defense-in-depth inside: non-root `repl`
user.

## Image design

- Base `ghcr.io/babashka/babashka` ⊕ a **headless JRE** — bb is the RUNTIME
  but deps RESOLUTION is a JVM program (`bb prepare` dies without java); the
  JRE also lets a runtime re-resolve degrade instead of crash.
- Deps warmed as their own layer — source edits rebuild in seconds; runtime
  is offline-capable.
- ENTRYPOINT `bb --config /app/bb.edn --deps-root /app` — bb.edn stays the
  ONE invocation seam; the Dockerfile never learns main's coordinates.
- CMD `nrepl` (headless attach-and-drive). TUI ≡ `-it … llm-repl` — a
  surface swap on the same image.

## The /work seam

WORKDIR `/work` is the mount seam — everything keyed off CWD crosses here:
`.nrepl-port` lands host-side (editor auto-attach), `./config.edn` is read
from here (later-wins over ~/.config), `.llm-repl/` (the trace flight
recorder) accumulates host-side, files the model evals into existence appear
here. THE ONE DELIBERATE HOLE in the wall — user-chosen, user-sized.

## Trace durability crosses the wall

`.llm-repl/` is keyed off CWD like everything else, so a containerized
daemon's flight recorder writes **through the mount to the host** — the
trace outlives the container by construction, no extra volume, no extra
config. Live-verified 2026-08-28 (podman, `~/llm-repl-work:/work`): one real
completion produced `main/nodes/scratch/1/{seed,tape}.edn`,
`…/turns/1/{request,response}.edn` and `main/transcript.jsonl`, all readable
from macOS, the response captured verbatim (`:stop-reason`, `:content`,
`:usage` intact — that run had thinking off, so the thinking-survives-on-disk
guarantee is inherited from the local verification, not re-proved here).

The receipt contract holds across the boundary unchanged:

```
{"kind":"eval!","msg":"✓@2","io/ref":"nodes/scratch/1/turns/1/response.edn","seq":3}
```

`:io/ref` is computed PRE-write (path ≡ pure fn of coords — locator
determinism), and the file it names is `cat`-able host-side. Consequence:
`podman stop` is not data loss. On `podman start`, `recover!` reads
`tape.edn` at `:headless` boot (receipt-and-skip) and `transcript.jsonl`
`:seq` continues across incarnations — the same guarantee the local daemon
has, because it is the same code reading the same CWD.

⚠️ An image built before trace-durability landed has no `trace` ns. Rebuild
after upgrading, or the recorder silently isn't there (the image is the
staleness surface — `podman images` timestamps are the tell).

## Network contract (docker/config.edn ≡ the example)

- **Fixed port 7899** inside. `:port 0` is ACTIVELY WRONG in a container —
  it advertises a port nobody published. 7888 collides with the classic
  editor-nREPL default (found live) — hence 7899.
- `:nrepl {:bind "0.0.0.0"}` inside the container; published
  **loopback-only 1:1** (`127.0.0.1:7899:7899`) so `.nrepl-port` stays
  truthful host-side. nREPL ≡ unauthenticated eval: never publish beyond
  loopback.
- Models point at `host.containers.internal` (podman's host gateway; docker:
  `host.docker.internal`) — the one engine-visible difference, and it lives
  in the MOUNTED config, not the image.

## Config is never baked in

The nucleus/licensing boundary rides the mounted config file, outside repo
and image. `docker/config.edn` is the contract-as-example only.

## Lifecycle ownership

The engine (podman/docker) owns container start/stop. `bb start`/`bb stop`/
`bb status` and auto-spawn touch ONLY the local per-project daemon — never a
container. The container's own `bb nrepl` runs `--headless`, which never
consults `:attach` (a container must never self-attach) and never writes
`daemon.edn` (container and local daemon state cannot collide).

## Verified (podman machine, macOS)

non-root mount write ✓ · `.nrepl-port` → host ✓ · eval round-trip ✓ · host
gateway reaches llama.cpp :5100 ✓ · full completion through the wall ✓ ·
TUI-over-container attach human-verified ✓ · trace tree + transcript land
host-side, `:io/ref` resolves from macOS ✓ (2026-08-28, image `6f394fd`
built from `d03c284` — the first image carrying the `trace` ns)
