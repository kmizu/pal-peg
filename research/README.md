# research — towards a small PEG for PAL

Scaffolding for the search for a *small* ordinary PEG (a few hundred rules) recognizing the binary
palindromes. Nothing here changes the proof in `../proof`; the grammars here are experiments.

```sh
sbt test                       # the harness checks itself
sbt "run sanity"
sbt "run rttm anbn 10"         # compile a real-time machine to a PEG and check it exhaustively
sbt "run rttm marked 8 print"  # also print the grammar
```

## Contents

| File | Contents |
|---|---|
| `Peg.scala` | PEG expressions, a parser for `Name = expr;` grammar text, a packrat matcher that reports non-consuming recursion |
| `Exhaust.scala` | exhaustive comparison of a grammar with the palindrome oracle, extent checks, random words |
| `Rttm.scala` | real-time multitape machines (one transition per input symbol) with a program-like rule notation and a simulator |
| `Rttm2Peg.scala` | compilation of a real-time machine into a PEG: one node per input position, tapes as pairs of persistent stacks, one rule per field |

## Calibration

The compiled PEG has about `2·L + |Q| + k·(2·|Γ| + 3)` rules for a machine with `L` program lines,
`|Q|` states, `k` tapes and tape alphabet `Γ`:

| machine | states | tapes | lines | PEG rules | nodes |
|---|---|---|---|---|---|
| `a^n b^n` | 4 | 1 | 7 | 28 | 454 |
| `u # uᴿ` | 3 | 1 | 7 | 27 | 486 |

So a real-time machine for PAL with a program of a few hundred lines would give a PEG of under a
thousand rules. The machine has to be real-time (worst-case constant work per input symbol), because
a PEG evaluates each nonterminal at each position at most once: amortized bounds do not help.

## Why not the direct routes

The rules of a PEG at position `j` compute functions of the suffix `w[j..]` only. Two consequences
were checked here and in `../../lean4-peg` notes:

- A PEG cannot compare the lengths of two extents from the same position, compute a midpoint, or
  transfer a length from one position to another. Constructions that need node identity by length
  (eertree, palindromic-prefix chains, midpoint folding) do not close with finitely many rules.
- Amortized real-time algorithms (Galil–Seiferas matching with service quanta, as in `../generator`)
  need the worst-case burst unrolled per position, which is where the current 13-million-rule
  grammar comes from.
