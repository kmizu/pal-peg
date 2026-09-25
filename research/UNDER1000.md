# Towards a PAL PEG below 1,000 rules: results (2026-09-25)

> **Correction (2026-09-25, `POOLS.md` §0).** The 10/13 grammar of this document answers **false** on a palindrome of 128
> letters (a random palindrome found by the pools plan's fourth word family; kept in `src/test/resources/pools-counterexamples.txt`):
> bank B is late at its first answering tick. Both `out/under1000-gap-a10-b13*.peg.gz` grammars are therefore wrong on that word.
> The per-bank rate that passes all four families is 10/14 (6,177 rules compact). The rest of this document is unchanged.

Executed from `pal-peg-under-1000-claude-code-c4b38c5.md` on top of main `c4b38c5` (the 8,336-rule grammar of
`BREAKTHROUGH.md`). Everything below is a finite check of experimental machines and their printed plain PEGs;
nothing here is a Lean proof (the proof in `../proof` is untouched).

**Outcome in one line: the target N < 1,000 was not reached.** The best measured grammars are in the Pareto table
of §4; the obstruction is quantified in §5 (a rate floor times a per-layer core that inlining cannot remove).

Every number below is labelled: *measured* (from the exported grammar or the interpreter), *estimate* (an explicit
cost sum), *bound* (a proved inequality), or *target* (a budget).

## 1. Baseline reproduced (measured)

`sbt test` (49 tests), `lps-code sizes overlap 19 28` (colours 31, W0 32, first answer 48), the three `overlap-profile`
runs at 19/28 (788 adversarial ≤ 600, 412 switch ≤ 513, 122 structured ≤ 513: no violation), `lps-code optimize`
(8,336 / 431,165 / 617 / 2,502,032, `out/metrics/under1000-baseline.json`), export and `lps-check-file … 9`. The
exported grammar's sha256 (`67bbf88c3aeafbf9…`) equals the committed `out/overlap-code-a19-b28.peg.gz`.

Census of the 8,336 rules by name pattern (measured): register/bit rules 1,632 (32 per universal-step layer × 51
layers), site field rules 660 (≈ 14 per layer), condition rules `K…` 3,696 (≈ 78 per layer), pointer sub-expression
rules `X…` 2,216 (≈ 47 per layer), fallback 50, helpers 62, controller ≈ 20. So ≈ 171 rules per copy of the
universal step, of which ≈ 46 form the *recursive core* (registers and site fields refer to each other across
layers) and ≈ 125 are named sub-expressions that an inliner can fold into the core at the price of RHS nodes.

## 2. Supporting route: the budgeted inliner (`PegBudgetInline.scala`, plan §5)

Greedy elimination of named intermediates under a node or byte budget and a max-RHS bound; the cost model is the
plan's (`deltaNodes = u(s−1) − s`, `deltaBytes ≈ u(b+2−l) − (b+l+5)`, the byte formula an estimate — the exported
metrics decide); the start rule, protected rules and self-referencing rules are never eliminated and self-reference
is re-checked after every substitution. `PegBudgetInlineSuite` checks the extent of every retained rule at every
position on all words (hand grammars with predicates, repetition and mutual recursion; the compiled KMP2 grammar
≤ 5 over `ab#`), the re-parse round trip, and that the byte estimate never undercounts the rendered bytes.

On the baseline (measured, `out/metrics/under1000-inline-*.json`, `under1000-inline-pareto.md`; `lps-inline-sweep`):

| budget (relative to the compacted input) | N | rhs nodes | max rhs | bytes |
|---|---:|---:|---:|---:|
| input (compact) | 8,336 | 431,165 | 617 | 2,502,032 |
| nodes ×1.0, maxRhs 4096 | 5,557 | 431,164 | 699 | 2,458,633 |
| nodes ×1.25 | 3,301 | 538,921 | 1,443 | 2,885,277 |
| nodes ×1.5 | 2,981 | 646,689 | 1,657 | 3,394,875 |
| nodes ×2 | 2,670 | 861,873 | 1,849 | 4,429,365 |
| nodes ×3 | 2,337 | 1,293,283 | 3,937 | 6,444,888 |
| nodes ×4 | 2,198 | 1,721,040 | 4,094 | 8,336,512 |
| nodes ×4, maxRhs 16384 | 2,151 | 1,721,424 | 14,561 | 8,347,898 |
| bytes ×1.0 | 4,171 | 450,469 | 725 | 2,489,908 |

