# EOF-coded sites and overlapping anchors: results (2026-09-25)

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

## Final table

| variant | N | rhs nodes | max rhs | bytes | colours | rates | warmup / first answer | machine tests | printed PEG tests | proof |
|---|---:|---:|---:|---:|---:|---|---|---|---|---|
| baseline PAL3 (`out/pal3-m12-f36.peg.gz`) | 25,510 | 2,361,254 | 894 | 12,410,391 | bits | 12/36 | — | repo (NOTES §6) | repo | unproven |
| PAL3 + warmup + code (`out/pal3-code-m12-f36.peg.gz`) | 14,819 | 739,084 | 793 | 4,083,371 | 36 | 12/36 | W0 = 64 / n = 128 | interpreter: all words ≤ 10, 100 random ≤ 513, boundary 127–257 and structured ≤ 260 without deadline/report/reachability violations | PEG = interpreter above the fallback (1/1, W0 = 4, ≤ 10); re-parsed ≤ 9; Scala matcher on 8 boundary words of 127–128; Rust runner on 40 raw words 127–600 | unproven |
| overlap + old backend (`out/overlap-old-a15-b24.peg.gz`) | 12,048 | 870,025 | 594 | 4,835,137 | bits | 15/24 | — / n = 3 | all words ≤ 10; 300 random ≤ 121; 100 random ≤ 513; 412 switch words ≤ 513 and 122 structured ≤ 513 with no violation (report bit, answering bank, queue order, replay, output) | PEG = interpreter (1/1, 2/3, ≤ 6); PEG = oracle at 15/24 (≤ 5); re-parsed ≤ 9; Rust runner on 40 raw words 127–600 | unproven |
| **overlap + code** (`out/overlap-code-a15-b24.peg.gz`) | **6,960** | **315,161** | **529** | **1,820,016** | 27 | 15/24 | W0 = 32 / n = 48 | interpreter with minAnchor 32: 412 switch words ≤ 513 and 122 structured ≤ 513 without violations (false below 48 as specified) | PEG = interpreter above the fallback (1/1, W0 = 4, ≤ 10); re-parsed ≤ 9; Scala matcher on 12 boundary words of 47–49; Rust runner on 56 raw words 47–600 | unproven |

(Trie dispatch for the code backend: same N, +31% rhs nodes on PAL3+code; not adopted.)

Rates (`out/metrics/overlap-rate-search.md`): 16/24 and above are clean on every family; 12/18 fails
(bank A late at its first answering tick on `a^12`); A needs 15 (14 fails), B needs 23–24 (22 fails on one
structured word; 23 was not run on the random families). These are observed minima on the tested inputs,
not bounds; the backlog on false ticks grows (up to 212 letters on the switch words ≤ 513) and is allowed by
the specification, the true full matches were always reported in their tick.

Cost structure of the best grammar (compact 6,960): site-code rules 1,405, helpers 54, fallback 50; the
rest are the two banks' universal steps (15 + 24 copies) and the controller. The next cost item is the copy
count itself: each U copy still carries its own field rules and dereferences.

## What is and is not established

- Mathematically: the overlap identity (§4.1 of the plan) and the anchor coverage; the EOF-code algebra of
  appendix A (ported to `SiteCodeSuite`).
- By finite tests (this repository): the machines' outputs against the oracle, the printed PEGs against the
  interpreter, the oracle and the Rust runner, the deadline and queue-order checks listed above.
- Not established: a general rate bound for the two banks (the plan's potential argument), totality of the
  printed PEG beyond the structural argument (helpers are consuming recursion; every same-position reference
  goes to an earlier allocation site of the same slot or to a rule of a lower layer), and any Lean statement.

## Reproduce

```sh
cd research   # under WSL2: XDG_RUNTIME_DIR=/tmp/xdg-1000 sbt
sbt test
sbt "run lps-code sizes overlap 15 24"                                  # colours 27, W0 32, first answer 48
sbt "run overlap-profile 15 24 switch 513 32" "run overlap-profile 15 24 structured 513 32"
sbt "run lps-code optimize overlap 15 24 auto out/metrics/ab-overlap-code-a15-b24.json"
sbt "run lps-code export overlap 15 24 auto out/overlap-code-a15-b24.peg" "run lps-check-file out/overlap-code-a15-b24.peg 9"
sbt "run lps-code check overlap 15 24 32 <words>"                       # Scala matcher vs oracle on given words
../runner/target/release/plain-peg-runner out/overlap-code-a15-b24.peg <raw words>
```
