#!/usr/bin/env bash
# docker/container.sh — build the llm-repl image and (re)spin the container.
#
# The ONE repeatable path from source → running core, per the pinned spec
# (mementum/knowledge/container.md): fixed port 7899, loopback-only publish
# (nREPL ≡ unauthenticated eval — never beyond loopback), ~/llm-repl-work
# mounted at /work (THE one hole in the wall; .nrepl-port, config.edn and
# the .llm-repl/ flight recorder all cross here, so traces ∧ tapes survive
# the container by construction).
#
# Engine-neutral: ENGINE=docker ./docker/container.sh runs the identical spec
# (the Dockerfile is plain OCI). Overridables: ENGINE IMAGE NAME PORT WORK.
#
# Self-verifying: readiness ≡ a real eval round-trip over the wire (when bb
# is on PATH; bare port check otherwise) — "up" means ANSWERING, not just
# running. Port-open is NOT ready: podman's forwarder accepts immediately.
set -euo pipefail

ENGINE="${ENGINE:-podman}"
IMAGE="${IMAGE:-llm-repl}"
NAME="${NAME:-llm-repl}"
PORT="${PORT:-7899}"
WORK="${WORK:-$HOME/llm-repl-work}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
HEAD_REV="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"

echo "==> building $IMAGE from $REPO_ROOT (HEAD $HEAD_REV)"
"$ENGINE" build -t "$IMAGE" -f "$SCRIPT_DIR/Dockerfile" "$REPO_ROOT"

mkdir -p "$WORK"

if "$ENGINE" container exists "$NAME" 2>/dev/null; then
  echo "==> replacing container $NAME"
  "$ENGINE" rm -f "$NAME" >/dev/null
fi

echo "==> running $NAME (127.0.0.1:$PORT:$PORT, $WORK -> /work)"
"$ENGINE" run -d --name "$NAME" -p "127.0.0.1:$PORT:$PORT" -v "$WORK:/work" "$IMAGE" >/dev/null

# Readiness ≡ ANSWERING, not port-open: podman's forwarder accepts the
# instant the container publishes — long before the nREPL server inside is
# up — so a bare port check is a lie. The eval round-trip IS the gate.
if command -v bb >/dev/null 2>&1; then
  echo "==> waiting for a real eval round-trip on 127.0.0.1:$PORT"
  (cd "$REPO_ROOT" && bb -e '
    (require (quote [us.whitford.llm-repl.net :as net]))
    (loop [n 30]
      (let [v (try
                (let [conn (net/connect "127.0.0.1" '"$PORT"')
                      r    (net/eval-msg conn "(+ 1 2)")]
                  (net/close conn)
                  (when (net/ok? r) (net/value r)))
                (catch Throwable _ nil))]
        (cond
          (= "3" v)  (println "✓ core answering: (+ 1 2) =>" v)
          (zero? n)  (do (println "✗ no eval round-trip after 30s")
                         (System/exit 1))
          :else      (do (Thread/sleep 1000) (recur (dec n))))))')
else
  printf "==> bb not on PATH — falling back to port check (port-open ≠ ready!) "
  up=""
  for _ in $(seq 1 30); do
    if nc -z 127.0.0.1 "$PORT" 2>/dev/null; then up=1; break; fi
    printf "."
    sleep 1
  done
  echo
  if [ -z "$up" ]; then
    echo "✗ port not reachable after 30s — recent logs:" >&2
    "$ENGINE" logs --tail 20 "$NAME" >&2
    exit 1
  fi
fi

"$ENGINE" ps --filter "name=$NAME" --format '{{.Names}} {{.Image}} {{.Status}}'

echo "==> done: $NAME @ 127.0.0.1:$PORT, image $IMAGE (HEAD $HEAD_REV)"
