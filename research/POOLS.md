# One builder, one answerer: role pools for the PAL PEG (2026-09-25)

Executed from `inst.md` (the pools plan) on top of main `0a2d58f` (the gap core of `UNDER1000.md`). Everything here is
an experiment on printed grammars and on the interpreter: **not a Lean proof; the theorem in `../proof` is about the
automaton.** Numbers are labelled *measured* (exported grammar, interpreter or profiler), *estimate* (an explicit
cost sum) or *target*.

## 0. A correction to UNDER1000.md first

**The 10/13 grammar is wrong on a palindrome of 128 letters (measured).** Task 0 of this plan adds a fourth word
family, random palindromes and near-palindromes (`OverlapProfile.randomWords`, seed 21). On the 87th of 200 words
≤ 513 letters, a palindrome of 128 letters, bank B (anchor h = 96, first answer 128) is still behind at its first
answering tick and reports false; the interpreter answers false, and both merged grammars
(`out/under1000-gap-a10-b13.peg.gz`, 5,902 rules, and its inlined 1,492-rule variant) answer **false** in the Rust
runner. `PoolsSuite` keeps the word. The previous validation used 60 random words ≤ 129, which never reached this
case. With B = 14 the machine passes all four families (random 2,000 words ≤ 1,025, adversarial ≤ 600,
switch ≤ 1,025, structured ≤ 1,025; 3,385 words): the corrected status quo is **10/14, 6,177 rules compact**
(measured; colours 17, so W₀ = 32 and the first machine answer moves from 24 to 48).

Lesson kept for every rate below: the random family is part of every rate search, with thousands of words and
lengths up to 1,025 (and a fresh seed up to 2,049 for the final candidate).

## 1. Step demands (Task 0, measured)

`OverlapProfile` counts, per tick and bank, the index of the last universal-step copy that changed any of the bank's
registers (copies are deterministic, so after the first no-op every later copy is a no-op). A bank is young before
its first answering tick `start(h)`, old from then on.

| family (10/13, minAnchor 16) | young A | young B | old (max) | old at the first answering tick |
|---|---:|---:|---:|---:|
| adversarial ≤ 600 (788 words) | 10 | 13 | 13 | 13 |
| switch ≤ 1,025 (475) | 10 | 13 | 13 | 13 |
| structured ≤ 1,025 (122) | 10 | 13 | 13 | 13 |
| random 200 ≤ 513 | 10 | 13 | 13 | 13 |

The young bank builds from a source that is complete at its birth, so it uses every step it gets (at 40/40 the
young demand is 40, too). The old demand is 1–2 on most ticks (e.g. adversarial: 178,353 ticks at 1, 1,434 at 2) with
tails at 10 and 13: the young bank hands over a backlog (up to 178 queued letters) and a pop in progress.

**Gate 0:** k_o = 13 > 6, so Task 6 (a lazy answerer with a small old pool) came first. The premise of inst §2 — that
the young bank's FIFO holds at most this tick's letter when it becomes old — is false (measured backlog ≤ 178).

## 2. The old-limit model and the role machine (Tasks 6 and 1)

**Model.** `OverlapPal.machine(a, b, W₀, GapCore, oldLimit = O)` runs only the first O copies of a bank's universal
step while the bank is old (young ⇔ `clk` is armed for family A, not armed for family B). With a = b = Y this is
exactly the step budget of the role machine (young pool Y, old pool O) on the per-bank registers, so rates can be
searched without building the role machine first. `pool-grid` profiles the four families in parallel with a
fail-fast stop; `strict` adds the handover condition (a bank must have left BUILD/REV by the end of its young window).

**Role machine** (`RolePal.scala`, inst §2 and Appendix B). Registers `y_*` (young: the full gap core, pattern source,
replay countdown, FIFO) and `o_*` (old: the match-only gap core and FIFO), one tag set; schedule
`CT, IN, YU × ky, OU × ko, OUT`; `IN` = handover (`o := y` on a birth) then the young reset/push then the old push,
composed with `Lpm.fuse`; one shared FIFO allocation site per role in `IN`; `OUT` answers `o_acc`.
`GapKmp.Core(matchOnly)` drops BUILD/BPOP/REV and the entry/rear sites; the default core is unchanged (the exported
10/13 grammar keeps its sha256, `RolePalSuite`).

