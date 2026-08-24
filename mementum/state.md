---
type: Working Memory
title: Project State
---

# Project State

> Bootloader. ~30-second read. For detail: `git log --oneline`,
> `knowledge/upstream/escapement.md`, README.md, idea.md.

## What this is

llm-repl — the tape (`messages[]`) as an immutable, forkable value; the repl as
a PLACE tapes live. Humans (TUI), models, editors ≡ equal nREPL clients.
Extracted from anima (`us.whitford.anima.llm-repl`) to test standalone
viability; anima either migrates onto this or evolves its copy separately —
**function names verbatim across repos keeps both doors open** (lineage policy).
Gen-1 was a Python repl attached TO a model; gen-2 inverts: clients attach to IT.

## Now

**Increment 2 (TUI) in flight.** Increment 1 DONE + live-verified at `7a79bae`.

- ✅ Increment 1: extraction. chat-memory + llamacpp backend + core ported
  verbatim; roster.clj replaces anima's llm.clj surface (config-file roster,
  `wrapped-backend` ≡ identity — NO capacity arbiter; hosts inject at
  `:complete-fn`). Launcher: nREPL first (`.nrepl-port`), plain prompt loop.
  VERIFIED: terminal ∧ attached nREPL client both round-trip qwen :5100;
  fork isolation proven live (parent depth 2 frozen, child advanced to 4).
- 🔄 Increment 2: TUI on escapement's pure primitives (theme/compositor —
  direct requires; ticker + key-decoder patterns copied; see
  `knowledge/upstream/escapement.md` for the full map + λ contracts).
  - done: `tui.clj` render half — pure `frame` (purity seam: headless-testable),
    state atom, 33ms dirty-ticker, alt-screen/signal/shutdown lifecycle,
    bracketed-paste escapes reserved.
  - next: input half (decoder + line editor + history + paste) → wire
    (registry add-watch, worker-thread eval!, form eval) → main integration
    (interactive→TUI, `--plain`, `--headless`) → live verify → commit.

## Invariants worth not rediscovering

- rf G1: `eval-rf` MUST keep the 1-arity completer (transduce calls it).
- rf G2: eager drivers only — the step blocks on IO; no sequence/eduction.
- esc-seq-timeout 50ms MUST be >0 (CSI tail misread as bare ESC — escapement
  bug history).
- `src/escapement/ui/*` is Fulcro/JVM-only — never require under bb.
- guardrails stays pinned 1.2.16 transitively — don't override.
- nucleus preamble ≡ `resources/genes/nucleus-preamble.edn`, the ONE
  AGPL-annotated file. Project LICENSE still TBD (human decision).

## Queue (rough order)

1. Finish increment 2 (see Now).
2. `:bbin/bin` entry → `llm-repl` on PATH.
3. Tape persistence (registry → disk; tree survives restart).
4. Split-pane tape view (watch a second session live).
5. MCP facade over the same command ns-publics.
6. LICENSE decision.
