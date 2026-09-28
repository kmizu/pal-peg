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

## 4. Slot-scheduled pointer machines (2026-09-24, DESIGN.md route)

`Lps.scala` compiles a pointer machine with a fixed schedule of slots per tick (one layer of
nonterminals per slot, registers alias to their last writer, pointers carry "site bits" naming
the allocation site (layer, index) among the sites that can allocate their kind). `LpsKmp.scala` is
the DESIGN §2 KMP matcher, `LpsPal.scala` the whole recognizer (controller with dyadic clocks
kept as pointers into the input history, one matcher per stage, one shared flags worker that
builds the forward view, runs the matcher with the full match kept, walks the failure chain into
a gap stack and skips to r·S, and a controller that consumes one flag per tick).

Measured (`run lps-kmp-size`, `run lps-pal-size`; all grammars agree with the interpreter and, at
the working rates, with the oracle; the Rust plain-PEG runner accepts the printed grammars):

| machine | slots/tick | rules | symbols |
|---|---|---|---|
| KMP matcher alone, 2 groups | 21 | 2,037 | 29,581 |
| KMP matcher alone, 3 groups | 31 | 3,213 | 65,693 |
| PAL, matchers 6 groups, flags 16 groups (interpreter correct on all words ≤ 10 and 1,500 random words ≤ 81) | 318 | 62,012 | 7.06M |
| PAL, same, each group fused into one slot (`fusion = 1`, exact sequential composition) | 34 | 53,280 | 7.05M |
| PAL, also the controller slots fused into their neighbours (`fusion = 2`) | 29 | 53,147 | 7.05M |

What the numbers say:

- The working rates are far above DESIGN's 41 local steps per input: a "group" of ten micro-slots
  processes one letter and two back-up steps, and the flags job of size 4S must finish within S
  ticks, so 16 groups per tick were needed (6 for the matchers, whose 16-letter table build took 8
  ticks at 3 groups).
- Fusing slots does not shrink the grammar: the rules are the distinct expression nodes
  (dereferences `X`, conditions `K`), and fusion keeps every node of every micro-step. The
  per-layer fixed cost (register aliases, phase tests) was small.
- Hence the size is set by the micro-step decomposition itself: sub-phase counters, `at(phase,
  sub)` guards, one allocation per slot, queue reversals as separate steps. A local step in
  DESIGN's sense (fixed number of records, fixed-depth reads, one guard set) has to be written as
  one slot with several allocation sites (now supported: `E.MadeAt`, `Lpm.fuse`, site bits) —
  that is the remaining work, not more compiler optimisation.

Pitfalls fixed on the way (see the commits): a dereferenced field's predicates consumed the base
pointer's extent; `Prog` branches were not exclusive (first-wins is per register); the node after
the last letter evaluated a phantom tick; shared cell tags made every pointer dispatch over all
modules' layers; fused expressions are DAGs, so hashes are cached and evaluation, substitution and
use-counting are memoised.

## 5. Universal local steps (2026-09-24, later)

`LpsKmp2.scala` / `LpsPal2.scala`: every worker is `n` copies per tick of one **universal step**
`U` that performs exactly one local step in DESIGN's sense (a back-up unit, one archive-reversal
step, one queue-reversal step, one letter load, or one letter processed with its entry allocated,
enqueued and compared in the same step), written as one exclusive guard set; each kind of cell has
one allocation site per `U` (`Prog.site`, fields selected by the guards), so a pointer's site bits
only say which copy of `U` allocated it. The flags worker's own phases (forward view, chain, skip)
are further branches of the same step.

