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

1. **`registry.clj:83,90` — tap swallow-on-throw** 🔴. `event-tap*`/
   `mutate-tap*` invoked under `(catch Throwable _ nil)`. A broken tap ⇒
   durability silently stops while the app runs fine. S3* failing silently —
   who watches the watcher: nobody. Note: trace's OWN disk errors never
   reach the tap boundary (they're caught → `trace-fail!` receipts inside
   trace), so a throw HERE means the tap fn itself is structurally broken.
   Fix trap: a tap-failure receipt must not re-invoke the tap (recursion).
2. **`tui/term.clj` state atom — no chokepoint** 🟠. ~11 swap! sites across
   2 namespaces (`term.clj` + `main.clj` reach in directly), no key schema.
   Typo'd key ⇒ silent no-repaint. Every individual swap body IS pure — the
   pure-swap half holds; the chokepoint half doesn't.
3. **`daemon.clj:78-84` — corrupt state ≡ absent** 🟠. `read-state` catches
   → nil, so a torn `daemon.edn` write reads as "never started" → spawns a
   SECOND daemon racing the first. `clean-state!` drops `.delete`'s failure
   boolean. No temp+rename on the `spit` (trace's disk layer has it; this
   path doesn't).
4. **`llm_repl.clj:168-195` — config merge stickiness** 🟡 documented.
   `open!` merges, never replaces; absence ≠ reset; poison values outlive
   everything short of `drop!`. Design decision needed: see queue
   config-unset-semantics — nil COLLIDES with D7's present-nil ≡
   explicitly-none on prompt keys (`:system nil` must stay meaningful), so
   nil-clears cannot apply uniformly.
5. **`tools.clj:138` — unvalidated tool registration** 🟡. Zero local gating
   on what a host registers; lands directly on the model's tool wire.
   Kin: `register-manual-ns!` (llm_repl.clj:220) accepts a typo'd ns-sym
   that later breaks `(help)`/`(manual)` for EVERY caller (`find-ns` → nil →
   `ns-publics` throws); `events*` (registry.clj:53) lacks the EDN assert
   its sibling `sessions*` has.

## Smaller / accepted

- **No clock seam**: 9 scattered `System/currentTimeMillis` — tests can't
  control time without redefs. Gap, not bug; queued low.
- `sessions*`/`version*` direct-`reset!` escape hatch: documented,
  human-invoked, accepted.
- `mutate!`'s pure-f discipline: convention, unenforced (swap-vals! may
  retry f under contention — side effects in f would double-fire).
- trace `on-mutate` cross-write ordering: explicitly accepted non-atomicity
  (each write atomic, sequence unordered — its docstring says so).
- `.nrepl-port`: 2 readers with different parse paths (daemon.clj:102,163);
  single writer; low risk. Env reads scattered but single-site each.
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