Tests (`RolePalSuite`, measured): interpreter = expected outputs on all words ≤ 10 at 16/8; RolePal(14, 4, 16) has the
same outputs as the model OverlapPal(14, 14, 16, oldLimit 4) tick by tick on 666 words of the four families, and the
role profile (strict, handover copies exact) is clean; PEG = interpreter through `LpsCode` at 1/1 with minAnchor 4 on
all words ≤ 10 (`checkRules`, `checkReachable`); the warm-up boundary at W₀ = 16.

## 3. Rates (measured with `pool-grid`, minAnchor 16)

| young / old | random 400 ≤ 513 + 3 families (1,785 words) | random 2,000 ≤ 1,025 + 3 families (3,385 words) |
|---|---|---|
| 13 / 3 | 42 of 400 random words fail | — |
| 14 / 3 | FAIL (n 376) | — |
| 14 / 4 | PASS | FAIL (random palindrome, n 65) |
| 14 / 6, 14 / 8 | PASS | FAIL (random palindrome, n 262, h 192) |
| 15 / 3 | PASS | FAIL (n 671, h 384) |
| 16 / 4, 16 / 5 | PASS | PASS |
| 18 / 4 | PASS | PASS |
| 17 / 2, 18 / 2 | FAIL (n 376) | — |
| 15 / 4, 15 / 5, 15 / 6 | — | PASS |
| 16 / 3, 17 / 3 | — | PASS |

A fresh seed (7) with 3,000 random words up to 2,049 letters plus the three families up to 200 (3,810 words) passes
16 / 4, 16 / 5 and 18 / 4. The smallest pools that pass the big set are 15 + 4 and 16 + 3 (19 copies each); 15 / 4 is
the lighter one (a young layer costs about 11 core rules more than an old one, §4). Two observations:

- **Young 14 fails whatever the old pool** (14 / 6 and 14 / 8 fail at the same palindrome of 262 letters, h = 192): the
  young bank of family B hands over so large a backlog that the old bank cannot catch up before the next palindrome
  end. The unlimited machine needs B = 14 (10/14), the pooled one B = 15.
- **An old pool of 3–4 steps works once the young bank is fast enough**, although the old bank is behind on long
  stretches (up to 128 consecutive old ticks with letters queued): being behind is harmless while no full match is
  possible, and after a mismatch the next full match is at least as far away as the pops it costs (the KMP potential).
  This is a finite observation, not a proof.

**Configuration A** (`r15/4:noacc,fuse,compact,endent,endonce`, §4.2): big set at minAnchor 16 PASS for 15/4, 16/3 and 16/4;
young 14 fails (n 262) and old 3 fails (n 671) as in the model.

**The rate search must use the production W₀.** A fresh seed (7) at minAnchor 16 made A fail at n = 42 (bank B with
h = 24): with `endonce` a young bank of a short window loses up to one tick of its 8. That word is irrelevant to the
grammar: with 21 colours W₀ = 32, so the first anchor is 32 and the fallback answers every n < 48. A life's work depends
only on the input, so a word of length ≥ 48 behaves identically at minAnchor 16 and 32. At minAnchor 32, A passes the
fresh seed (3,810 words, random up to 2,049 letters) and the non-`endonce` variant too, and a third seed (11) with
5,000 random words up to 2,049 letters plus adversarial ≤ 600, switch ≤ 1,025, structured ≤ 1,025 (6,385 words; 16/4 as well).

**A base-3 clock (W₀ = 24) is not used.** Starting the doubling clock at tick 3 gives anchors 24, 36, 48, 72, …, so 21
colours would need only W₀ = 24 (a fallback of 36 instead of 48 lengths). A passes the big set at minAnchor 24, but fails
the fresh seed at n = 197 (bank B, h = 144): a different set of anchors meets different words at the edge. After inlining
the fallback is nearly free anyway (§4.3: 4 fallback rules survive), so the base-3 clock is kept as an option only.