The floor ≈ 2,150 is the recursive core of the baseline machine (51 layers × ≈ 46 rules): no budget removes it.
The plan's Python figures (5,541 / 4,170 / 2,653 / 2,142) are reproduced within tie-breaking.

## 3. Main route: the gap matcher (`GapKmp.scala`, plan §4)

### 3.1 What the abstract algorithm needs (measured on exact lists, `probes/*.py`, `BinaryGapKmpSpecSuite`)

- Appendix A reproduces its headline (8,190 patterns ≤ 12; 129,794 pattern/text pairs, every prefix).
- **Range lemma.** Every sequence the matcher forms (`g_i`, `h_i`, `C`, `D`, `front`) is a contiguous range of entry
  indices, and during matching `front = [cur+1 .. m]` (checked on the same 8,190 patterns and 129,794 pairs). So the
  whole matcher state is `cur`, and the only operation a persistent structure has to provide is `succ(E_a)` — the
  entry allocated *after* `E_a`. An immutable entry cannot hold that pointer, and the transition graph of a KMP
  automaton is strongly connected (state 0 is reachable from every state and every state from 0), so no assignment
  of one pointer per transition exists in a DAG: successors must be materialised by allocation (Kmp2's walks, or a
  persistent sequence).
- Storing every `h_i` flat costs Σ|h_i| = m(m−1)/2 on `a^m` (measured to m = 256): not O(1) per letter.
- Storing `h_i` as a nested snapshot popped lazily costs, per pop, the nesting met: max m−2 on `a^(m−1) b`, m/2 on
  `(ab)^k` (measured to m = 1024), mean 1.1–1.25. Amortised O(1) per letter (a pop out of `h_cur` costs at most
  c·|h_cur| ≤ c·(matches since the last jump)); worst case Θ(m) — the same profile as Kmp2's BACK walks. A worst-case
  O(1) structure (Kaplan–Tarjan catenable steques) was not ported: the gate of §5 shows that even unit costs per
  letter leave the two-bank architecture above 1,000 rules once the per-layer core is counted.

### 3.2 The implemented core (amortised, plan's option 3)

`GapKmp.Core` keeps the live sequence as `[[L]] ++ concat(K) ++ reverse(R)`: `L` a list whose nodes are `LNODE(e,
rest)` or `GNODE(e, rest)` (= `gap(e) ++ [e] ++ rest`), `K` a stack of pending sequences, `R` the stack of the entries
appended last. A stored gap is the triple `(hL, hK, hR)` of the live sequence at the time (no copy); a `KNODE`
holds three pendings typed by position (`p4` a stack to splice, `p3` a stack to reverse, `p1` a list), so every
pointer field has one tag and every step allocates at most one cell per tag (five sites per layer: list node, entry,
K node, R node, queue cell). Letter loads are fused into the transitions (a BUILD step consumes the source letter,
a MATCH step the FIFO head; no `have`/`c`), the BUILD→MATCH handover is one step (the live sequence after the build
*is* the reset state `(f_m, g_m)`, which is also a correct start state: for |text| ≥ m the KMP state of `P ++ text`
is the state of `text`), a full match is reported when the FIFO is empty after the consumed letter. Fast paths make
the common pops one step (head of `L`, empty live, a list on top of `K`, a single rear entry, a mismatch at `E_0`).
Withdrawn: a fast path for a rear of two entries (it needs two list nodes in one step; there is one site per tag).
`OverlapPal` banks take a `CoreKind` (`Kmp2Core` default, `GapCore`); the controller, replay and FIFO are unchanged.

Tests (`GapKmpSuite`): interpreter = oracle on all words ≤ 10 at 64/96; the profile is clean on the switch and
structured words ≤ 129; PEG = interpreter with the old backend at 1/1 and 2/3 (≤ 6) and through the code backend with
minAnchor 4 (≤ 10, `checkRules`, `checkReachable`).

### 3.3 Rates (measured with `overlap-profile … gap`; the three word families of §9 of the plan)

