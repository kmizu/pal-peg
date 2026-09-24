# pal-peg — `PAL ∈ PEG`, machine-checked in Lean 4

**English** | [日本語](README.ja.md)

This repository contains a machine-checked proof that the binary palindromes

```
PAL = { w ∈ {0,1}* | wᴿ = w }
```

are recognized by a **total** parsing expression grammar (PEG). The proof has no hypothesis. The repository also contains a Scala generator that prints an explicit PEG for the same construction.

```lean
theorem PalPeg.PalInPeg.unconditional : PegSeparation.RecognizedByTotalPEG PalPeg.PAL
theorem PalPeg.PalInPeg.evenPal_in_peg : PegSeparation.RecognizedByTotalPEG PalPeg.EvenPal
```

- Both theorems depend only on the standard axioms `propext`, `Classical.choice` and `Quot.sound`.
- The second theorem says that the even-length palindromes `{ w wᴿ }` are in PEG. This refutes Conjecture 7 of Loff, Moreira and Reis (2020).
- The PEG model and the SCA → PEG theorem come from the Lean artifact of Kim and Park (arXiv:2608.29592; Zenodo 10.5281/zenodo.22099762).

## Layout

| Directory | Contents |
|---|---|
| [`proof/`](proof/) | The proof: Lean 4.31, Mathlib, and the Kim–Park artifact. Start with [`proof/README.md`](proof/README.md), then read [`proof/PROOF.md`](proof/PROOF.md). |
| [`generator/`](generator/) | The Scala 3 generator. It prints the explicit PEG of the *window-pal* construction, compacts it, and checks it with the runner. |
| [`runner/`](runner/) | A plain PEG runner in Rust (packrat, no palindrome logic), and a faster compaction tool for generated grammars. |
| [`research/`](research/) | Experiments towards a *small* PEG for PAL (the current best: 6,960 rules). Start with [`research/APPROACH.md`](research/APPROACH.md); the measurements are in [`research/NOTES.md`](research/NOTES.md). |

## Checking the proof

```sh
cd proof
lake exe cache get     # first time: fetch the Mathlib build cache
lake build PalPeg      # builds the proof and runs the axiom audit PalPeg/Axioms.lean
```

## Printing the PEG

```sh
cd generator
sbt "runMain pal.GenerateWindowPal --verified /tmp/pal.peg"     # the budgets of the Lean proof
sbt "runMain pal.CompactScaffoldPeg /tmp/pal.peg /tmp/pal-compact.peg"
cargo build --release --manifest-path ../runner/Cargo.toml
sbt "runMain pal.VerifyWindowPal /tmp/pal-compact.peg --runner ../runner/target/release/plain-peg-runner --log /tmp/verify.log"
```

The full grammar has millions of rules. Generating it takes a long time and a large heap; we have not measured the peak memory of the Scala generator.

The Lean theorem is about the automaton, not about this printed grammar. The proof gets its PEG from the automaton through the Kim–Park construction. The generator uses its own SCA → PEG translator, which is tested on finite inputs but not verified.

## Tests

```sh
make test    # lake build (proof and axiom audit), sbt test (generator), cargo test (runner)
```

## License

MIT; see [`LICENSE`](LICENSE). The dependencies (Mathlib and the Kim–Park artifact) are fetched by Lake and keep their own licenses.