| machine | slots/tick | rules | symbols | correctness |
|---|---|---|---|---|
| KMP matcher, `U` × 4 | 5 | 1,022 | 18,391 | all words ≤ 8 (interpreter), ≤ 6 (PEG vs oracle) |
| KMP matcher, `U` × 6 | 7 | 1,735 | 45,363 | all words ≤ 8 |
| PAL2, matchers 6 / flags 32 | 50 | 22,229 | 1.96M | fails at |w| = 7 (the S = 1 job of size 4 in one tick) |
| PAL2, matchers 8 / flags 40 | 62 | 30,059 | 3.77M | all words ≤ 10; fails on a 64-letter word (the W = 32 matcher is late) |
| PAL2, matchers 12 / flags 40 (`out/pal2-m12-f40.peg.gz`) | 70 | 33,019 | 3.98M | all words ≤ 10; 500 random words ≤ 121 and 100 ≤ 513; PEG = oracle ≤ 5; Rust runner correct on samples up to 161 letters |

Cost structure at 12 / 40: a matcher `U` is about 215 rules, a flags `U` about 560, of which about
310 are site-bit rules: with 40 copies every pointer to a flags cell carries 6 bits and every
dereference dispatches 40 ways. The flags rate itself is DESIGN's bound (≈ 31 local steps per input for the
job of size 4S with deadline S, plus the overhead of the S = 1 and S = 2 stages).

What would move the number further:

- the two smallest stages (W = 2, 4) could be replaced by a finite check of |w| < 16 in the
  controller (seven stored letters compared against fixed-depth history reads), removing the
  small-S overhead that forces 40 rather than 32 flag steps;
- fewer dereferences per flags step, or a cheaper encoding of "which copy allocated this cell"
  than log₂(copies) bit rules per pointer — the dominant term now;
- a flags job that reuses work across the four jobs of a stage (the table of y_r is a prefix of
  the table of y_{r+1}; only the matching against y^R restarts), which would cut the rate itself.

## 6. The compression plan (2026-09-25)

Executed from the plan `pal-peg-compression-claude-code-aecb05e-2026-09-24.md` (Tasks 0–5; Task 6 not
run, see below). Every row is measured (`out/metrics/*.json`); "compact" is after `PegOptimize`.

| step | grammar | N | rhs nodes | max rhs | bytes |
|---|---|---|---|---|---|
| before (aecb05e) | PAL2 12/40 | 33,019 | 3,976,769 | — | — |
| Task 0: semantics (exclusive Ite masks, no phantom tick on ε) | PAL2 12/40 raw | 33,019 | 4,098,717 | 1,222 | 25.5M |
| Task 1: reachability, identities, aliases, identical rhs | PAL2 12/40 compact | 29,408 | 4,056,061 | 1,222 | 25.2M |
| (Task 1, single-use inlining — not adopted) | PAL2 12/40 | 20,958 | 4,045,899 | 7,964 | 24.9M |
| Task 2: static letter/separator kinds, same-slot fields | PAL2 12/40 compact | 28,701 | 3,944,094 | 1,222 | 24.5M |
| Task 3: flags truncated to the needed interval (PAL3) | PAL3 12/40 compact | 27,550 | 3,614,066 | 1,222 | 22.3M |
| Task 3/4: the rate the truncation buys (deadline-checked) | PAL3 12/36 compact | 25,510 | 3,039,750 | 1,102 | 18.7M |
| Task 5: decision tries for site dispatch | **PAL3 12/36 compact** | **25,510** | **2,361,254** | **894** | **12.4M** |

Correctness of the final grammar: `LpsCompilerSuite`, `PegOptimizeSuite` (extents of every mapped rule),
`FlagIntervalSuite` (the truncated iterator against the oracle, |y| ≤ 12; the plan's Python model up to
15), `LpsDeadlineSuite` (no deadline violation, no overwritten request, matcher reports on time, on the
122 structured words ≤ 129); interpreter vs oracle on all words ≤ 10 and 100 random words ≤ 513; PEG vs
interpreter on all words ≤ 6 (1/1); PEG vs oracle on all words ≤ 5 at 12/36; the printed grammar
re-parsed and checked on all words ≤ 9; the Rust runner on raw inputs up to 600 letters.