The search used all three families from the start (the previous plan's lesson, reversed: the adversarial start-up
family `b a^(n−2) b` is *not* the worst case for this core). With the production minimum anchor W0 = 16 (colours 16,
first machine answer 24):

| rate A/B | adversarial ≤ 600 (788 words) | switch ≤ 1025 (475) | structured ≤ 1025 (122) |
|---|---|---|---|
| 8/11 | clean | 67 late (Thue–Morse palindromes from n = 25) | 17 late |
| 9/12 | clean (≤ 400) | 18 late (B: n = 65, 129; A: n = 193) | 4 late (B: n = 65, 129, 256, 257) |
| 9/13 | — | 1 late (A at n = 193, h = 128) | clean |
| 10/12 | — | B late at n = 65 (h = 48) | — |
| **10/13** | **clean** | **clean** | **clean** |

The late words are Thue–Morse palindromes (`abbabaabbaababba…`), whose border structure nests deeply: a mismatch at
a deep state pops through nested gaps. `GapKmpSuite` keeps the boundary as regression tests (9/13 late at n = 193,
10/12 late at n = 65; 8/11 clean on `b a^46 b` but late on the Thue–Morse palindrome of 48 letters).

Per-letter costs read off the profiles (measured units by phase; e.g. the Thue–Morse palindrome of 64 letters at
64/96): build = h + 1 steps plus 1.2–1.3 nested-pop steps per letter, matching 1 step per letter plus 0.1–0.5
nested-pop steps, the FIFO reversal 1 step per letter that arrived during BUILD. The start-up estimate of
`BREAKTHROUGH.md` with these constants gives A ≈ (2·2.3 + 1.3 + 2.3) ≈ 8.2 and B ≈ (3·2.3 + 2·1.3 + 2.3) ≈ 11.8
steps per tick, against the measured 10/13: the estimate does not capture the Thue–Morse pops.

## 4. Results

| variant | N | rhs nodes | max rhs | bytes | colours / W0 / first answer | tests |
|---|---:|---:|---:|---:|---|---|
| baseline overlap+code 19/28 (`out/overlap-code-a19-b28.peg.gz`) | 8,336 | 431,165 | 617 | 2,502,032 | 31 / 32 / 48 | BREAKTHROUGH.md |
| baseline, inlined nodes ×1.25 | 3,301 | 538,921 | 1,443 | 2,885,277 | — | extents preserved (PegBudgetInline) |
| baseline, inlined nodes ×4, maxRhs 16384 | 2,151 | 1,721,424 | 14,561 | 8,347,898 | — | idem |
| **gap core 10/13** (`out/under1000-gap-a10-b13.peg.gz`, `metrics/under1000-gap-a10-b13.json`) | **5,902** | **208,201** | **204** | **1,180,743** | 16 / 16 / 24 | interpreter: 788 adversarial ≤ 600, 475 switch ≤ 1025, 122 structured ≤ 1025, 60 random ≤ 129 without violation; PEG re-parsed = oracle ≤ 9; Scala matcher on 7 words of 23–64 letters; Rust runner on 152 raw words of 24–601 letters (three families, random palindromes, one-flip words): 0 mismatches |
| gap core 10/13, trie dispatch (`metrics/under1000-gap-a10-b13-trie.json`) | 5,906 | 292,339 | 223 | 1,499,382 | 16 / 16 / 24 | no gain |
| gap core 10/13, inlined nodes ×1.0 | 3,378 | 208,197 | 474 | 1,139,664 | — | extents preserved; re-parsed = oracle ≤ 9 |
| gap core 10/13, inlined nodes ×1.25 (`…-inline-nodes-x1.25-r4096.peg.gz`) | 2,319 | 260,183 | 813 | 1,357,764 | — | idem; Rust runner on the 152 raw words: 0 mismatches |
| gap core 10/13, inlined nodes ×1.5 | 2,086 | 312,263 | 974 | 1,572,831 | — | idem |
| gap core 10/13, inlined nodes ×2 | 1,816 | 416,054 | 1,943 | 2,070,035 | — | idem |
| gap core 10/13, inlined nodes ×3 | 1,561 | 624,182 | 3,432 | 2,883,917 | — | idem |
| gap core 10/13, inlined nodes ×4 (maxRhs 4096) | 1,494 | 831,817 | 4,078 | 3,627,605 | — | idem; Rust runner on the 152 raw words |
| gap core 10/13, inlined nodes ×4, maxRhs 16384 (`out/under1000-gap-a10-b13-inline-nodes-x4.0-r16384.peg.gz`) | 1,492 | 832,771 | 6,086 | 3,614,037 | — | idem; Rust runner on the 152 raw words: 0 mismatches |
| gap core 10/13, inlined bytes ×1.0 | 2,684 | 228,253 | 489 | 1,166,620 | — | idem |