## 4. Results

### 4.1 Gate 1: the role machine at 16 / 4 (measured)

| grammar | N compact | ×1.25 | ×2 | ×3 | ×4 (maxRhs 16,384) | colours / W₀ / first answer |
|---|---:|---:|---:|---:|---:|---|
| gap 10/13 (UNDER1000.md; **wrong on a random palindrome**, §0) | 5,902 | 2,319 | 1,816 | 1,561 | 1,492 | 16 / 16 / 24 |
| gap 10/14 (the corrected status quo) | 6,177 | | | | | 17 / 32 / 48 |
| role 16/4 (`pools-role-y16-o4`) | 4,987 | 1,972 | 1,549 | 1,344 | **1,280** | 22 / 32 / 48 |

rhs nodes of role 16/4: 241,021 compact, 301,060 (×1.25), 481,585 (×2), 963,368 (×4); bytes 1,325,770 compact,
4,068,795 at ×4 (`out/metrics/pools-role-y16-o4-inline/`). The export re-parses and equals the oracle on all words ≤ 9.

Inlined N = 1,280 > 1,150: **Gate 1 says stop and compare the censuses layer by layer** (`probes/census.py`):

| layer | gap 10/13: core / K / X / total | role 16/4: core / K / X / total |
|---|---|---|
| 0 (the previous tick's registers) | 67 / 0 / 0 / 67 | 66 / 0 / 0 / 66 |
| CT | 7 / 10 / 7 / 24 | 8 / 10 / 7 / 25 |
| IN | 31 / 9 / 0 / 40 and 29 / 9 / 0 / 38 (M0IN, M1IN) | 57 / 26 / 6 / 89 (handover included) |
| one universal step | 49–50 / 108 / 89 / 246–247 (× 23) | young 50 / 108 / 89 / 247 (× 16); old 39 / 82 / 67–73 / 188–194 (× 4) |
| OUT | 1 / 2 / 0 / 3 | 1 / 1 / 0 / 2 |
| fallback + helpers | 26 + 32 | 50 + 44 |

A young layer is exactly the old universal step (core 50: pointer registers `cur, L, K, R, rv, ks, end, pendF, pendB,
pendN` at 2 rules each, `src`, `rc` at 1, bits `acc, p0–p2, s0, s1, rev`, and 21 site fields); an old layer has no
`end`, `src`, `rc` writes and no entry/rear sites (core 39). The inlined result sits about 100 rules above the core
floor (1,182 = 66 + 8 + 57 + 16 × 50 + 4 × 39 + 1 + 50 + 44).

Why inst §1's estimate (≈ 1,070 after inlining for 13/3) missed: the pools needed are 16/4 or 15/4, not 13/3
(+3 young layers ≈ +150, +1 old ≈ +39); the per-layer core is 50, not 46 (+4 × 20); and the IQC domain of the old
FIFO (2 IN sites + every young and old layer) gives 22 colours, so W₀ = 32 (+24 fallback, +12 helpers) — risk 1 of the
execution plan.

### 4.2 The per-layer diet at 15/4 (Tasks 2 and 4, measured one by one)

The core floor below is the sum of the per-layer cores (registers, bits, site fields) of the census plus fallback and
helpers; the inliner cannot go below it (the K and X rules are what it removes).

| 15/4 with | N compact | colours / W₀ | layer 0 | IN | young layer | old layer | floor |
|---|---:|---|---:|---:|---:|---:|---:|
| (nothing) | 4,738 | 21 / 32 | | | | | |
| `noacc` (the young `acc` is never read: the old push clears it on the handover tick) | 4,688 | 21 / 32 | | | | | |
| `compact` (states in `q` bits: young 3, old 2; no REV, no IDLE — unborn = null pointers) | 4,457 | 21 / 32 | | | | | |
| `noacc compact` | 4,407 | 21 / 32 | 60 | 52 | 47 × 15 | 36 × 4 | 1,062 |
| `noacc fuse compact` (the first young and old steps composed into IN, Task 2) | 4,407 | 21 / 32 | 60 | 88 | 47 × 14 | 36 × 3 | 1,015 |
| + `endent` (END allocated as an entry: no tag bit) | 4,392 | 21 / 32 | 60 | 87 | 46 × 14 | 36 × 3 | 1,000 |
| + `endonce` (only the first young step of a tick ends a build) | **4,216** | 21 / 32 | 60 | 87 | **42** × 14 | 36 × 3 | **944** |
| `tmp` (rv/ks merged, Task 4.2) | — | 34 / 64 | | | | | loss |
| `rk` (rear and stack cells from one site), with or without `tmp`, with the rear entry in p1 or p3 | 4,300–4,476 | 34–38 / 64 | | | 40–44 | 34–36 | loss |

Why `tmp` and `rk` lose: a code colour must be injective on every set of sites one pointer expression may point into,
and both options create expressions whose kinds span two tags (`Fld(tmp, 1)` is an entry for a rear cell and a list for
a stack cell; the shared site's own field is the union of the two tags' field kinds) — 34 or 38 colours, W₀ 64, a
fallback of 98 rules and 68–76 helpers.

Why `endonce` is sound: the young bank's text is shorter than its pattern until its first answering tick, so the young
bank never has a full match and never reads `end`; only the old bank does. With `endonce` a young bank whose build ends
in a later step waits in that state until the next tick's first step; the strict profile check (the young bank has left
the build side by the end of its young window) makes sure END exists before the handover. It costs at most one tick of
young steps per life, so the rates are re-validated (§3).

