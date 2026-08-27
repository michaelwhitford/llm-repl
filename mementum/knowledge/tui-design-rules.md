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

## Mechanics worth keeping (escapement lineage)

- render loop ≡ one state atom ⊕ dirty flag ⊕ ~30fps ticker; any thread
  swaps+flags, ONE thread paints (attached-client activity visible ≤33ms)
- esc-seq-timeout 50ms MUST be >0 (CSI tail misread as bare ESC)
- full CSI param parse buys bracketed paste: a multi-line paste lands as ONE
  turn
- watches don't rewire on hot-reload — receipts/registry changes need a TUI
  restart to take effect
