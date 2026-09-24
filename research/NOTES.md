# Notes: how small can a PEG for PAL be?

Working notes for the `research/small-peg` branch (2026-09-24). Nothing here changes the proof in
`../proof`.

## 1. The head machine is small

`SlisenkoRT.scala` drives the direct realization of Slisenko's recursion `f` (Zapiski LOMI 68,
1977, §4) as a multi-head machine: region search, Fischer–Paterson border pass over `S(x)`
(KMP whose failure chain is walked by head movement), and chained-lattice extension. One unit is
one step of the machine (every head moves by at most one cell). The machine reads one letter per
tick and is allowed a fixed number of units per tick; a prefix palindrome must be certified in
its own tick.

| input | units per tick needed |
|---|---|
| all words of length ≤ 14 (32 767) | ≤ 29 |
| Fibonacci, Thue–Morse, period-doubling, Zimin (3 000) | 23–31 |
| random palindromes, palindromes of palindromes (3 000) | 41–46 |
| random words | 5–18 |

Charges are conservative (3 units per KMP position, 2 per failure-chain cell). Random palindromes
are the worst because their only certification is at the very end, so the whole first half must
have been processed: the speed needed is the *average* work rate, about 34 units per letter, of
which the KMP pass is 18. Regions of radius ≤ 3 handled directly barely help (radii 4–10 are
common: they come from lattice ends and border centres).

So the *algorithmic* content of a real-time PAL recognizer is a few dozen head steps per letter.

## 2. Why the PEG is still not small: cells created within one tick

A PEG built from the machine has one position per letter and, inside a position, one layer of
nonterminals per unit (registers are functions of the previous layer). Pointers are extents, so a
pointer names a *node* (position), and a field of the target is a nonterminal name chosen
statically by the dereferencing rule. This is exactly the scaffolding-automaton model: a node is
created once per tick with fixed fields.

Consequences, all checked against what PEG can express:

- No equality or order test on pointers (extent comparison is not expressible). Head coincidence,
  "reached position F_B", "is this the centre" must be replaced by end-of-input tests, null tests,
  marks on a head's own trail, or unary countdowns.
- No pointer toward the frontier. Walking toward the start of the word (older nodes) is free;
  walking toward the frontier needs a *trail* built while walking the other way. Returning to the
  frontier after a lag costs the lag (walk down from the newest cell by an age counter).
- Numbers can be pointers into a shared unary chain: decrement and reset are O(1), increment is
  impossible (it would point to a younger cell); growing numbers are lists of blocks.
- **A stack that receives k pushes in one tick has k cells in one node.** A pointer into it must
  carry the index (log₂ B bits per pointer) and every dereference is a B-way choice. This is the
  same multiplexing that made the 13-million-rule grammar in `../generator` (quantum 512 with
  pooled tags): with B ≈ 45 units per tick and ~10 registers pointing into such stacks, the
  layered grammar is roughly 5 000–8 000 rules, not "a few hundred".

The only way around it is a machine that allocates O(1) cells per input letter (unbounded read
depth per tick is cheap: a data-dependent walk of up to B steps over *existing* node-level cells
is B rules and no bits). Slisenko's and Galil's algorithms are burst-based (a Fischer–Paterson
pass after each region), and the burst-free "live centres as O(log n) series" approach needs
O(log n) updates per tick. Whether a real-time palindrome recognizer with O(1) allocations per
letter exists is open; it is the question that decides the size of the smallest PEG for PAL.

## 3. What is committed

- `Peg.scala`, `Exhaust.scala`: PEG interpreter and exhaustive checks.
- `Rttm.scala`, `Rttm2Peg.scala`: real-time multitape machines → PEG (calibration: 27–28 rules
  for `a^n b^n` and `u#uᴿ`).
- `Slisenko.scala`: offline reference of `f` and conditions (A)/(B), checked ≤ 14.
- `SlisenkoRT.scala`: the real-time head machine and its speed measurements (`run slisenko-rt`,
  `run slisenko-stress`, `run slisenko-profile <word>`).
- `Psm.scala`: a first pointer-machine sketch (marked palindromes), superseded by the analysis
  above.