Rates: matchers 12 (8 failed on a 64-letter word), flags 36 (34: deadline violations on structured words
and a failing random word; 32: a 113-letter word; 28: a 60-letter word; 24: |w| = 7).

Two compiler bugs surfaced by the plan's review items, both invisible to the exhaustive machine tests:
a selected null fell through to the next alternative of a flattened conditional (fixed with exclusive
masks), and the input kind of a field read through a cell was dropped (a letter compared as "never a
letter"; caught by the PEG-vs-oracle check at the working rate, which the 1/1 check does not exercise).

Where the 25,510 rules are (compact PAL3 12/36): site-bit rules ≈ 13,000, conditions ≈ 7,000,
dereferences ≈ 1,600, fields ≈ 600, registers/bits ≈ 2,000. The flags step is ≈ 500 rules × 36 copies.

Task 6 was not run. From the measured job profile (r = 3, S = 4: forward view 20, table 56, archive
reversal 18, matching 44 units, i.e. ≈ 5S, 14S, 4.5S, 11S) and per-phase step costs of roughly
60/250/60/250 rules, the plan's continuous estimate for phase-specific service, (Σ√(aᵢcᵢ))² ≈ 21k rules,
does not beat the universal step (36 × ~500 ≈ 18k), because a job's phases are sequential (6A).
Incremental tables (6B) would cut the r = 3 job's table work from ≈ 14S to ≈ 4S and the flags rate from
36 toward ≈ 26, about −5k rules, at the price of checkpoint registers: **that is the next cost item.**


**Correction (2026-09-25, evening).** The 12/36 rate of §6 is invalid: on the adversarial words
`b a^(n−2) b` and `a^i b a^j b a^i` (added to `LpsProfile.adversarialWords` after the final review of the
breakthrough branch) PAL3 12/36 misses a flags deadline (`a^94 b a^266 b a^94`: job (stage 1, r 3) released at
192 finishes at 227, deadline 224) and answers correctly only by reading a stale result of an earlier stage
generation; it also accepts the non-palindrome `a^129 b a^94`. PAL3 needs 12/40 (495 adversarial words
without violation), i.e. the 27,550-rule grammar of the 12/40 row, not the 25,510-rule one. The 12/36 grammar
was removed from `out/`.

## 7. EOF-coded sites and overlapping anchors (2026-09-25)

See [`BREAKTHROUGH.md`](BREAKTHROUGH.md) for the design, the checks and the final table. In one line each:

| variant | N | rhs nodes | max rhs | bytes |
|---|---:|---:|---:|---:|
| PAL3 12/36 (invalid, see the correction above) | 25,510 | 2,361,254 | 894 | 12,410,391 |
| PAL3 12/40, old backend | 27,550 | 3,614,066 | 1,222 | 22.3M |
| PAL3 12/40 + warmup (W0 = 64) + site codes | 15,967 | 861,036 | 881 | 4,762,519 |
| overlapping anchors (19/28), old site-bit backend | 14,955 | 1,256,793 | 694 | 6,969,322 |
| **overlapping anchors (19/28) + site codes (W0 = 32)** | **8,336** | **431,165** | **617** | **2,502,032** |

(A first version of this table with rates 15/24 — 6,960 rules — was withdrawn: those rates miss the first
answer of a bank on `b a^(n−2) b`; see `BREAKTHROUGH.md` and `out/metrics/overlap-rate-search.md`.)

The two changes are independent and multiply: the site code removes the log₂(sites) predicate rules per
pointer (and the 40-way dispatches), the overlapping anchors remove the flags worker (72% of the baseline)
and its consumer. The remaining grammar is the two banks' universal steps × their copies (19 + 28) plus
1,685 code rules, 62 helpers and 50 fallback rules.

## 8. Under 1,000 rules: the gap matcher and the budgeted inliner (2026-09-25)

See `UNDER1000.md`: the target was not reached; the best grammars are 5,902 rules (gap core 10/13, `out/under1000-gap-a10-b13.peg.gz`) and its inlined variants (`out/metrics/gap-inline/`). The obstruction is the product of the rate floor of the two-anchor architecture (≥ 10 universal-step copies at unit costs, 23 measured) and the ≈ 46 register/site rules per layer that no inlining removes.

## 9. Role pools: one builder, one answerer (2026-09-25)

| grammar | N compact | N inlined | rhs nodes (inlined) | bytes (inlined) |
|---|---:|---:|---:|---:|
| gap 10/13 (UNDER1000.md; wrong on a palindrome of 128 letters) | 5,902 | 1,492 (×4) | 832,771 | 3.6 MB |
| gap 10/14 (corrected status quo) | 6,177 | — | — | — |
| role 15/4 A (`POOLS.md`) | 4,216 | 1,377 (×2) / 1,120 (×4) / **999 (×9.75)** | 1,945,495 | 8.0 MB |

The young bank (building, replaying) always lives in `y_*`, the answering bank in `o_*`, with a handover at every birth, so
the schedule carries 15 young + 4 old copies instead of 10 + 14 per-bank ones; a compact state encoding, a dead young `acc`,
the first steps fused into IN and the end of the build confined to the first young step of a tick cut the young layer from
50 to 42 core rules. The rates (15/4) are measured on 3,385 + 3,810 + 6,385 words (three seeds) at W0 32; young 14 and old 3 have counterexamples.
N < 1,000 is reached only at a large node budget (×9.75, 8 MB); at the preferred ×2–×3 it is 1,377–1,194. Not a Lean proof.

## 10. What the 999 rules are made of (2026-09-26, `STRATEGY.md`)

After inlining, the survivors are the registers and site fields of every layer and the shared sub-expressions: a register
is read through a dispatch over every site of its domain, so its fan-in is its field reads times the number of young
layers. The self-recursive code helpers (`Code_c`) could never be inlined; a star form with the same extent removes 15–20
rules from ×4 on (999 at ×8.28, 6.75 MB). A step-kind profile shows the young bank spends 51–65 % of its steps on the
pattern build (0.9–1.2 pops per pattern letter) and that a two-letter match would save only 4.5–10.5 % — rejected. Next:
size the build pops (E2), then balance the anchors on the model (Task 3).


## 11. Balanced anchors, and a wrong answer found (2026-09-26, `STRATEGY.md` §3b)

The anchors of family B moved from 3W/2 to 23W/16 by a staged clock in the controller (β = 7/16): the young windows then
need 4.57 and 5.11 letters per tick instead of 4 and 6, and the young pool falls from 15 to 13 on the model and the role
machine. Fewer young layers shrink every register's dispatch domain too, so the compact grammar loses 356 rules and the
curve 87–111 at ×2–×4. A new every-length family (the runner's words at every length) then showed that the POOLS machine
— and both 999-rule grammars made from it — answer false on a 79-letter palindrome: `endonce` loses a tick of the first B
life's 16-tick window. The replacement (13/4, β 7/16, W₀ 64, which keeps `endonce` safe) passes three seeds and every length
≤ 2,049: 999 rules at 2.98 MB, ×4 1,018. `unfold` (a mismatch unfolds its own gap) is implemented but its gains (old 3,
young 12) do not survive the every-length family.

## 12. Other angles (2026-09-27, `STRATEGY.md` §3c)

Three angles moved the curve: `tmp` (rv/ks merged), free now that W₀ is 64; W₀ 128, which puts the short early lives — every
failure so far — into the bounded fallback, so that 12/4 with `unfold` passes every test of §3b; and naming common
sub-expressions after inlining (`PegCse`), which the remove-only inliner cannot do. Together: 998 rules at 531,531 rhs nodes,
2.28 MB (§3b: 724,780, 2.98 MB). Two did not: a look-ahead priority for the inliner (worse at every λ) and the `trie` dispatch
(better per multiple, worse in absolute nodes). The first `tmp` grammars referenced undefined rules — a register domain with
two tags got dispatch arms for always-null fields — which only the Rust runner noticed; the compiler no longer emits those arms
and a test checks that every referenced rule is defined.

## 13. The fallback, naming in the loop, the old pool (2026-09-27, `STRATEGY.md` §3d)

The bounded fallback was the cheapest lever left: `Pal_k` references `Pal_{k-2}` twice, so its chain cannot be inlined and
42 of its rules survived at W₀ 128. Checking mirrored pairs with EOF-anchored skips (`Mir_j`: the letter at j against the letter
followed by exactly j letters) needs one reference per step; the chain inlines completely into the start rule. With it the
grammar goes below 1,000 rules at ×2.7 (472,260 nodes, 2.03 MB), inside the band the handoff prefers. Taking the naming
threshold from the greedy run (the price of the next elimination) named too little and ended no lower: a naming also cheapens
the later elimination of the rules around it. Old 3 passes only with young 13 at W₀ 256, which costs more than it saves; young 12
with old 3 fails at the first B life whatever W₀.

## 14. Seven ideas for a dramatic cut (2026-09-27, `STRATEGY.md` §3e)

A young layer is worth about a quarter of the nodes at 999 rules, so the ideas aimed at fewer layers or cheaper layers. Two steps
per slot copied the first step's register expressions into the second (worse); colours by use and a larger RHS cap changed
nothing. Classifying the young steps showed reversal chains and the unfolding of GNODEs whose gap is empty; an entry with an empty
gap now gets an LNODE (`lgap`, free). Merging a pop with the pop that always follows it saves a layer but adds nested reads, and a
read costs a dispatch over every site its pointer may reach, so the merges cost about what the layer is worth. With balanced
anchors (β 13/32) young 11 needs only the cheapest merge: 994 rules at 438,487 nodes (×2.56), 1.91 MB, about 7 % below §3d.
Not dramatic: the per-layer cost of branching, not the number of steps, is the limit now. A literal `False` guard used to be
compiled into rules; options now add branches only when they are on.

## 15. The branching of one read (2026-09-27, `STRATEGY.md` §3f)

Half of the compact grammar is dispatch arms, so the arms were counted by what they do. Two kinds were waste the compiler
itself made: a tag test inside every field read of a two-tag site, `&(!g0 / g0)`, always true but keeping the tag-bit rules
and their guards alive; and arms from young reads to the old role's sites, because domains came from tags although a young
register, reset at every birth, never holds an old cell. `sound` drops the first (the colour picks a site of the pointer's
domain and kinds are checked after every slot), points-to domains (`PointsTo`, an Andersen-style fixpoint) the second. The
machine does not change, so the rates stand: 996 rules at 349,849 nodes (×2.45), 1.54 MB, 20 % below §3e. What remains is one
arm per young layer, and it is inherent: `alloc-profile` finds ticks in which every site of a tag keeps a cell, so no
re-addressing of the cells branches less; only a machine that caps the cells kept per tick could.

## 16. Capping the cells of a tick (2026-09-27, `STRATEGY.md` §3g)

Capping the cells a tick may keep was the structural step §3f pointed at. Naming cells by their rank within the tick costs a
bus per (rank, site, field) that eats what the reads save; fixing which young layers may allocate needs no bus and was built
(`noAlloc`, `ypat=`), with a test that an N layer stalls exactly where the full step would store a cell. Every pattern fails
the first answer: the gap matcher allocates in 77 % of its working steps — builds, the unfolds and reversals of its pops, the
FIFO — because its structures are persistent, so eleven allocating young steps are the floor. W₀ 64 would save 5.7 % but is
late on a 104-letter palindrome; young 10 with every merge passes but is larger. The next real cut needs a matcher whose pops
do not allocate.

## 17. Pops that store no cell (2026-09-27, `STRATEGY.md` §3h)

A third of the young role's busy steps are builds and a third are pops that store a cell (reversals of the rear, unfold frames).
A what-if machine whose N slots may run every pop showed the prize first: the cap would then pass with 8 allocating young layers
of 13, about 15 % of the arms. And the pops cannot be freed in this model: cells point only to older cells and registers are
bounded, so consuming the build-ordered rear oldest-first needs a cell per entry (or quadratic re-walks), and nested unfolds need
a frame per level. The young role's branching is bound by the matcher's representation; §3f stays the best.

## 18. A matcher that only walks toward older data (2026-09-27, `STRATEGY.md` §3i)

Searched: KMP, the palindromic tree, online Manacher and fingerprints each need a step to newer data (a failure jump, the start of a
shrunken palindromic suffix, a moved left end) or are inexact. The reason is shared: verifying an alignment walks text and pattern
the same way, and overlapping candidates need a re-read or a jump back; a mirror maps older positions to newer ones. What the search
changed is the target: steps to newer data cost cells, and cells are cheap when born at fixed sites; the branching of a read is the
number of sites. For anchors the floor is about 1/β ≈ 2.5 entries a tick if the build and the match were pipelined, against 11
allocating layers now. No such design yet.

## 19. Where the headroom of sites really is (2026-09-28, `STRATEGY.md` §3j)

Measured before designing: 82 % of the arms read the live sequence (list, K, R nodes and tag tests), 16 % entries. Per tick the
young role makes on average 5.6 list, 1.8 K, 1.9 R, 3.0 entry and 3.4 queue cells, so sites at the means would leave about a third
of the arms; but every kind hits all eleven steps at the 95th percentile, cascades run up to 508 consecutive list-making steps, and
84 % of busy ticks are saturated. The headroom needs a matcher whose runs are bounded — a pipelined, de-amortised young role —
which does not exist yet; the burst table is its specification.

## 20. Towards a de-amortised young role (2026-09-28, `STRATEGY.md` §3k)

A de-amortised young role exists in principle: the KMP automaton built by running it on the pattern (one transition per letter)
plus real-time catenable deques for the forward sequences of entries. The first condition was measured per young life: entries are
2.44 a tick, as 1/β predicted, but the build side needs up to 6.48 busy steps a tick and the match side 7.30, so separate builder
and matcher slots would need about 15 layers against the 11 the shared role uses; the two sides peak at different times and share
slots today. Pipelining pays only if the work per letter drops to about one step on each side, deque operations included — the
open question. Queuing each pending bank's text at IN (taking the FIFO reversal out of the young role) is a smaller separate lever.
The arms go as r L² (layers L, reads per layer r); one fat step per letter would need about 5 young layers, which pays
if r grows less than about 4.8 times.

## 21. The de-amortised young role, built and measured (2026-09-28, `STRATEGY.md` §3l)

`RtSteque` (Kaplan–Tarjan real-time catenable steques, JACM 1999 §5, with the stack-of-substacks pointers) and `RtKmp` (the
KMP automaton built on the pattern, one transition per text letter) give a young role without amortisation: at most 5 steque
operations per builder letter, 3 per matcher letter, and a bounded number of cells for each (68 and 41 at most, unchanged from
length 64 to 2,048 and on the banks up to length 1,000). It is correct on every bank of the families (3,002 banks to length 400,
21,267 to 1,000; three builder and three matcher letters a young tick are enough, two are not). It costs about 77 cells a young
tick (199 at most, up to 77 of one kind) against today's 16 (11 of a kind at most): the bursts are gone because every letter pays
the worst case. So the forward sequences cannot be kept for well under a step per transition; the table is what costs.

Why a table forces this. With cells that point only to older cells, the per-position data of the automaton (built in index
order) cannot link forward; a matcher at state j needs j + 1 next and other(j) after a mismatch. A static layout fails on the
cycles: a list built in reverse gives "next" but not the jump; a list in index order gives the jump but not "next"; carrying a
handle of each kind loses the other after one move of the other kind, and alternating generations only postpone the loss by one
jump each. The matcher must carry the forward order itself, and a jump prepends a stored range — catenation.

The way out is a matcher without per-position data (Crochemore–Perrin two-way, real time by Breslauer–Grossi–Mignosi): the
pattern is read forward along the input chain for free, the text as it arrives. Its preprocessing needs position arithmetic
(the machine has no pointer equality; a counter chain of one cell per step replaces it), and its real-time form needs the
two-instance construction for patterns whose left part is the longer one (the plain recursion on the left part grows: depth 3,
4, 5 at lengths 10, 14, 18). Both are open.

## 22. A matcher without a table (2026-09-28, `STRATEGY.md` §3m)

`TwoWay` is the young role the §3j table asks for. Crochemore–Perrin two-way matching in Breslauer–Grossi–Mignosi's real-time
form: forward scan of the right part and a parallel backward check of the left part, one comparison a letter each; when the left
part is the longer, a second instance on a prefix X' (critically factorized in its first half) finds X' in real time, and the two
are coupled by fixed countdowns. The pattern is read along the input chain (forward) and a list N (backward); resets go to fixed
cells and lengths are countdowns to the end of the input chain, so matching stores no cell and needs no pointer equality.
Preprocessing: one maximal-suffix pass per order gives every prefix's maximal suffix; a list R keeps every prefix's critical
factorization; the chain of factorizations follows R, needing no search while the left part is the longer (then the local period
overflows both sides and is a period of X), and one search at the end. Measured on 21,267 banks up to length 1,000: correct,
never late, 19 steps a young tick, 6.2 cells a young tick (N, R, replay: fixed rates), at most 3 comparisons a text letter, at
most 6.1 preprocessing steps a pattern letter (linear: at most 8, tested to length 3,000). Not compiled yet.

Pitfalls met on the way: the backward check must count lateness only while it still agrees (a failed check stops early); when
X' = X, the second instance reports on the same letter as the first finishes, so the coupling must look at B's report before A's
new candidate replaces the flag; the chain of factorizations walked by searching made one search a step (up to 6 at length 1,000)
and made the preprocessing grow with the length until the chain was read from R.

## 23. The table-free matcher in the machine's terms (2026-09-28, `STRATEGY.md` §3n)

Checked before writing §3m as an Lpm core. Input-cell reads compile to a constant expression (no site dispatch), so §3m's
assumption holds. Matching needs no position comparison. The maximal-suffix loop's block counter is a countdown whose start a
pointer walking N supplies (N complete first). Every restart of the loop happens at the scan's frontier, never inside a rescan
(366,956 restarts over all binary words up to length 16), so a single countdown per restart tracks the frontier. The open part:
the critical position is the later of two maximal suffixes — a position comparison the machine lacks (a race costs O(n)); both the
per-prefix record and the chain of §3m need it. A counter-free loop is quadratic ((ab)^m bbaa, about m/2 steps a letter).
Later the same day: at every prefix, the later maximal suffix is the one whose start changed most recently (all prefixes of all
binary words up to 16 and of 448 long words; no exception, not proved) — so the critical position needs no comparison. Still open:
lookups by position into the per-prefix record (for the chain and for the search's period).

## 24. No chain, no lookup (2026-09-28, `STRATEGY.md` §3o)

The chain of §3m is replaced by one jump: with c(K) the prefixes' critical start (non-decreasing) and m* the last K with c(K) ≤ n/2,
ℓ_A = c(m*+1) is critical for X (above the midpoint the local period overflows both sides) and c(ℓ_A) is below it; one search
finishes the plan. In the machine: lists N and N' (N' nodes point into N and carry a midpoint tag), the passes walk N', the crossing
is the tag flip, two reruns of the passes read c(ℓ_A) and u's parameters. 21,267 banks to length 1,000: correct, never late, 33
steps and 6.2 cells a young tick, at most one change of factorization, preprocessing at most 11.8 steps a pattern letter.
