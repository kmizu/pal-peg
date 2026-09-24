# `proof/` — `PAL ∈ PEG` in Lean 4

*日本語版: [README.ja.md](README.ja.md)*

We prove in Lean 4, with no hypothesis, that the language of binary palindromes

```
PAL = { w ∈ {0,1}* | wᴿ = w }
```

is recognized by a **total** parsing expression grammar. As a corollary, the even-length palindromes
`{ w wᴿ }` are too, which refutes Conjecture 7 of Loff, Moreira and Reis (2020).

## Main results

```lean
theorem PalPeg.PalInPeg.unconditional : PegSeparation.RecognizedByTotalPEG PalPeg.PAL
theorem PalPeg.PalInPeg.evenPal_in_peg : PegSeparation.RecognizedByTotalPEG PalPeg.EvenPal
```

Both are in `PalPeg/PalInPegFinal.lean`.

- `#print axioms` gives `[propext, Classical.choice, Quot.sound]` for both, which are the standard axioms of Lean and Mathlib.
- The development uses no `sorry`, no `admit` and no `native_decide`, and declares no `axiom`.
- `PalPeg/Axioms.lean` pins these axiom lists with `#guard_msgs`, so any hole makes the build fail.

`RecognizedByTotalPEG L` comes from the Kim–Park artifact (`PegSeparation/Common/Recognition.lean`). It says there exist a nonterminal count `n` and a PEG `G` such that:

- `G.IsLoffTotal` holds, so `G` terminates on every input;
- `∀ w, G.Recognizes w ↔ w ∈ L`.

`PalPeg.EvenPal` is `{ w | ∃ u, w = u ++ uᴿ }` (`PalPeg/EvenLength.lean`).

## Checking it

```sh
cd proof
lake exe cache get     # first time: fetch the Mathlib build cache
lake build PalPeg      # includes the axiom audit PalPeg/Axioms.lean
```

To print the axioms yourself:

```sh
cd proof
printf 'import PalPeg.PalInPegFinal\n#print axioms PalPeg.PalInPeg.unconditional\n' > /tmp/Check.lean
lake env lean /tmp/Check.lean
# 'PalPeg.PalInPeg.unconditional' depends on axioms: [propext, Classical.choice, Quot.sound]
```

Toolchain: Lean v4.31.0 and Mathlib v4.31.0, both pinned in `lean-toolchain` and `lakefile.toml`.

## What has to be trusted

1. The Lean kernel and Mathlib.
2. The Kim–Park artifact `PegSeparation` (Zenodo 10.5281/zenodo.22099762, commit `c364edb`). We use two parts of it:
   - its definitions of PEGs and scaffolding automata (SCA) and their semantics, including `RecognizedByTotalPEG` and `RecognizedBySCA`;
   - its theorem `SCAToPEG.loffBackward`: for every SCA there is a total PEG recognizing the reverses of the words the SCA accepts.
3. Our definition `PalPeg.PAL := { w | w.reverse = w }` (`PalPeg/Basic.lean`).

The machine, its workers, and the Galil–Seiferas matching algorithm all live inside the proof, so none of them has to be trusted.

## How the proof goes

In short, we build a scaffolding automaton for `PAL`, and `SCAToPEG.loffBackward` together with `PALᴿ = PAL` turns it into a total PEG.

The automaton is the one that the Scala generator in this repository compiles into an explicit PEG: `GenerateWindowPal` in [`../generator`](../generator), the *window-pal* construction. We model that construction in Lean, component by component. It reads one letter per tick and keeps the following in a finite control and a bounded number of persistent stacks:

- **two dyadic stages** — when `|w| ≥ 4` and `W = 2^(⌊log₂|w|⌋−1)`, the word `w` is a palindrome iff two conditions hold:
  - `(w.take W)ᴿ` is a suffix of `w`;
  - the middle `w[W, |w|−W)` is a palindrome;
- **a matcher per stage**, which runs Galil–Seiferas real-time string matching of the reversed prefix against the rest of the word;
- **a flag worker per stage**, which computes which prefixes of the text read since the stage started are palindromes, in four jobs per stage. The middle `w[W, |w|−W)` is such a prefix.

The workers are the Scala tables themselves, 294 instructions for the matcher and 492 for the flags. They run a fixed number of instructions per tick: 2048 for the matcher and 32768 for the flag workers.

[PROOF.md](PROOF.md) walks through the proof layer by layer and maps each step to its file.

## Relation to the explicit PEG

The Scala generator in [`../generator`](../generator) prints an explicit PEG for the same automaton. It turns the automaton into a PEG with its own translator (`SymbolicSca2Peg`, followed by rule inlining), not with Kim–Park's `loffBackward`.

- **What is proved.** The Lean theorem is about the automaton, and its PEG comes from `loffBackward`. The printed grammar is not verified: no Lean theorem says that the printed grammar recognizes `PAL`.
- **Budgets.** `GenerateWindowPal --verified` uses the per-tick instruction budgets of the Lean proof (`GsBatchClock.VERIFIED_BATCH`).
- **The default budgets.** The default budgets (512 and 1024) are supported by measurements only. The worst case we found is about 104 steps per letter against a budget of about 470. The Lean proof uses larger budgets because our proven step bounds are looser: decomposition within `1698·|x| + 230` steps, and a flags job within `8098·|y| + 10`.

## Layout

| Path | Contents |
|---|---|
| `PalPeg/PalInPegFinal.lean` | the main theorem, the Conjecture 7 corollary, and the two step-count facts |
| `PalPeg/Sca*.lean` | the window-pal construction, its workers, their head VMs, and the encoding into an SCA |
| `PalPeg/GS*.lean`, `Matching`, `Words`, `Stages`, `Assembly`, … | list-level theory: Galil–Seiferas decomposition and real-time verification, dyadic stages, and the combinatorics of words |
| `PalPeg/PalInPegSca.lean` | SCA → PEG through Kim–Park |
| `PalPeg/Existence.lean`, `PalPeg/EvenLength.lean` | the earlier conditional theorem (from a real-time TM) and the Conjecture 7 argument |
| `PalPeg/Axioms.lean` | the axiom audit |

This repository contains only the final proof. Earlier routes and experiments, among them a route through a 118-tape real-time TM, are not included.
