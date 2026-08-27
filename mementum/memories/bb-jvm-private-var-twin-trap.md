---
type: Memory
symbol: ❌
title: defn- + direct cross-ns call ≡ bb/JVM twin trap — bb permits, JVM throws
related: [design/architecture, memories/swap-vals-race-detection]
---

# bb-jvm-private-var-twin-trap

Runtime-verified during refactor step 3 (completion tests): calling a
private var directly from another ns — `(ns.a/private-fn …)` — RESOLVES AND
RUNS under bb but throws `"var: … is not public"` at compile time under JVM
Clojure. The suite passes its bb leg and fails its JVM twin — invisible
until `clojure -M:run-tests` runs (D6 is exactly why the twin exists).

Safe across both runtimes: `with-redefs [ns.a/private-fn …]` — the binding
form expands to `(var ns.a/private-fn)`, which bypasses the privacy check
on both. So the rule:

- test needs to REDEF a helper (stub a seam) → `defn-` is fine
- test needs to CALL a helper directly → it must be public `defn`
  (publicity for test access ≠ API promotion — library-contract's INTERNAL
  section still governs; note it in the docstring)

Precedent: completion/session-backend stayed `defn-` (redef-only seam);
session-tools/tool-wire/with-tools-system went public (called by tests).
