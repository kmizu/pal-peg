# CLAUDE.md

Guidance for coding agents working in this repository.

## What is here

| Directory | Contents | Toolchain |
|---|---|---|
| `proof/` | The proof of `PAL ∈ PEG` (`PalPeg.PalInPeg.unconditional`, standard axioms only) and the Conjecture 7 corollary | Lean v4.31.0 + Mathlib v4.31.0 + the Kim–Park artifact `PegSeparation` (commit `c364edb`), all pinned |
| `generator/` | Scala 3 generator that prints the explicit PEG of the window-pal construction | Scala 3.7.4, sbt 1.11 |
| `runner/` | Plain PEG runner and grammar compaction tool | Rust (no dependencies) |

## Commands

```sh
make test                                  # all three parts
cd proof && lake build PalPeg              # proof + axiom audit (PalPeg/Axioms.lean)
cd generator && sbt test
cargo test --offline --manifest-path runner/Cargo.toml
```

`lake` may need `. ~/.elan/env` first. In WSL2, sbt sometimes fails on `/run/user/1000`; use
`XDG_RUNTIME_DIR=/tmp/xdg-1000 sbt ...` (after `mkdir -p /tmp/xdg-1000`).

## Rules

- No `sorry`, `admit`, `native_decide`, or new `axiom`. `proof/PalPeg/Axioms.lean` pins the axiom
  lists of the main theorems with `#guard_msgs`; keep it passing.
- A new Lean module must be imported from `proof/PalPeg.lean` (directly or transitively), or the
  build will not check it.
- Scala 3 code uses brace syntax, not indentation syntax, and compiles with `-Wunused:all -Werror`.
- The Lean model follows the generator: `ScaWindowPal.lean` transcribes
  `generator/src/main/scala/pal/ScaffoldWindowPal.scala`, and `ScaGsTables.lean` holds the worker
  tables that `GsProgram.compileController` produces. If the generator changes, update the Lean
  transcription and rerun the proof.
- The printed grammar is not verified; the Lean theorem is about the automaton. Say so when
  describing results.
- Documentation is in English, with Japanese versions in `*.ja.md`.
