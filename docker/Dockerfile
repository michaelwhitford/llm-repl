# llm-repl container — headless nREPL service by default; TUI via `-it`.
# Plain OCI (no BuildKit-isms): builds identically under podman and docker.
#
# Build (context = repo root):
#   podman build -t llm-repl -f docker/Dockerfile .
#
# Run headless (publish LOOPBACK-ONLY — nREPL is unauthenticated eval):
#   podman run -d --name llm-repl -p 127.0.0.1:7899:7899 \
#     -v ~/llm-repl-work:/work \
#     -v ~/.config/llm-repl:/home/repl/.config/llm-repl:ro \
#     llm-repl
#
# The ~/.config mount is OPTIONAL — a ./config.edn in the work dir is
# self-sufficient. Mount it to inherit the host config (preamble etc.);
# omit it to keep the licensing boundary outside the container entirely.
#
# TUI instead:   podman run -it ... llm-repl       (CMD swap, same image)
# Host TUI onto a RUNNING container:  bb llm-repl --attach 7899
# Lifecycle is the ENGINE's: podman start/stop/logs — never `bb stop`.
#
# Topology:
#   /app    baked source + bb.edn; deps warmed at build → offline at runtime.
#   /work   WORKDIR ≡ the mount seam. Everything that keys off CWD crosses
#           the boundary here: .nrepl-port lands host-side (editor
#           auto-attach), ./config.edn is read from here (later-wins over
#           ~/.config), .llm-repl/ (the trace flight recorder: requests,
#           responses, tape.edn, transcript.jsonl) accumulates host-side and
#           SURVIVES the container, files the model evals into existence
#           appear here. The ONE deliberate hole in the sandbox wall —
#           user-chosen, user-sized.
#   Config is NEVER baked into the image (the nucleus/licensing boundary
#   lives in the mounted config, outside the repo and outside the image).

# PINNED, deliberately. `:latest` made the instrument's runtime a function of
# WHEN you last rebuilt — it silently adopted 1.13.220 (and its new
# babashka.ffi) the moment the tag moved. The repo is authoritative, the
# machine is irrelevant — same rule as .clj-kondo/config.edn pinning its
# levels. Bump this line on purpose, after the release has settled.
FROM ghcr.io/babashka/babashka:1.13.220

# bb is the RUNTIME, but dependency RESOLUTION (deps.clj → tools.deps) is a
# JVM program — maven deps (escapement) cannot warm without `java`. Keep a
# headless JRE in the image: the warm step needs it at build, and a future
# config-driven re-resolve at runtime degrades gracefully instead of
# crashing. (~200MB — the resilient trade over a fragile multi-stage
# cache-copy whose mtime staleness checks could demand java anyway.)
#
# libsqlite3-0: babashka.ffi's SQLite wrapper (babashka/ffi-sqlite3) dlopens
# the SYSTEM library — and this base image ships none (verified 2026-08-31:
# no dpkg package, no libsqlite3.so anywhere on the aarch64 image). A desktop
# or a GitHub runner has one because half of Ubuntu depends on it; a minimal
# image does not. Without this line the failure is a runtime load-library
# throw on the first measurement write, far from its cause.
RUN apt-get update \
    && apt-get install -y --no-install-recommends default-jre-headless libsqlite3-0 \
    && rm -rf /var/lib/apt/lists/*

# Unprivileged user. Armed sessions eval with full power (tools.clj:
# load-string, NO sandbox) — the container wall is the sandbox; running the
# eval as non-root is defense-in-depth inside it.
RUN useradd --create-home --shell /bin/bash repl \
    && mkdir -p /app /work \
    && chown -R repl:repl /app /work

USER repl

# Deps layer: bb.edn alone, then warm — the escapement/maven cache bakes into
# this layer, so source edits rebuild in seconds and runtime needs no network
# beyond the model endpoints.
COPY --chown=repl:repl bb.edn /app/bb.edn
RUN bb --config /app/bb.edn --deps-root /app prepare

# Source layer.
COPY --chown=repl:repl src/main /app/src/main
COPY --chown=repl:repl resources /app/resources

WORKDIR /work

# The container contract (see docker/config.edn): :nrepl {:port 7899
# :bind "0.0.0.0"} in the mounted config, published 1:1 (7899:7899) so the
# .nrepl-port file stays truthful from the host's side. :port 0 is actively
# wrong in a container — it would advertise a port nobody published.
EXPOSE 7899

# Sensible default for the TUI under `-it`; harmless headless.
ENV TERM=xterm-256color

# bb.edn stays the single invocation seam — the Dockerfile never learns
# main's coordinates. CMD selects the surface: `nrepl` task ≡ headless
# attach-and-drive (default); override with `llm-repl` for the TUI.
ENTRYPOINT ["bb", "--config", "/app/bb.edn", "--deps-root", "/app"]
CMD ["nrepl"]
