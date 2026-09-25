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

(to be completed)

## 5. What is and is not established

(to be completed)
