---
type: pattern
symbol: 🔁
title: Library namespaces must be inert at require — ambient reads belong to standalone entrypoints
related: [knowledge/design/library-contract, memories/config-stickiness, memories/probe-hygiene-tools-armed]
---

The law (holds regardless of any one ns's current state): a ns consumed as a
LIBRARY may not do ambient IO at require — no `defonce (atom (load-io))`,
no env/file/home-dir reads. The operator's personal machine config becomes
invisible input to every host JVM on the box, and a bad file breaks
`require` itself on whoever's classpath loads the ns. Ambient resolution is
the STANDALONE entrypoint's job (main/daemon load it explicitly at startup);
the library defaults to builtins until the host calls `init!`.

Corollaries, learned the hard way (anima migration, 2026-08-29 — roster's
require-time `config*` leaked the operator's `:tools true` into embedded
session configs; fix ≡ library-config-inert-default):

- **Laziness is a precondition of every fix.** An opt-out knob can't help —
  the read fires at require, before any host call could run.
- **Reload must re-fold from the SOURCE, not the value** — else reload
  resurrects the leak a host's init! suppressed.
- Precedents: trace (nil-until-`init!` — why anima had zero flight-recorder
  hazard) ∧ escapement library mode (inject credentials, never read env).

Test for any NEW ns: would `(require …)` alone touch disk, env, or home?
Then it's standalone code, not library code.
