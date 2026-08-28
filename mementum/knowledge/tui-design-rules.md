---
type: Design
title: TUI design rules — the ratified visual-language invariants
status: active
related: [design/architecture, upstream/escapement]
---

# TUI design rules (ratified)

> The rules that survived human ratification across v0.1–v0.2. The v0.3.0
> `tui.frame`/`tui.term` split re-homes the code; these rules govern any
> surface.

## Events ≡ global UI chrome, NEVER tape content

Receipts would repeat in every session's pane if they lived in the tape.
Structurally out of tape-lines; the tree pane's footer owns them (last ~5,
dim, receipt-length). Narrow mode has NO event display — the footer's home
is the tree pane only. **Receipts point INTO the tree; payloads live AT the
nodes.**

## stdout NEVER survives in the TUI

The alt screen paints over raw stdout. Every surface needs banner/output in
its OWN idiom: plain/headless print; the TUI renders welcome hints in the
empty tape pane and pops captured form *out* as an overlay titled with the
form. `help` RETURNS a string — a println would corrupt the alt screen.

## The overlay slot

One generic `{:title :lines}` slot — help, captured output, and future
compare panes all ride it. The VIEW swaps; the tape is untouched (chrome
never enters the tape). The frame decorates (⧉ + esc hint) — callers pass a
bare title. Esc dismisses overlay-first, else clears the editor.

## Scroll semantics — the sign flip lives in ONE place

Tape is TAIL-anchored (scroll+ ≡ older); an overlay document is
HEAD-anchored (scroll+ ≡ further down). `scroll-view!` owns the per-kind
sign flip; key handlers stay direction-literal. The frame returns the
EFFECTIVE (clamped) scroll and render syncs state to it — otherwise :scroll
drifts past content and reverse keys eat phantom distance (human-found).

## Movement ≡ the tree

Tab walks DFS tree order — movement on screen ≡ movement in the tree. The
tree pane is the map (glyphs, short arm names, @branch-points, current
highlighted, windowed around current); the tape pane is where you are.
`ab!` child naming (`parent-variant`) and the tree's `short-name` prefix
strip MUST agree (v0.3.0: one shared naming fn).

## Two audiences, two texts, one seam

Docstrings stay maintainer/agent-dense; the `^:manual` tag's string VALUE is
the curated human sentence. `(manual)` ≡ data, `(help)` ≡ human render.
Banner ≡ (help) ≡ overlay ≡ any future facade: ONE compile from ns-publics —
tagging curates the operator surface out of the plumbing.

## What `frame` needs from the registry — and what the wire actually sends

Audited 2026-08-28 (every access site read). `frame` needs FULL tape data for
exactly ONE session:

| scope | fields | sites |
|---|---|---|
| every session | slug (key), `:forked-from`, `:forked-at`, `(count :tape)` | `tree-lines`, `sessions-line`, roots/`children-of` |
| **focused only** | `[:config :model]`, `:tape` (the message vector), its count | `title-line`, `frame` (tape pane), `input-line` |

Everything registry-WIDE is edges ∧ counts. Only the pane you are looking at
needs bodies. And `registry/sessions-list` — "a compact index of live
sessions, no message bodies" — already returns precisely the wide set
(`:slug :model :depth :turns :forked-from :forked-at`; `:depth` IS the tape
count). **The projection was written and never put on the wire.**

The client instead fetches `@registry/sessions*` WHOLE, tapes included, on
every version bump (`client.clj:183`). Measured on the live daemon, synthetic
sessions carrying a real 6-message tape:

```
n=10   19 KB    n=50   99 KB    n=300  594 KB   (pr-str 21ms + read 14ms)
index  741 B          3.7 KB           22 KB    ← 27× smaller, CONSTANT ratio
```

Real sessions carry longer tapes, so those are FLOORS. At 50 sessions —
reachable today with a couple of `ab!` fans — it is already ~99 KB and ~6 ms
on every refresh. The cost is O(all state) per change when an O(index)
projection exists.

Blast radius is small: `registry` has ONE implementation (`RemoteCore`
`client.clj:206` — the TUI always goes over nREPL, `--plain` never calls
`frame`), and three consumers, two of which want only `keys`
(`main.clj:236,292,329`; `term.clj:74`). All of it is INTERNAL per
library-contract (`client` `tui.frame` `tui.term` `main` "may churn without
notice").

**The rule this buys, which matters more than the bytes:**

> Split the PAYLOAD, never the round-trip.

Index and focused tape must arrive from ONE eval. Two fetches are two points
in time: the tree renders depth N while the tape pane renders N−1 messages —
a torn read that looks like a rendering bug and isn't. The wire is an eval,
so `(let [...] {:index … :tape …})` keeps atomicity for free. Splitting the
round-trip would trade a size problem for a consistency problem.

Honest cost when this is built: `frame`'s signature grows a tape arg
(`(frame index tape state theme w h)`), ~5 accessor sites move from
`(count (:tape s))`/`get-in [:config :model]` to `:depth`/`:model`, and the
`frame_test` fixture (raw reg map, `frame_test.clj:157-162`) changes with it.
The tempting alternative — reconstituting a fake registry map client-side to
keep the tests green — keeps the shape a lie. Take the signature change.

## Mechanics worth keeping (escapement lineage)

- render loop ≡ one state atom ⊕ dirty flag ⊕ ~30fps ticker; any thread
  swaps+flags, ONE thread paints (attached-client activity visible ≤33ms)
- esc-seq-timeout 50ms MUST be >0 (CSI tail misread as bare ESC)
- full CSI param parse buys bracketed paste: a multi-line paste lands as ONE
  turn
- watches don't rewire on hot-reload — receipts/registry changes need a TUI
  restart to take effect
