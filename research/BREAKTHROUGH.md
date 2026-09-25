# EOF-coded sites and overlapping anchors: results (2026-09-25)

> Superseded for the matcher by `UNDER1000.md` (the gap core, 5,902 rules at 10/13; the budgeted inliner). The
> controller, the anchors, the code backend and the rate-search lessons below are unchanged.

Executed from the research plan `pal-peg-breakthrough-claude-code-1266231.md` on top of main `1266231`
(baseline `LpsPal3` 12/36: 25,510 rules). Everything below is a finite check of an experimental machine
and its printed plain PEG; nothing here is a Lean proof (the proof in `../proof` is untouched and still
covers the window-pal construction, not these workers).

## The two changes

**A — the site as an EOF-anchored second extent (`SiteCode.scala`, `LpsCode.scala`).** A pointer is
`(address extent, code extent)`: the cell allocated at site `s` carries the helper `Code_{colour(s)}`, which
ends `colour(s)` letters before the end of the input (`Tail_0 = !.`, `Tail_i = . Tail_{i-1}`,
`Code_i = &Tail_i / . Code_i`); a site test is `&(P_code Tail_i)`; a field of a pointer is chosen by the
code of its base (`&(x_c Tail_c(s)) x_ext C_s_f`), registers copy codes, conditional chains select them with
the same exclusive masks, the previous tick's code is `. &. last_c` (consuming). Sites are coloured so that
colours are injective on every set of sites a pointer may point into (`SiteCode.color`, asserted); one code
rule per pointer replaces the log₂(sites) site-bit rules. A code `c` needs `c` remaining letters, so no
machine cell may be born before tick `W0 = nextPow2(colours)`: the controller disables the stages of width
below `W0` (`LpsWarmup`, `LpsPal.ct1(gate)`), the debug interpreter checks that no reachable machine cell is
born earlier (`LpsWarmup.checkReachable`), and the lengths below the first machine answer are decided by the
bounded fallback `S = Short / &Long &accept .*` (`Short` = the exact-length palindromes with EOF inside each
alternative, `Long` = the length bound).

**B — overlapping anchors, no flags worker (`OverlapPal.scala`).** `w` is a palindrome iff its suffix of
length `h` is the reverse of its prefix of length `h` for any `⌈n/2⌉ ≤ h ≤ n` (1,092,266 checks on all words
≤ 16). Two matcher banks with pattern lengths `W, 3W/2, 2W, 3W, …` (family A: `h = W`, answers `[3W/2, 2W)`;
family B: `h = 3W/2`, answers `[2W, 3W)`) cover every length from 3 with an anchor `h ≥ n/2`. A bank reads its
pattern backwards from the birth cell and, during the first `k = 2h − n0` loads (a unary countdown over the
saved dyadic pointer: `W/2` for A, `W` for B), prepends the letter cells to the FIFO, so its text is
`rd[t0:n]` with `t0 = n0 − h`; letters arriving during BUILD go to the back of the queue. The controller
keeps the dyadic clock `pw/nb` and one half clock `clk` (armed at a dyadic birth, runs out at `3W/2`;
`clk == null` selects family A). `Kmp2` is reused unchanged. The middle-palindrome flags worker (72% of the
baseline grammar), its results and the CT2 consumer are gone.

## Rates: the start-up bound and the adversarial words

The final review found that the first rate search (structured, random, switch words) had accepted rates
at which a bank's *first* answer is late: the pattern `a^(h−1) b` is the most expensive table build for
`Kmp2` (about 4 steps per `a`, then a full-chain back-up on the `b`), and the palindrome `b a^(n−2) b` gives
exactly that pattern to the bank answering at `n`. `OverlapProfile.adversarialWords` now contains
`b a^(n−2) b` for every `n` and `a^i b a^j b a^i` around the anchors (`LpsProfile.adversarialWords` the same
for PAL3). An accounting from the per-operation costs of `Kmp2` (build ≤ ~6 steps per letter including back-ups
and entry-queue reversals, archive reversal `h + 1`, replayed letters 2 each, new letters 3 each) gives, for
the `n0 − h` ticks before the first answer, `(6W + W + W + 1.5W) / (W/2) = 19` steps per tick for family A
and `(9W + 1.5W + 2W + 1.5W) / (W/2) = 28` for family B. The measured minima agree exactly: A = 18 and
B = 27 still fail adversarial words, **19/28** passes all families (`out/metrics/overlap-rate-search.md`).
This is an accounting, not a proof of a rate bound for all inputs.

The same review showed that PAL3 12/36 — the previous deliverable — misses a flags deadline on
`a^94 b a^266 b a^94` and two similar words, answering correctly only by reading a stale result of an earlier
stage generation (and accepting the non-palindrome `a^129 b a^94`). PAL3 needs 12/40 (the rate of PAL2):
`lps-profile pal3 12 40 adversarial 400` → 495 words, 0 violations. The 12/36 grammars were removed from `out/`.

