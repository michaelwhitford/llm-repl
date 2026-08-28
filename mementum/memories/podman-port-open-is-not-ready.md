---
type: insight
symbol: 💡
title: podman's port forwarder accepts before the service inside is listening — port-open ≠ ready
related: [knowledge/container, memories/nrepl-concurrency-is-per-socket]
---

Found live 2026-08-28 building `docker/container.sh`: an `nc -z 127.0.0.1
7899` readiness check passed the instant `podman run` returned — but the
nREPL server inside was still booting, so the very next connection hit a
half-open forward and died mid-bencode (`read-frame` EOF).

The forwarder (gvproxy on podman machine) binds the published port
immediately and accepts connections regardless of whether anything inside
the container is listening yet. TCP connect success proves the WALL is up,
not the service behind it.

Consequence for any script or client that waits on a containerized core:
**readiness ≡ a real eval round-trip**, retried until it answers —
`container.sh` loops `(+ 1 2)` over `net/eval-msg` inside one bb process
(30s bound, loud failure with container logs). The `nc` fallback exists
only for bb-less machines and labels itself the weaker check.

Same shape as the TUI's attach contract: reachable-and-answering is the
only truth; anything less is the silent-fallback failure mode wearing a
green light.