All four exported inlined grammars re-parse and agree with the oracle on all words ≤ 9 (`lps-check-file`; the
re-parsed node counts are a few per cent lower because the renderer's parentheses flatten nested sequences).

Census of the 5,902-rule grammar (measured): register/bit rules 783 (29 per layer × 27 layers: 10 + 13 universal
steps, two IN slots, two controller slots), site field rules 475, conditions `K` 2,514, sub-expressions `X` 2,054,
fallback `Pal_0..23` + `Short` + `Long` 26, helpers 32, controller 20. The recursive core is 783 + 475 = 1,258 rules.

Reproduce: `sbt "run lps-code sizes gap 10 13"`, `sbt "run overlap-profile 10 13 adversarial 600 16 gap" "run
overlap-profile 10 13 switch 1025 16 gap" "run overlap-profile 10 13 structured 1025 16 gap"`, `sbt "run lps-code
optimize gap 10 13 auto out/metrics/under1000-gap-a10-b13.json"`, `sbt "run lps-code export gap 10 13 auto
out/under1000-gap-a10-b13.peg" "run lps-check-file out/under1000-gap-a10-b13.peg 9"`, `sbt "run lps-inline-sweep
out/under1000-gap-a10-b13.peg out/metrics/gap-inline …"`, `python3 probes/runner_words.py 24 600` +
`../runner/target/release/plain-peg-runner out/under1000-gap-a10-b13.peg <words>`.

## 5. Why N < 1,000 is out of reach for this architecture (estimate and bound)

N is a sum over the layers of the schedule: every universal-step copy is a layer with its own rules, because a PEG
rule at a position cannot iterate (a loop at the same position needs distinct names per iteration), so

    N = Σ_layers (registers written + site fields + named sub-expressions) + fallback + helpers + controller.

- **What inlining cannot remove (measured).** The register and site-field rules of a layer refer to the previous
  layer's registers; substituting them multiplies bodies across layers (the plan's symbolic path: N = 999 at 6.8·10²⁴
  nodes). Every measured sweep stops at that core: baseline ≈ 2,150 of 8,336; gap 8/11 ≈ 1,126 of 4,380 (21 layers ×
  46 + 76); gap 10/13 → 1,494 of 5,902 at ×4 nodes (27 layers × 46.6 + 76 = 1,334 is the census bound; the remaining 160 are
  sub-expressions whose inlining would exceed the max-RHS budget).
- **Rate floor (estimate).** With anchors W and 3W/2 (the only two-family schedule; three families cost 12c_b + 9c_r
  + 3c_m against 5c_b + 3c_r + 2c_m), a bank with anchor h and first answer n0 must do h·c_b + (2h−n0)·c_r +
  (n0−h)·c_m steps in n0−h ticks: A needs 2c_b + c_r + c_m, B needs 3c_b + 2c_r + c_m steps per tick. With unit
  costs (one step per letter for building, replaying and matching — not achieved by any known amortised or
  worst-case structure in this IR) the floor is 4 + 6 = 10 copies, i.e. 10 U layers + 2 IN layers + 2 controller
  layers = 14 layers.
- **Per-layer core (measured).** A bank needs at least the registers `cur`, the live sequence (3), the pattern
  source and replay countdown, and the FIFO (3 + a flag): 12 pointer registers → 22 rules, 7 bits, ≈ 17 site
  fields: ≈ 46 rules per layer. (Kmp2: 32 + 14 = 46 as well.)
