---
type: trap
symbol: ❌
title: "JVM macroexpand-1 wraps macro throws in CompilerException — bb rethrows bare"
related: [memories/bb-jvm-private-var-twin-trap, memories/twin-first-load-latent-breaks, knowledge/design/architecture]
---

A macro that throws a teaching `ex-info` at expansion (defcommand's
compile-time gates) surfaces DIFFERENTLY per runtime:

- **bb/sci**: `macroexpand-1` rethrows the macro's exception BARE —
  `ex-message` ≡ the teaching text.
- **JVM**: `clojure.core/macroexpand-1` routes through
  `Compiler/macroexpand1`, which wraps it in `CompilerException`
  ("Unexpected error macroexpanding…") — the teaching text is now in
  `.getCause`.

So `thrown-with-msg?` on the message passes bb and fails JVM (observed:
8 green→red on the twin, same suite, 2026-08-29). And root-cause-only
matching overshoots when the macro CHAINS causes (defcommand's
schema-compile gate wraps malli's `:malli.core/invalid-schema` as cause —
the root is malli's, not the teaching text).

**The twin-stable match: join the WHOLE cause chain's messages**

```clojure
(try (macroexpand-1 form) nil
     (catch Throwable t
       (->> (iterate #(.getCause ^Throwable %) t)
            (take-while some?) (map ex-message) (str/join " | "))))
```

then `str/includes?` on the joined string. Same family as
bb-jvm-private-var-twin-trap: the twin suite exists precisely to catch
these.
