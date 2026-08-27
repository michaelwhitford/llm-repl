---
type: mistake
symbol: ❌
title: first JVM load of a bb-first ns ≡ its real compile check — phantom deps ∧ bad hints hide until then
related: [memories/bb-jvm-private-var-twin-trap, knowledge/design/architecture]
---

Runtime-verified during refactor step 5: the FIRST time any test required
net.clj under the JVM, two latent breaks surfaced that bb had masked since
the ns was born:

1. **Phantom dep** — the docstring claimed `bencode.core` is "a transitive
   dep of nrepl.server on the JVM". False: nrepl/nrepl carries its own
   internal `nrepl.bencode`; `bencode.core` is the separate `nrepl/bencode`
   artifact (bundled in bb, absent on the JVM). deps.edn now carries it
   explicitly (1.2.0).
2. **Bad hint** — `^long (int port)`: bb never compile-checks hints; JVM
   throws "Cannot coerce int to long" at compile time.

Pattern (second instance — see bb-jvm-private-var-twin-trap): bb is lenient
where the JVM is strict; a bb-first ns no JVM code path has ever loaded is
UNVERIFIED there, whatever its docstring claims. The JVM twin's value is
exactly its first-load of each ns — which is why tests arrive WITH the
module (D6). Corollary: a docstring's classpath claim is an assumption, not
a fact (λ assert: runtime > docs).