### 4.3 The result: N < 1,000 (measured)

`lps-inline-curve` runs the greedy inliner once with the largest budget and records (N, rhs nodes, bytes) after every
elimination; with a fixed max-RHS bound, the eliminations under a smaller node budget are a prefix of those under a
larger one (the cheapest candidate is taken first; if it does not fit, no candidate does), so the whole trade-off curve
comes from one run. Configuration A, 4,216 rules compact (`out/pools-role-y15-o4.peg.gz`, `metrics/pools-role-y15-o4.json`),
max RHS 65,536 (`metrics/pools-inline-curve-y15-o4.md`):

| node budget (× the compact grammar's 199,511 rhs nodes) | N | rhs nodes | bytes (estimate) |
|---|---:|---:|---:|
| ×1 | 2,499 | 199,507 | 1,053,026 |
| ×1.25 | 1,747 | 249,213 | 1,266,897 |
| ×2 | 1,377 | 398,621 | 1,922,124 |
| ×3 | 1,194 | 596,775 | 2,791,991 |
| ×4 | 1,120 | 796,230 | 3,509,186 |
| ×8 | 1,023 | 1,582,180 | 6,705,420 |
| **×9.75** | **999** | **1,945,495** | 8,211,904 |
| ×16 | 946 | 3,179,139 | 13,814,327 |
| ×32 | 878 | 6,346,794 | 26,552,136 |

**The 999-rule grammar** (`out/pools-role-y15-o4-n999.peg.gz`, `metrics/pools-role-y15-o4-n999.json`, measured): 999 rules,
1,945,495 rhs nodes, max RHS 16,224, 7,958,678 rendered bytes, 42,936 distinct DAG nodes, 275,293 bytes gzipped; sha256 of
the `.peg` `eef35388…77656c1`, of the `.peg.gz` `102db800…108f0a958`. Checks: re-parsed, it equals the oracle on all 1,023
words ≤ 9 (`lps-check-file`); the Rust plain-PEG runner on the 140 raw words of `probes/runner_words.py 24 600` gives 0
mismatches (at most 4.4 s and 67 million steps per word). The machine it prints (RolePal 15/4 A at W₀ 32) passes
the four families on three seeds (3,385 + 3,810 + 6,385 words, §3) and every counterexample of the rejected
configurations (`PoolsSuite`).

This meets the target of inst.md (N < 1,000 after `PegOptimize` + budgeted inlining) but **not the preferred size**: the
preferred ×2–×3 budget gives 1,377–1,194 rules. Census of the 999-rule grammar: young layers 45–52 rules each (core
31–36, K 7–12, X 7–8), old layers 30–48, IN 137, layer 0 23, helpers 29, fallback 4, controller 8. What survives the
inliner is what the next layer reads hundreds of times: every pointer register is read through a dispatch over its
colours, and the guards are shared by all register updates of a layer (`y_K_5` has 680 references, `y_q0_5` 860).

| variant | N compact | N inlined (×1.25 / ×2 / ×4) | best N < 1,000 | colours / W₀ / first answer |
|---|---:|---|---|---|
| gap 10/13 (UNDER1000.md; wrong, §0) | 5,902 | 2,319 / 1,816 / 1,494 | — | 16 / 16 / 24 |
| gap 10/14 (the corrected status quo) | 6,177 | not measured | — | 17 / 32 / 48 |
| role 16/4 | 4,987 | 1,972 / 1,549 / 1,301 | — (1,280 at ×4, maxRhs 16,384) | 22 / 32 / 48 |
| role 15/4 `noacc fuse compact` | 4,407 | 1,812 / 1,432 / — | — | 21 / 32 / 48 |
| **role 15/4 A** | **4,216** | 1,747 / 1,377 / 1,120 | **999 at ×9.75** (8.0 MB) | 21 / 32 / 48 |

## 5. What is and is not established

```
overlap/coverage lemma:            unchanged (UNDER1000.md; finite check <= 16, schedule <= 4096)
abstract matcher correctness:      unchanged (Appendix A + range lemma, finite)
role handover:                     the handover copies the young state exactly (checked by the profiler on every birth
                                   tick of the test words); the young text is shorter than its pattern until the handover,
                                   so the young bank never has a full match (argument; this is what endonce relies on)
per-bank startup/deadline bound:   rates found by measurement only (15 young / 4 old at W0 32 on 3,385 + 3,810 + 6,385 words);
                                   young 14 and old 3 have counterexamples; no all-input bound
compiler endpoint preservation:    PegBudgetInline exact by construction; LpsCode unchanged
printed-grammar totality:          the structural argument of BREAKTHROUGH.md (consuming helpers, references to earlier
                                   sites of the same layer or to lower layers); not machine-checked
finite tests:                      §3, §4.3; sbt test (PoolsSuite, RolePalSuite and the earlier suites)
Lean verification status:          none — not a Lean proof; the theorem in ../proof is about the automaton
```

## 6. Tasks of inst.md not executed, and why

- **Task 3 (anchor rebalance α ∈ {11/8, 23/16}).** Not implemented. With the dyadic history the replay lengths
  k = 2h − n₀ become 5W/8, 3W/4 (α = 11/8) or 9W/16, 7W/8 (α = 23/16), which no single saved cell counts: a two-stage
  countdown needs a second pointer register and a stage bit in every young layer (+2 core rules × 14, estimate), and a
  longer replay that fits a saved cell makes A as expensive as B is now (estimate: A ≈ 11.9, B ≈ 9.4 at α = 11/8 against
  B ≈ 11.8 at 3/2). The base-3 experiment (§3) shows the other risk: changing the anchors changes which random
  palindromes sit at the edge. Worth measuring only with the model (`OverlapPal` with α) before any machine work.
- **Task 4.1 (single-writer registers in the compiler).** Not implemented; `endonce` removes the only register that
  qualified in the young layers (`end`) at the machine level. `acc` of the young role is gone (`noacc`).
- **Task 4.2 (rv/ks merged).** Implemented (`tmp`), measured, rejected: 34 colours (W₀ 64).
- **Task 4.3 (phase bits as one EOF code).** Replaced by the compact state encoding (young 3 bits, old 2 bits instead of
  5; no REV, no IDLE), which needs no new register kind.

## 7. Next steps (each one measurable)

1. **Fewer references per register** — the inliner stops where a register or guard has hundreds of readers. A pointer
   read dispatches over all its colours; a representation whose hot registers have small domains (fewer colours per
   read) would let the inliner go further at ×2–×3.
2. **A heterogeneous schedule** (inst §5): young layers that only build and young layers that only match, sized by
   the phase demands of Task 0, would drop `src`/`rc`/`end` from the matching layers and the FIFO reversal from the
   building ones.
3. **Task 3 on the model first**, as above.
