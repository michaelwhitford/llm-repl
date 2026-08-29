---
type: trap
symbol: ❌
title: "A kondo hook is repo contract — .clj-kondo/* gitignore silently strands it"
related: [knowledge/design/architecture, memories/jvm-macroexpand-wraps-macro-throws]
---

The repo's `.gitignore` had `.clj-kondo/*` with only `!config.edn`
whitelisted (the standard recipe — `.cache/` and `imports/` don't belong
in git). Adding `.clj-kondo/hooks/defcommand.clj` therefore SILENTLY
skipped the repo: `git add .clj-kondo/` succeeds, config.edn lands, the
hook doesn't — no error, no warning. The committed config then references
a hook CI cannot see, and CI's kondo re-derives every error the hook
exists to kill (here: 26 invalid-arity at defcommand's generated-arity
call sites). Local ≡ CI silently broken — the exact divergence the
project's lint pin exists to prevent, this time in the OTHER direction
(local stronger than CI).

Caught only because the editor's stale LSP pass showed what hook-less
analysis looks like, prompting the question.

**The law:** anything config.edn references is repo contract — whitelist
it beside config.edn (`!.clj-kondo/hooks/`). After adding ANY file under
a wildcard-ignored dir: `git status --short <dir>` must show it; and
lint the hooks dir itself (`clj-kondo --lint .clj-kondo/hooks`) — the
CLI habit of linting only src/ never reads it.
