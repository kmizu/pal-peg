# research — towards a small PEG for PAL

Scaffolding for the search for a *small* ordinary PEG (a few hundred rules) recognizing the binary
palindromes. Nothing here changes the proof in `../proof`; the grammars here are experiments.

**Current approach and status: [`APPROACH.md`](APPROACH.md)** (the slot-scheduled pointer machines
`Lps*.scala`). Next steps: [`STRATEGY.md`](STRATEGY.md) ([日本語](STRATEGY.ja.md)) — what the 999 rules are made of, the ranked levers, and §3b: the correction of the POOLS grammar; §3c–§3d: 12/4 + unfold + `tmp`, W₀ 128, mirrored fallback, named sub-expressions (996 rules at ×2.7, 2.03 MB); §3e: seven ideas measured (11/4 + `lgap` + one pop merge, β 13/32: 994 rules at ×2.56, 1.91 MB); §3f: the branching of one read, and the current best grammar (the §3e machine with points-to domains and no redundant tag tests: 996 rules at ×2.45, 1.54 MB). §3g: capping the cells a tick may keep, measured — the gap matcher allocates in most of its steps, so the cap fails the rates; the next cut needs a matcher whose pops do not allocate. §3h: such pops do not exist in this model (cells point only to older cells), and free pops would buy only about 15 % of the arms.
Latest result: [`POOLS.md`](POOLS.md) ([日本語](POOLS.ja.md)) — the role machine at 15/4, 4,216 rules compact and
**999 rules after budgeted inlining** (8.0 MB); before it [`UNDER1000.md`](UNDER1000.md) (5,902 rules, wrong on one
palindrome of 128 letters, corrected there) and [`BREAKTHROUGH.md`](BREAKTHROUGH.md) (8,336). The measurements in order: [`NOTES.md`](NOTES.md).

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
| `Slisenko.scala` | reference implementation of the recursion `f` and the conditions (A), (B) of Slisenko's real-time palindrome recognizer (Zapiski LOMI 68, 1977, §§3–4), checked against brute force on all words of length ≤ 14 |

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

## The candidate machine

Slisenko (1977) gives a complete and short description of a linear-time multitape machine that finds
all initial palindromes, and shows that a constant-factor speed-up of it runs in real time (his §4,
claims 4.5 and 4.6). Its parts are the Fischer–Paterson algorithm for the palindromic suffixes of
a word (a KMP failure function on `X * X`), a two-head symmetry-region search, a four-head extension
of arithmetic progressions of centres ("chained lattices"), and a recursion `f` over points and
lattices. `Slisenko.scala` implements `f` and the centre conditions and agrees with brute force.
The open question for the PEG size is the speed-up factor: the PEG must unroll that many machine
steps per input symbol, unless the bursts can be expressed as memoized single-cursor walks.

## Why not the direct routes

The rules of a PEG at position `j` compute functions of the suffix `w[j..]` only. Two consequences
were checked here and in `../../lean4-peg` notes:

- A PEG cannot compare the lengths of two extents from the same position, compute a midpoint, or
  transfer a length from one position to another. Constructions that need node identity by length
  (eertree, palindromic-prefix chains, midpoint folding) do not close with finitely many rules.
- Amortized real-time algorithms (Galil–Seiferas matching with service quanta, as in `../generator`)
  need the worst-case burst unrolled per position, which is where the current 13-million-rule
  grammar comes from.
