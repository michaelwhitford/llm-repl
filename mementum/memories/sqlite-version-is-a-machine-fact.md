---
type: insight
symbol: 💡
title: three SQLite versions will touch one ledger file — pin a feature floor, not a version
related: [knowledge/design/measurement-surface.md, knowledge/container.md, memories/llama-wire-is-ours-request-is-open]
---

`babashka.sqlite` (babashka/ffi-sqlite3) dlopens the **system** SQLite. It
does not vendor one. So the SQLite version is a property of the MACHINE,
never of the repo — and the planned census ledger is read ∧ written from
more than one machine and more than one language.

Measured 2026-08-31, same day, three places:

| where | SQLite |
|---|---|
| macOS host (homebrew) | **3.51.0** |
| the container (Ubuntu 26.04 base ⊕ `libsqlite3-0`) | **3.46.1** |
| verbum's python | its OWN bundled sqlite3 (a third) |

Five minor versions between the two we control, across exactly the
membrane a shared ledger would span. The **file format** has been stable
since 3.0, so this is not a corruption risk; **features** are the risk —
STRICT tables (3.37+), JSON function coverage, generated columns, and
`RETURNING` (3.35+) all landed at different times.

∴ the ledger's contract is a **feature floor**, not a version pin.
Today's effective floor is the lowest writer ≡ **3.46.1** (the container),
and python's bundle can be lower than either. Whatever the schema uses
must be checkable against `sqlite_version()` at open, loud on violation —
the same posture as the config chain's fail-loud, for the same reason: a
silently-missing feature degrades into a wrong answer much later.

Second fact, cheaper but sharper: the base image ships **no** SQLite at
all (no dpkg package, no `.so`). The failure is loud and good —
`babashka.ffi: cannot load library: libsqlite3.so.0` with the search paths
listed — but it lands at the FIRST CALL, i.e. mid-measurement, not at
boot. `libsqlite3-0` is now in the Dockerfile; if the base image ever
changes, that is the line that keeps it working.

Generalized: a native dependency moves version-pinning OUT of the repo and
into the environment. Everything the repo can still pin — the image tag,
the CI installer version, the feature floor — is the only leverage left.