## Final table

| variant | N | rhs nodes | max rhs | bytes | colours | rates | warmup / first answer | machine tests | printed PEG tests | proof |
|---|---:|---:|---:|---:|---:|---|---|---|---|---|
| baseline PAL3 12/36 (main; **invalid**: misses a flags deadline on `a^94 b a^266 b a^94`, accepts `a^129 b a^94`) | 25,510 | 2,361,254 | 894 | 12,410,391 | bits | 12/36 | — | fails the adversarial words | fails | — |
| PAL3 12/40, old backend (`out/metrics/task3-pal3-m12-f40.json`) | 27,550 | 3,614,066 | 1,222 | 22.3M | bits | 12/40 | — | 495 adversarial words ≤ 400 and the flags-interval/structured words without violation | (not re-exported) | unproven |
| PAL3 + warmup + code (`out/pal3-code-m12-f40.peg.gz`) | 15,967 | 861,036 | 881 | 4,762,519 | 40 | 12/40 | W0 = 64 / n = 128 | warmup 12/40 W0 = 64: 495 adversarial + 37 boundary words without deadline/report/reachability violations | re-parsed ≤ 9 (12/36 version); Rust runner on 112 adversarial raw words 47–600 | unproven |
| overlap + old backend (`out/metrics/b2-overlap-old-a19-b28.json`) | 14,955 | 1,256,793 | 694 | 6,969,322 | bits | 19/28 | — / n = 3 | all words ≤ 10; 300 random ≤ 121; 100 random ≤ 513; 788 adversarial ≤ 600, 412 switch ≤ 513, 122 structured ≤ 513 with no violation | PEG = interpreter (1/1, 2/3, ≤ 6); PEG = oracle at 19/28 (≤ 5) | unproven |
| **overlap + code** (`out/overlap-code-a19-b28.peg.gz`) | **8,336** | **431,165** | **617** | **2,502,032** | 31 | 19/28 | W0 = 32 / n = 48 | interpreter with minAnchor 32: 788 adversarial ≤ 600, 412 switch ≤ 513, 122 structured ≤ 513 without violations (false below 48 as specified) | PEG = interpreter above the fallback (1/1, W0 = 4, ≤ 10); re-parsed ≤ 9; Scala matcher on `b a^k b` and flips at 47–49; Rust runner on 112 adversarial raw words 47–600 | unproven |

Withdrawn rows (in `out/metrics/*a15-b24*.json` and `*m12-f36*.json` for the record only): overlap 15/24
(6,960 rules with the code backend, 12,048 with the old one) rejects `b a^(n−2) b` at n = 48–52, 64–65, 96–106,
128–133, 192–199; PAL3 + code 12/36 (14,819 rules) inherits the PAL3 deadline miss.

Cost structure of the best grammar (compact 8,336): site-code rules 1,685, helpers 62, fallback 50; the rest
are the two banks' universal steps (19 + 28 copies) and the controller. The next cost item is the copy
count itself: each U copy carries its own field rules and dereferences, and the copies are set by the
start-up cost of the pattern `a^(h−1) b` (about 6 steps per letter in `Kmp2`'s build), not by the steady
state (3 per letter).

## What is and is not established

- Mathematically: the overlap identity (§4.1 of the plan) and the anchor coverage; the EOF-code algebra of
  appendix A (ported to `SiteCodeSuite`); the start-up accounting above as an explicit sum of unit costs.
- By finite tests (this repository): the machines' outputs against the oracle on the families listed, the
  printed PEGs against the interpreter, the oracle and the Rust runner, the deadline and queue-order checks.
- Not established: a rate bound for all inputs (the accounting assumes the per-letter costs of `Kmp2`'s
  build are the worst case; the adversarial family realises them, other patterns were not searched
  exhaustively), totality of the printed PEG beyond the structural argument (helpers are consuming
  recursion; every same-position reference goes to an earlier allocation site of the same slot or to a rule
  of a lower layer), and any Lean statement.

## Reproduce

```sh
cd research   # under WSL2: XDG_RUNTIME_DIR=/tmp/xdg-1000 sbt
sbt test
sbt "run lps-code sizes overlap 19 28"                                  # colours 31, W0 32, first answer 48
sbt "run overlap-profile 19 28 adversarial 600 32" "run overlap-profile 19 28 switch 513 32" "run overlap-profile 19 28 structured 513 32"
sbt "run lps-code optimize overlap 19 28 auto out/metrics/ab-overlap-code-a19-b28.json"
sbt "run lps-code export overlap 19 28 auto out/overlap-code-a19-b28.peg" "run lps-check-file out/overlap-code-a19-b28.peg 9"
sbt "run lps-code check overlap 19 28 32 <words>"                       # Scala matcher vs oracle on given words
../runner/target/release/plain-peg-runner out/overlap-code-a19-b28.peg <raw words>
```
