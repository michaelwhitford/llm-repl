---
type: Audit
title: State discipline audit — every mutable site, graded (2026-08-29)
status: active
related: [design/architecture, design/trace-durability, wire-protocol,
          memories/guardrails-is-not-a-boundary-guard]
---

# State discipline audit (2026-08-29, pre-v0.3.0-tag)

Full sweep of `src/main/` (14 files) for every stateful construct: atoms,
dynamic vars, volatiles, disk state, implicit state. Grades: STRICT
(chokepoint + validated + pure swaps) · MIXED (partial discipline) · LOOSE
(scattered ∨ unvalidated ∨ silent). Counts: ~34 STRICT · ~13 MIXED · ~5 LOOSE.
The v0.3.0 tag WAITS on the strictness arc closing (human, this session).

## The finding that matters

Looseness clusters at exactly one kind of place: **S2 seams between units**.
Every S1 unit is strict internally (`sessions*`/`mutate!` EDN-asserted,
`eval!` race-visible, `RemoteCore`'s 7 atoms all pure/single-writer, trace's
ring, `config*` full-replace). `tape.clj` ∧ `tui/frame.clj` hold ZERO mutable
constructs. The gaps are where two units meet and discipline was delegated to
convention — and the worst one is the audit channel itself.

## Ranked gaps (→ queue tickets, same date)

1. **`registry.clj:83,90` — tap swallow-on-throw** 🔴 → ✅ **FIXED
   2026-08-29** (tap-failure-receipts, D9's first build ticket). Was:
   `event-tap*`/`mutate-tap*` invoked under `(catch Throwable _ nil)` — a
   broken tap ⇒ durability silently stops while the app runs fine; S3*
   failing silently. Now: `run-tap!` guards both seams — a throw DISARMS
   (reset! slot nil FIRST) then emits ONE `:tap-disarmed` receipt naming
   the tap ∧ throwable. The recursion trap ("a tap-failure receipt must not
   re-invoke the tap") is closed BY CONSTRUCTION: the slot is nil before
   the receipt's `event!` runs. One failure ≡ one receipt (test-locked, no
   spam); the mutation path is unharmed (swap ∧ EDN assert ∧ version bump
   all precede the tap); a still-armed event-tap observes a mutate-tap
   disarm, so the transcript records the durability loss. Still true: a
   throw HERE means the tap FN itself is structurally broken (trace catches
   its own disk errors internally → `trace-fail!` receipts).
2. **`tui/term.clj` state atom — no chokepoint** 🟠 → ✅ **FIXED
   2026-08-29** (term-state-chokepoint). Was: ~11 swap! sites across 2
   namespaces (`term.clj` + `main.clj` reaching in raw), no key schema —
   typo'd key ⇒ silent no-repaint. Now: `update-state!` ≡ THE chokepoint
   (registry/mutate!'s pattern at TUI scale) validating every result
   against the CLOSED `state-keys` set (12 keys) — unknown key throws loud
   naming it; same no-rollback pin as mutate!. All 13 sites rewired;
   main.clj's raw reaches became named mutators (`focus-slug!`,
   `set-pending!`) beside show-overlay!/scroll-view! — main never touches
   the atom's shape directly again. term.clj gained its first test ns
   (term_test.clj — the chokepoint ∧ mutators need no terminal; the
   byte-moving rest stays out of the suite by construction, D5).
3. **`daemon.clj:78-84` — corrupt state ≡ absent** 🟠 → ✅ **FIXED
   2026-08-29** (daemon-state-hygiene). Was: `read-state` caught → nil, so
   a torn `daemon.edn` read as "never started" → spawned a SECOND daemon
   racing the first; `clean-state!` dropped `.delete`'s boolean; no
   temp+rename on the spit. Now: `write-state!` ≡ spit-to-temp-sibling ⊕
   `ATOMIC_MOVE` rename (a reader sees old-complete or new-complete, never
   torn); `read-state` on corrupt (unparseable ∨ not-a-map) moves the file
   aside to `daemon.edn.corrupt` VERBATIM ⊕ one loud stderr line naming
   both paths, THEN treats absent — evidence preserved, silence dead;
   `clean-state!` returns `{:failed [paths]}` ⊕ a loud line per failed
   delete. Bonus from the same ticket: `read-port-file` ≡ THE one
   .nrepl-port parse path (was 2 divergent ones — see "Smaller" below),
   absent/blank → nil, garbage → loud throw carrying file ∧ content.
4. **`llm_repl.clj:168-195` — config merge stickiness** 🟡 documented.
   `open!` merges, never replaces; absence ≠ reset; poison values outlive
   everything short of `drop!`. Design decision needed: see queue
   config-unset-semantics — nil COLLIDES with D7's present-nil ≡
   explicitly-none on prompt keys (`:system nil` must stay meaningful), so
   nil-clears cannot apply uniformly.
5. **`tools.clj:138` — unvalidated tool registration** 🟡 → ✅ **FIXED
   2026-08-29** (registration-guards, D9's second build ticket). All three
   seams throw per the boundary-idiom rule (teaching ex-message ⊕
   `{:errors …}` ex-data; discarded-return side effects THROW):
   `tools/register-tool!` ≡ the guarded chokepoint — satisfies Tool ∧
   keyword tool-name ∧ input-schema compiles as malli AT REGISTRATION (not
   on the model's turn) ∧ collision throws (silent replacement was the
   failure; `=`-tool re-register ≡ no-op for reload idempotence; deliberate
   replace ≡ upstream `tp/register!`, the labeled escape).
   `register-manual-ns!` validates symbol ∧ `find-ns` — the typo'd-ns
   time-bomb in `(help)` is dead. `events*` got `sessions*`'s EDN assert at
   `event!` — validated BEFORE the ring (e is a value, unlike mutate!'s
   opaque f), ring stays clean, no version bump on reject.

## Smaller / accepted

- **No clock seam**: 9 scattered `System/currentTimeMillis` — tests can't
  control time without redefs. Gap, not bug; queued low.
- `sessions*`/`version*` direct-`reset!` escape hatch: documented,
  human-invoked, accepted.
- `mutate!`'s pure-f discipline: convention, unenforced (swap-vals! may
  retry f under contention — side effects in f would double-fire).
- trace `on-mutate` cross-write ordering: explicitly accepted non-atomicity
  (each write atomic, sequence unordered — its docstring says so).
- `.nrepl-port`: ~~2 readers with different parse paths (daemon.clj:102,163)~~
  → unified into `daemon/read-port-file` 2026-08-29 (daemon-state-hygiene).
  Env reads scattered but single-site each.
- `term.clj:252` `stopped?` — the codebase's one CAS, correctly idempotent.
- `llamacpp.clj:230` acc atom — textbook function-local, never escapes.
- `binding [*ns* *ns*]` pattern consistent at both eval surfaces
  (tools.clj:81, main.clj:114); `*capture?*` ∧ `*tool-depth*` have safe
  defaults and convey through futures on both runtimes.

## The reusable principle

Strictness ≡ per-seam, not per-unit. When auditing: grade the SEAMS
(registration slots, taps, cross-ns shared atoms, process boundaries), the
units will mostly be fine. The audit channel deserves the FIRST look, not
the last — it's the one whose silent failure hides every other failure.