- **Bound.** 14 layers × 46 = 644 + fallback (3W0/2 ≈ 24) + helpers (2·colours ≈ 30) + controller (20) ≈ 720 —
  *at unit costs and after unlimited inlining*, i.e. with rhs nodes and bytes several times the current ones. The
  measured rates 10/13 give 27 layers → 1,334 at the same limit. To reach 999 with this architecture one would need
  c_b + c_r + c_m ≈ 4 (say 1.5/1/1.5) — a matcher whose *worst-case* build cost per pattern letter is 1.5 steps
  including the failure-structure work, which the range lemma says needs a successor structure; the nested
  representation measured here costs ≥ 2.3 on Thue–Morse patterns, and a worst-case O(1) catenable steque has more
  constructor cases and fields (≥ 2 registers and ≥ 2 sites more per layer) than the whole budget allows.
- **What would change the picture:** a representation in which one PEG rule serves several layers (position-shifted
  or EOF-relative encodings of the layer index — no construction known), or a reduction of PAL to something with
  fewer than 10 letter-operations per tick (the replay alone is 3 per tick across the two banks).

## 6. Other routes of the plan (bounded investigations)

**Endpoint truncation (plan §6).** `PegTruncate.scala` implements the conservative transformer Z/P/D (fresh rules
`Z_r`, `P_r`, `D_r` per rule, `DStar_k` per repetition; multi-character literals split); `PegTruncateSuite` checks
the three specifications at every position on all binary words ≤ 8 (abc ≤ 6) for ten grammars with nullable
choices, mutual recursion, predicates, repetition and positive-prefix failures (90,726 original evaluations,
272,178 Z/P/D comparisons, no mismatch), and on the compiled KMP2 grammar
(`D_S` ends one before `S` on all words ≤ 5 over `ab#`). The transformer terminates on every finite grammar (three
fresh rules per rule, one per `A*`). The step the plan flags as unsolved stands: a *machine* rule that consumes a
pointer's last character through `D` of a recurrent rule `R` needs `D(R)`, whose body needs `D(D(R_old))`, and so
on — `D^k(R)` are pairwise different rules (each truncates one more character), so the family does not close, and
no pointer sublanguage of `LpsCode`'s output has a bounded truncation depth: a register's extent is carried across
arbitrarily many ticks (`r_0 = . &. r_last`). Bounded truncation (`D` applied to a fixed number of layers) is a
usable helper; it does not remove layers.

**Eertree (plan §7).** Not implemented, on the same obstruction as the range lemma: the operation
`extend(descriptor, bit)` must find the node `c v c` from the node `v` (the eertree edge `to[v][c]`), and `c v c` is
created *after* `v`; an immutable descriptor of `v` cannot point to it. Occurrence descriptors created per letter
(no deduplication) need, for their suffix link, the descriptor of the longest proper palindromic suffix of `c v c`,
which is an earlier *occurrence* that was not necessarily the longest palindromic suffix at its time (so no
descriptor exists for it), or a search. Either a search (Kmp2's walks again) or a persistent structure with the
same constants as §3 is required; no cheaper primitive was found.

**Guarded provenance (plan §5, secondary).** Not attempted: the dispatch arms are already small in the gap grammar
(max RHS 204; `dispatch_arm_count` 29,084), and they are not what the inlining floor is made of.

## 7. What is and is not established

```
overlap/coverage lemma:            unchanged from BREAKTHROUGH.md (finite check ≤ 16, schedule ≤ 4096)
abstract matcher correctness:      Appendix A + range lemma, finite (8,190 patterns; 129,794 pairs, every prefix)
data-structure invariant and cost: nested-sequence core; amortised O(1) per letter argued (c·|h_cur| ≤ c·matches since
                                   the last jump), worst case Θ(pattern) measured; not proved for all inputs
per-bank startup/deadline bound:   rates found by measurement on three word families (§4); no all-input proof
compiler endpoint preservation:    PegBudgetInline exact by construction (substitution); LpsCode unchanged
printed-grammar totality:          structural argument of BREAKTHROUGH.md (consuming helpers, same-position references
                                   to earlier sites/lower layers); not machine-checked
finite tests:                      §4; `sbt test` 65 + 2 (PegTruncateSuite) green
Lean verification status:          none of the research grammars; the proof of PAL ∈ PEG in ../proof is untouched
```
