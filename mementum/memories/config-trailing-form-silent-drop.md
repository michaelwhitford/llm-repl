---
type: memory
symbol: ❌
title: config trailing-form silent drop — valid first form + garbage parses clean
related: [design/architecture, config-stickiness]
---

# config trailing-form silent drop

A stray `}` closed the config map at `:default-model`; the `:preamble` below
it became trailing text. `edn/read-string` reads the FIRST form and silently
ignores the rest — no parse error, key just gone. Worse than malformed: it
half-works.

**Symptom signature:** key present on disk (both sides of a mount), `nil` at
runtime, `reload-config!` doesn't help, no error anywhere.

**Discriminating test** (runtime, one eval):
`(:the-key (edn/read-string (slurp "config.edn")))` → nil while grep finds it
→ the key is outside the first form. Then `cat -n` and look for a premature `}`.

**Fix designed** (architecture § D7): `read-edn-file` reads ALL forms and
throws on more than one, naming the trailing content. Plus malli schema at
load with humanized errors.

Cost when unknown: ~40 minutes + one whole experiment session accidentally
run without its intended preamble. Check the parse boundary FIRST when
config-on-disk disagrees with config-at-runtime.
