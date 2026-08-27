---
type: memory
symbol: 🎯
title: podman-desktop 0.0.0.0 publish — accepted deviation on trusted LAN
related: [container, attach-topology]
---

# podman-desktop 0.0.0.0 publish — accepted deviation

The container contract says publish loopback-only (`127.0.0.1:7899:7899`)
because nREPL ≡ unauthenticated eval. When the container is started via
**podman-desktop**, the UI does not expose a way to set the host bind IP,
so `podman ps` shows `0.0.0.0:7899->7899` — LAN-visible.

**Decision (2026-08-27):** accepted on the trusted home LAN until
podman-desktop grows the knob. Do NOT re-flag this as a violation when
orienting over this container; it is known and deliberate.

**The fix when wanted:** recreate from the CLI, where loopback publish is
already live-verified on podman machine:

```
podman run -d --name llm-repl -p 127.0.0.1:7899:7899 \
  -v ~/llm-repl-work:/work localhost/llm-repl:latest
```

The invariant itself is unchanged — `:bind "0.0.0.0"` only behind a wall;
here the wall is the LAN perimeter, temporarily.
