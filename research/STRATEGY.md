# Below 999 rules: what the grammar is made of, and the levers that move it (2026-09-26)

A strategy document on top of main `75c8494` (the 999-rule grammar of `POOLS.md`), following the routes of
`pal-peg-under-1000-claude-code-c4b38c5.md`. It ranks levers by measured evidence and names, for each, the cheapest
experiment that can falsify it. **Not a Lean proof; the theorem in `../proof` is about the automaton.** Numbers are
labelled *measured*, *estimate* or *target*.

## 0. Where we are (measured, `POOLS.md`)

The role machine 15/4 with the options noacc fuse compact endent endonce: 4,216 rules compact; the greedy budgeted
inliner gives 1,377 (×2 nodes), 1,194 (×3), 1,120 (×4), **999 (×9.75, 1,945,495 rhs nodes, 8.0 MB)**, 946 (×16), 878
(×32). The handoff prefers a compact grammar (×2–×3); that is 195–378 rules away.

**Correction (§3b): that machine, and both 999-rule grammars made from it, answer false on a 79-letter palindrome.** The
grammar that replaces them (13/4, β 7/16, W₀ 64): 999 rules at ×4.40, 2,984,353 bytes; ×2 1,257, ×3 1,090, ×4 1,018.

**Current best (§3f):** the §3e machine (11/4 + unfold + `tmp` + `lgap` + the unfold-then-popL merge, β 13/32, W₀ 128, mirrored
fallback) compiled with `sound` and points-to domains, common sub-expressions named: 996 rules at 349,849 rhs nodes (×2.45),
1,542,201 bytes; ×2.2 1,044, ×3 933. (§3e: 994 rules at 438,487; §3d: 996 at 472,260.)

## 1. Diagnosis: what survives the inliner, and why (measured)

`probes/survivors.py out/pools-role-y15-o4-n999.peg.gz` — the 999 rules by kind:

| kind | rules | total references |
|---|---:|---:|
| site fields `C_l_i_f` (allocated cells' fields) | 270 | 57,359 |
| sub-expressions `X` | 171 | 119,053 |
| conditions `K` | 169 | 108,540 |
| young registers (`cur, L, K, R, rv, ks, pendF/B/N, src, rc, q0–q2, rev`) | ≈ 300 | |
| old registers | ≈ 60 | |
| helpers: all 21 `Code_c` + 8 `Tail_c` | 29 | |
| fallback, controller | ≈ 16 | |

`probes/survivors.py --cost out/pools-role-y15-o4.peg.gz` — the compact grammar, inlining cost = fan-in × (size − 1):

| kind | rules | median fan-in | max fan-in | median size |
|---|---:|---:|---:|---:|
| young register | 374 | 64 | 201 | 28 |
| site field | 347 | 38 | 931 | 17 |
| X | 1,586 | 1 | 198 | 89 |
| K | 1,685 | 3 | 221 | 2 |
| old register | 112 | 4 | 159 | 17 |

The 1,000 most expensive rules to inline are 255 young registers, 318 site fields, 326 X, 51 old registers, 19 helpers
and 31 K. The reason is the dereference: `Fld(x, f)` compiles to a dispatch over every site of `x`'s domain,
`&(x_c Tail_c) x_ext C_s_f` per site, so **a register's fan-in ≈ its field reads × its domain size**, and a domain has
one site per young layer (the colours are 21 = 2 IN + 15 young + 4 old FIFO sites). Fewer young layers therefore cut
both the number of layers and the price of inlining everything that is left.

Three more facts:

- **The greedy inliner is the practical frontier** (`probes/keep_policy.py`). Keeping exactly the registers, site
  fields, helpers, fallback and controller and expanding every K and X gives N 945 at 122 million rhs nodes (×613);
  keeping by a fan-in threshold explodes to 10³⁸ nodes. The greedy run reaches N 946 at ×16. The inliner is not the
  lever.
- **Floor reductions carry over at 0.6–0.75** to the ×2–×4 points: 16/4 → 15/4 A lowered the core floor by 238 and N by
  172 (×2) and 150 (×3); `noacc fuse compact` → A lowered it by 71 and N by 55 (×2) and 41 (×3). So N < 1,000 at ×4
  needs ≈ −121 (a floor ≈ −160…−200), at ×3 ≈ −195 (floor ≈ −260…−325) (estimates).
- **The 21 `Code_c` helpers can never be removed**: `Code_c = &Tail_c / . Code_c` refers to itself, and the inliner
  never removes a self-referencing rule.

## 2. Levers, ranked before the experiments

| # | lever | mechanism | estimate | first experiment and gate |
|---|---|---|---|---|
| S1 | non-recursive codes | `Code_c = (!Tail_c .)* &Tail_c`: the same extent (the loop stops exactly c letters before the end and the `&Tail_c` fails when fewer remain), no self-reference | −21 at every budget, ~0 nodes | **E1** (§3; measured: adopted) |
| S2 | two letters per fast match | a MATCH step whose letter matches and whose next FIFO letter matches the next live entry consumes both (the fast path allocates nothing); for both roles | fewer young and old steps on matching stretches: young 15 → 13–14, old 4 → 3 would save 2 × 42 + 36 core | **E0** (§3; measured: rejected, 4.5–10.5 % < 20 %) |
| S3 | anchors balanced (Task 3) | family B born at αW. Letters per tick in the young window: A = 2/(α−1), B = 2α/(2−α) with exact replay — both 4.83 at α = √2 against a maximum of 6 at α = 3/2 (−19.5 %); with the replay rounded up to a saved dyadic cell only α ≈ 1.45, maximum 5.45 (−9 %) | young 15 → 12–13 (exact replay, a two-stage `rc` costs +2 core per young layer) or → 14 (dyadic replay); colours −2…−3 | α in `OverlapPal.ct`, `anchors` and the profiler **for the old-limit model only**; `pool-grid` per α ∈ {3/2, 23/16, 11/8}; into `RolePal` only if the model saves ≥ 2 young copies |
| S4 | old pool 3 | after S2/S3 the young bank hands over less backlog (≤ 130 letters today) | −36 core | `pool-grid r·/3` at W₀ 32, three seeds |
| S5 | inliner tweaks | e.g. eliminate registers before their readers inflate | ≤ 5 % | only after S1–S4 |

Combined (S1–S4, estimate): a floor −150…−250, so ×4 N ≈ 950–1,020 and ×3 ≈ 1,010–1,090. **N < 1,000 at ×4 is
plausible, at ×3 borderline**; ×2 needs a change in the number of allocation sites per young step (§4).

## 3. First experiments (measured)

### E1 — non-recursive code helpers (S1): adopted

`SiteCode.helpers(count, codeByStar = true)` (`lps-code … star`); `SiteCodeSuite` checks the extent of every `Code_i`
against the recursive one at every position of every word ≤ 9. The same configuration A, exported with it, re-parses
and equals the oracle on all words ≤ 9; its curve (`out/metrics/pools-inline-curve-y15-o4-star.md`):

| node budget | N (recursive `Code`) | N (`Code` by star) |
|---|---:|---:|
| ×2 | 1,377 | 1,368 |
| ×3 | 1,194 | 1,184 |
| ×4 | 1,120 | 1,105 |
| ×8 | 1,023 | 1,003 |
| ×16 | 946 | 926 |

N < 1,000 is now reached at ×8.28: **999 rules, 1,652,383 rhs nodes, max RHS 12,773, 6,754,563 bytes**
(`out/pools-role-y15-o4-star-n999.peg.gz`, `metrics/pools-role-y15-o4-star-n999.json`; before: ×9.75, 7,958,678 bytes).
It re-parses and equals the oracle on all words ≤ 9, and the Rust runner gives 0 mismatches on the 140 raw words
(24–601 letters; at most 2.2 s per word, against 4.4 s). The gain is −10 at ×3 (the gate asked for −15) and −15…−20
from ×4 on: the 21 helpers are cheap, but the inliner spends ×2–×3 on other rules first.

### E0 — what the young and old steps do (S2): S2 rejected

`OverlapProfile.StepKinds` classifies every universal step by its state change. Role 15/4 A without `fuse` (so every step
is a slot; the same 15 + 4 steps per tick), W₀ 32:

| family | role | build letter | build pop | match fast | match slow | match pop | FIFO reversal | two fast matches in a row |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| adversarial ≤ 600 (788 words) | young | 33.9 % | 30.9 % | 27.9 % | 0.2 % | 1.1 % | 5.7 % | 10.5 % of the busy steps |
| | old | — | — | 97.7 % | 0.7 % | 1.4 % | 0.3 % | 0.1 % |
| random 400 ≤ 1,025 | young | 23.4 % | 27.4 % | 13.6 % | 6.2 % | 23.6 % | 5.6 % | 4.5 % |
| | old | — | — | 36.1 % | 13.0 % | 43.3 % | 7.6 % | 2.5 % |

(percentages of the busy, i.e. state-changing, steps; the random family is the one that sets the rates.)

- **S2 fails its gate**: two consecutive fast matches are 4.5 % (random) and 10.5 % (adversarial) of the young steps,
  against the 20 % asked for; a two-letter match would not save a young layer.
- **The young bank's work is mostly the pattern build**: build letters and build pops together are 51–65 % of its busy
  steps, with 0.9–1.2 pops per pattern letter. The pops of the build are the rear reversal (every appended entry is
  moved once from the rear stack to the front list, one step each) and the unfolding of gaps after a mismatch. On random
  texts the matching pops (23.6 %) are the second item.
- **The old bank's work on random words is mismatch handling** (43 % pops, 13 % slow matches); on the adversarial family
  it is almost only fast matches, one per tick.

### The ranking after E0 and E1

| # | lever | status | estimate |
|---|---|---|---|
| S1 | non-recursive codes | **adopted (measured)** | −10 (×3), −15 (×4), −20 (×8 and above) |
| S3 | anchors balanced (Task 3), model first | next | young 15 → 12–13 (exact replay) or 14 (dyadic): floor −84…−126 |
| S2′ | a mismatch step unfolds its own gap | new, from E0 | a mismatch (build or match) pushes `GNODE(cur, L)` and its first pop always unfolds it (`gPopG`: `rv` is null, the sub-state is S0 and the head of L is that GNODE). Both fit in one step with the existing sites: the list-node site allocates `LNODE(cur, L)` instead of the GNODE, and the KNODE site — unused by a build or match step — holds `(LNODE, K, cur.hR, cur.hK)`; L becomes `cur.hL`. (A matched build step does *not* free the list-node site: the GNODE becomes the new entry's `hL`.) It adds branches to every young layer, so it pays only if the saved steps remove a layer: E2 counts how many steps this saves per tick |
| S4 | old pool 3 | after S3 | floor −36 |
| S2 | two letters per fast match | **rejected (E0)** | — |
| S5 | inliner tweaks | last | ≤ 5 % |

The next experiment is **E2**: count the mismatch steps that push a GNODE (each would save exactly one step under
S2′), per role, and the peak per tick, to size S2′ before writing it. S3 on the old-limit model comes first, since it
changes no core and so no per-layer cost.

Updated estimate (S1 measured + S2′ + S3 + S4): ×4 N ≈ 950–1,000, ×3 ≈ 1,030–1,080. N < 1,000 at ×4 is the realistic
target of this plan; ×3 needs the ×2 question of §4 as well.

## 3b. S3 and S2′, implemented (measured)

### S3 — balanced anchors: young 15 → 13

`OverlapPal.Sched(num, den)` (β = num/den; `beta=7/16` on `pool-grid`, `overlap-profile`, `lps-code`). β = 1/2 builds the
original controller (the default grammars keep their sha256). Any other β in (1/3, 1/2) uses a **staged clock** in the
controller: at every dyadic tick P (from W₀/2 on) a walker `clk` walks down, one stage after the other, from saved dyadic
cells P/2^j (a history `h1 … hJ` shifted at every dyadic tick); the ends of three intervals are the events — at
(2 − 2β)P `cA` saves the replay cell of the next A, at (1 + β)P family B is born (only while A is young, `ya`), at 4βP
`cB` saves the replay cell of the next B. The stages are the binary digits of the intervals (1 − 2β)P, (3β − 1)P,
(3β − 1)P; for 7/16: P/8 | P/4 + P/16 | P/4 + P/16 (5 stages, W₀ ≥ 32). Replays are exact (k_A = (1 − β)W, k_B = 2βW).
Only the controller and IN change; no core, so no per-layer cost. `AnchorSchedSuite` (measured): the stages and anchors;
β = 1/2 builds the original controller; the model at 7/16 (W₀ 32) and 3/8 (W₀ 16) has the oracle's outputs and a clean
profile (births, replay cells, answering bank); the role machine at 7/16 has a clean strict role profile; through the
code backend the PEG's rules agree with the interpreter across the anchors 32, 46, 64.

Letters per tick in the young window (A = 2/(α−1), B = 2α/(2−α)): 4 and 6 at α = 3/2, 4.57 and 5.11 at 23/16, 5.33 and 4.4
at 11/8. The proportional model from the unlimited machine (A 10 steps at 4 letters, B 14 at 6: steps ≈ 2 × letters + 2)
predicted young 13 at 23/16. Rates on the old-limit model, W₀ 32, the big set (random 2,000 ≤ 1,025 + adversarial ≤ 600,
switch ≤ 1,025, structured ≤ 1,025; 3,345 words):

| β | 12/12/4 | 12/12/5 | 12/12/6 | 13/13/3 | 13/13/4 | 14/14/4 |
|---|---|---|---|---|---|---|
| 1/2 (POOLS) | — | — | — | — | — | FAIL (15/15/4 is the minimum) |
| 7/16 | FAIL n 538 | FAIL n 261 | FAIL n 261 | FAIL n 71 | **PASS** | PASS |
| 3/8 | — | — | — | — | FAIL n 363 | PASS |

Young 12 fails whatever the old pool (the young B bank itself is late at n 261, h 184): **β = 7/16 gives young 13**, as
predicted; 3/8 gives 14.

The role machine `r13/4` (configuration A, `Code` by star, β = 7/16, W₀ 32) passes seed 7 (3,000 random ≤ 2,049 + three
families ≤ 200; 3,754 words) and seed 11 (5,000 random ≤ 2,049 + the three families; 6,345 words), **but fails the big
set at n = 71** (the first B life, h = 46, young window 18 ticks): `endonce` loses up to one tick of a young window, and
the shortest window at W₀ 32 cannot spare it (without `endonce`, with old 5, or at young 14 the word passes). The same
mechanism as the n = 42 failure at W₀ 16 in POOLS.

Its compact grammar is **3,812 rules** (15/4 A: 4,216) and its curve:

| node budget | 15/4, β 1/2 (E1) | 13/4, β 7/16 |
|---|---:|---:|
| ×2 | 1,368 | 1,243 |
| ×3 | 1,184 | 1,077 |
| ×4 | 1,105 | 1,005 |
| ×8 | 1,003 | 911 |
| ×16 | 926 | 838 |
| N < 1,000 | ×8.28, 1,652,383 nodes | ×4.11, 674,895 nodes (2,806,652 bytes) |

The gain (−100 at ×4, −125 at ×2) is larger than two young layers' core (−84): every register's dispatch covers its
domain's sites, and the domains lose two sites each.

### E2 and S2′ — a mismatch step unfolds its own gap

E2 (`StepKinds`, "GNODE pushes"; role 15/4 A without `fuse`, W₀ 32): a mismatch that pushes `GNODE(cur, L)` is 9.2 % of
the young busy steps and 8.3 % of the old ones on random words (400 ≤ 1,025), 0.3 % and 0.1 % on the adversarial family
(≤ 600). Every such step is followed by `gPopG`, so S2′ saves exactly these steps.

`GapKmp.Core(unfold)` (`unfold` flag): the mismatch step allocates `LNODE(cur, L)` and `KNODE(LNODE, K, cur.hR, cur.hK)`
(the K-node site is unused by a transition) and sets `L := cur.hL`. `UnfoldSuite` (measured): the oracle's outputs on all
words ≤ 10 and a clean profile on the switch/structured words ≤ 129; PEG = interpreter with the old backend (≤ 6) and the
code backend (configuration A, ≤ 12 at 1/1, ≤ 11 at 2/2); no GNODE is pushed any more; configuration A with unfold has a
clean strict role profile. Rates on the model (β 7/16, big set): 12/12/4 FAIL (n 71), **12/12/5 PASS, 13/13/3 PASS**. On
the n = 71 word the role machine with `endonce` passes at 13/4 and 13/3 with unfold, fails at 12/5 and 12/4.

### The every-length family, and a wrong answer of the POOLS grammar

The rate searches of POOLS and of this section used the four families (random, adversarial, switch, structured) on three
seeds. The switch family is built around the anchors (bOf(W), 2W, 2bOf(W) ± 1), and `probes/runner_words.py` doubles its
lengths, so neither reaches every length. `OverlapProfile.everyLengthWords` (`pool-grid … every=<maxLen>`) takes the
runner's seven kinds of words at **every** length 3 … maxLen (`b a^(n-2) b` and its flip, the Thue–Morse palindrome and a
flip at n/3, period 3, `a^i b a^j b a^i`, a random palindrome; 7,158 words up to 1,025).

**The role machine of POOLS (15/4 A, β 1/2, W₀ 32) fails it at n = 79**: bank B of the first B life (h 48, young window
16 ticks) has not caught up at the end of the 79-letter random palindrome
`bbbabaabaaaaabaaaaabbababaaaabaaaaaabbaaabbaaaaaabaaaabababbaaaaabaaaaabaababbb`. Both 999-rule grammars made from it
(`out/pools-role-y15-o4-n999.peg.gz` on main and `out/pools-role-y15-o4-star-n999.peg.gz` of E1) answer **false** on it
in the Rust runner (measured). Cause: `endonce` lets only the first young step of a tick end a build, so a young window can
lose a tick; in the shortest window this delays the handover and the old pool of 4 does not catch up (without `endonce`,
at W₀ 64, with old 5 or with young 16 the word passes). The three seeds never contained such a word: the validation was a
finite observation, and it missed this one.

The every-length family, measured on the role machine (configuration A unless stated, W₀ 32 unless stated):

| configuration | every = 1,025 |
|---|---|
| 15/4 A, β 1/2 (POOLS) | FAIL n 79 (h 48) |
| 13/3 + unfold, β 7/16 | FAIL n 177 (h 92, Thue–Morse) |
| 13/3 + unfold − endonce, β 7/16 | FAIL n 361 (h 184, Thue–Morse) |
| 13/3 + unfold, β 7/16, W₀ 64 | FAIL n 177 |
| 12/4 + unfold, β 7/16, W₀ 64 | FAIL n 155 (h 92) |
| 13/4 − endonce, β 7/16 | PASS |
| 13/4 + unfold, β 7/16 | PASS |
| 14/3 + unfold, β 7/16 | PASS |
| **13/4, β 7/16, W₀ 64** | **PASS** |

(12/3 + unfold at β 13/32, W₀ 64, which passed the model, fails the role machine on all three seeds and the runner's
121-letter Thue–Morse palindrome.) An old pool of 3 is broken by Thue–Morse palindromes whatever the other options.

### The chosen grammar: 13/4 A, β 7/16, W₀ 64

Role machine 13/4, configuration A (noacc fuse compact endent endonce), `Code` by star, β = 7/16, W₀ = 64 (first machine
answer 92; 19 colours). Rates (measured): the three seeds (3,345 + 3,754 + 6,345 words), every = 1,025 with seeds 5 and 7
(7,158 + 7,161 words) and every = 2,049 (14,326 words) all pass; the machine answers every counterexample of
`src/test/resources/anchors-counterexamples.txt` (eight configurations, the POOLS machine included) and of POOLS.

`out/anchors-role-y13-o4-w64.peg.gz` (compact: 3,860 rules, 164,724 rhs nodes) and
`out/anchors-role-y13-o4-w64-n999.peg.gz`: **999 rules, 724,780 rhs nodes (×4.40 of its compact grammar), max RHS 21,425,
2,984,353 bytes, 167,877 bytes gzipped** (`metrics/anchors-role-y13-o4-w64-n999.json`; sha256 of the `.peg`
`2443c690…1bfc0843`). Checks: re-parsed, it equals the oracle on all 1,023 words ≤ 9; the Rust runner gives 0 mismatches
on the 140 raw words of `probes/runner_words.py 24 600` and on the 16 palindromes the grids found. Against the POOLS grammar
(×9.75, 7,958,678 bytes) it is 2.7 times smaller at the same N, and its curve is ×2 1,257, ×3 1,090, ×4 1,018
(15/4 with E1: 1,368, 1,184, 1,105). All the curves: `out/metrics/anchors-inline-curves.md`.

**Long words** (measured, 2026-09-26). The failures found so far were short early lives, but a rate deficit could also
accumulate over long palindromes. `pool-grid … lengths=<n1,…>` (`OverlapProfile.lengthWords`: the runner's seven kinds and
four more random palindromes per length) at 25 lengths up to 9,000 — 2943, 2944, 2945, 4095, 4096, 4097, 5887, 5888, 5889, 8191, 8192, 8193, 9000, 3840, 3975, 4829, 5617, 6445, 7116, 7192, 7559, 7690, 7923, 8091, 8301 —
283 words: PASS. Old backlog maximum and the longest run of old ticks behind: 182 and 282 (every length ≤ 1,025), 449 and
570 (every length ≤ 2,049), 914 and 1,758 (the 25 sampled lengths ≤ 9,000): no faster than the length. The Rust runner on
the grammar itself with 12 words of 4,097 and 9,000 letters (Thue–Morse palindrome and a flip, two random palindromes,
`a^i b a^j b a^i`, `b a^n b`): 0 mismatches, 7–25 s and up to 2.4 billion steps per word. Still an observation, not a proof.

### Where this leaves the ranking

| lever | status |
|---|---|
| S1 non-recursive codes | adopted |
| S3 anchors at β 7/16 | **adopted**: young 15 → 13 |
| W₀ 64 | adopted: keeps `endonce` (−23 at ×4 against dropping it) |
| S2′ unfold | implemented and tested; no gain in a validated configuration yet (it made old 3 and young 12 pass the seeds, but not the every-length family) |
| S4 old pool 3 | rejected: Thue–Morse palindromes break it |
| S2 two-letter match | rejected (E0) |

N < 1,000 at ×4 is 19 rules away. The next levers: `unfold` with a margin that does not rely on old 3 (e.g. 12/4 at
β 13/32 or 3/8 on the every-length family); cheaper `endonce`-free build ends; S5.

## 3c. Other angles: encodings, W₀ 128, and naming common sub-expressions (measured, 2026-09-27)

The rate levers of §3b are nearly exhausted (old 3 and young 12 break on Thue–Morse palindromes of the short early lives).
Four other angles, all measured on the role machine with configuration A and `Code` by star. Node budgets below are
multiples of each grammar's own compact size; where the compact sizes differ, the absolute rhs nodes decide.

### Encoding options that W₀ 64 made free

`tmp` (rv and ks share one register), `rk` (RNODE and KNODE share one site) and `p3` (the rear entry in p3) were rejected in
POOLS only because their colours (30–34) needed W₀ 64; §3b chose W₀ 64 anyway. They and the `trie` dispatch change only the
encoding (`RolePalSuite`, "the size options keep the behaviour"). On 13/4, β 7/16, W₀ 64 (`out/metrics/perspectives-curves.md`):

| variant | compact rules / nodes | colours | ×2 | ×3 | ×4 | 999 rules at (rhs nodes) |
|---|---|---:|---:|---:|---:|---:|
| §3b (none) | 3,860 / 164,724 | 19 | 1,257 | 1,090 | 1,018 | 724,780 |
| `p3` | identical | 19 | 1,257 | 1,090 | 1,018 | 724,780 |
| `rk` | 3,882 / 182,863 | 30 | 1,256 | 1,084 | 1,017 | 800,723 |
| `rk p3` | 3,890 / 184,454 | 34 | 1,249 | 1,086 | 1,016 | 808,024 |
| **`tmp`** | **3,755 / 164,934** | 30 | **1,247** | **1,075** | **993** | **638,740** |
| `trie` | 3,865 / 242,352 | 19 | 1,209 | 1,047 | 980 | 876,054 |
| W₀ 128 | 3,954 / 166,023 | 19 | 1,286 | 1,115 | 1,041 | 820,882 |

`tmp` wins in rules and in absolute nodes. **Its first grammars were wrong:** they referenced 24–26 undefined site-field rules
`C_l_3_p3/p4`. A register that unites two tags (`tmp` points to RNODE and KNODE cells) got a dispatch arm for every site of its
domain, also for the rear sites whose fields 3 and 4 are always null and have no rule; the arms are never taken at run time,
the Scala matcher treats an undefined rule as a failure, so `lps-check-file` and `checkSim` passed and only the Rust runner
refused the grammar. `LpsCode` now leaves out the arms (and the fresh-cell reads) of always-null fields — the read is null there
anyway — and `LpsCodeSuite` checks that every referenced rule is defined for every size option (it fails on the old compiler).
The §3b compact grammar is byte-identical after the fix; the `tmp` numbers here are after it. `trie` looks better per multiple only because its `Mask_k` helpers make the
compact grammar 1.5 times larger; in absolute nodes it is worse. W₀ 128 alone costs +23 at ×4.

### W₀ 128: the short early lives go into the fallback

Every failure so far was an early life (h 46–184). At W₀ 128 the first machine answer is 184 (β 7/16) or 180 (β 13/32).
Every length ≤ 1,025 on the role machine (A + unfold):

| young / old | β 7/16 | β 13/32 |
|---|---|---|
| 12/4 | **PASS** | **PASS** |
| 13/3 | FAIL n 361 (h 184) | — |
| 12/3 | — | FAIL n 349 (h 180) |
| 11/4 | FAIL n 257 (h 184) | FAIL n 209 (h 128) |

An old pool of 3 still breaks (the h ≈ 180 life survives at W₀ 128); young 12 with unfold passes. The machine
**12/4, A + unfold + `tmp`, β 7/16, W₀ 128** passes the whole acceptance list of §3b: every = 1,025 with seeds 5 and 7
(7,158 + 7,161 words), the three seeds (3,345 + 3,754 + 6,345), the 25 sampled lengths ≤ 9,000 (283 words) and every = 2,049
(14,326 words). Compact: 3,739 rules, 152,266 nodes, 28 colours; greedy curve ×2 1,256, ×3 1,082, ×4 992, 999 rules at
591,546 nodes (β 13/32 passes every = 1,025 too, and is slightly larger).

### A look-ahead priority for the inliner: worse

`PegBudgetInline.Lookahead(λ)` adds λ × the growth an elimination adds to the future elimination of each user. On the `tmp`
grammar: ×4 993 (λ 0) against 1,110 (λ 0.05), 1,186 (0.2), 1,316 (0.5). The myopic order is better; not used.
(`lps-inline-curve … la=<λ>`; the export now stops the same run after k eliminations, which reproduces the previous
curves exactly.)

### Naming common sub-expressions (`PegCse`)

The budgeted inliner only removes the compiler's rules. After inlining, the 999-rule grammar of 12/4 has 544,405 tree
nodes but only 33,393 distinct sub-expressions (`peg-cse-probe`): 108 of them would each save more than 1,300 nodes if named
as a rule, while the inliner pays about 1,700 nodes per rule near N = 1,000. The largest are tiny and frequent (`&.` 6,700
times, `!K40_2` 4,734 times) or the old role's `cur` dispatch (387 nodes, 9 times). No two rules are equivalent
(bisimulation over the rule graph merges none). `PegCse.improve` alternates naming the best sub-expressions (each above a
threshold) and the greedy inliner under the same node budget, while N falls; both steps keep every rule's meaning
(`PegCseSuite`: extents kept at every position; the start rule on all words ≤ 9 of a site-code grammar). On the 12/4
grammar:

| node budget | greedy | + naming |
|---|---:|---:|
| ×2 | 1,256 | 1,235 |
| ×3 | 1,082 | 1,040 |
| ×3.5 | 1,031 | **998** |
| ×4 | 992 | 978 |

(The loop is a heuristic and not monotone in the budget: ×3.6 ends at 1,001; `out/metrics/perspectives-curves.md`.)

### The grammar of §3c

`out/anchors-role-y12-o4-w128-n998.peg.gz`: 12/4, A + unfold + `tmp`, `Code` by star, β 7/16, W₀ 128, greedy inlining and
common sub-expressions at ×3.5 of its compact grammar (`out/anchors-role-y12-o4-w128.peg.gz`): **998 rules, 531,531 rhs nodes,
max RHS 7,035, 2,283,029 bytes, 149,481 bytes gzipped** (`metrics/anchors-role-y12-o4-w128-n998.json`; sha256 of the `.peg`
`20804452…`). By kind: young registers 208, old 57, named sub-expressions 59, fallback 42 (it covers every n < 184 now), the
rest site fields and conditions. Checks: no undefined reference; re-parsed, it equals the oracle on all 1,023 words ≤ 9; the
Rust runner gives 0 mismatches on 168 words of 24–9,000 letters (the 140 raw words, the 16 counterexample palindromes of both
resource files, the 12 long words of 4,097 and 9,000 letters; at most 2.27 billion steps per word). Rates: the acceptance list
above. Still an observation, not a proof.

| grammar | 999 rules at | bytes |
|---|---|---:|
| POOLS (15/4, β 1/2, W₀ 32) — wrong on a 79-letter palindrome | 1,945,495 nodes | 7,958,678 |
| §3b (13/4, β 7/16, W₀ 64) | 724,780 nodes | 2,984,353 |
| **§3c (12/4 + unfold + `tmp`, W₀ 128, + naming)** | 998 rules at 531,531 nodes | 2,283,029 |

Next levers: the fallback (42 rules for n < 184; a bounded-length palindrome grammar can surely be smaller), naming inside
the greedy loop instead of in rounds, and the old pool (4 is still the floor).

## 3d. The three next levers of §3c (measured, 2026-09-27)

### The fallback: mirrored pairs instead of the `Pal_k` family — adopted

The fallback answers the lengths below the first machine answer (184 at W₀ 128). `SiteCode.fallbackBelow` builds
`Pal_k = "a" Pal_{k-2} "a" / "b" Pal_{k-2} "b"`: two references to `Pal_{k-2}`, so inlining a chain doubles its size and the
greedy keeps rules (42 of them in the §3c grammar, 10 at W₀ 64). `SiteCode.fallbackMirror` (`lps-code … mirror`) checks
mirrored pairs instead: `Mir_j` accepts when at most j + 1 letters remain, otherwise matches the letter at j against the letter
followed by exactly j letters (`(!T .)* &T`, T = exactly j + 1 letters then EOF, skips to it) and continues with `Mir_{j+1}` —
one reference per step, so the whole chain inlines into O(limit²) nodes. `SiteCodeSuite`: for limits 2, 3, 8, 13, 16 it accepts
exactly the palindromes shorter than the limit and agrees with the `Pal_k` family on all words ≤ limit + 2. On the §3c machine
the compact grammar goes from 3,739 rules / 152,266 nodes to 3,648 / 175,153, no `Mir` or `Pal` rule survives the inliner,
and the greedy alone reaches 999 rules at 528,756 nodes (with `Pal_k`: 591,546).

### Naming inside the greedy loop — not better

`PegCse.improveAdaptive` (`peg-cse-adaptive`) takes each round's threshold from the greedy run itself: the price of the next
elimination (`PegBudgetInline.cheapest`), 735–2,975 nodes along the curve. It names fewer sub-expressions than a fixed
threshold of 700–1,300 and ends no lower: on the mirrored grammar ×3 977 against 968 (fixed), ×3.5 938 against 939. A naming
below the price still pays, because it also shrinks the rules that contain the sub-expression and so the price of eliminating
them later. The fixed-threshold rounds stay.

### The old pool below 4 — possible, not useful

| W₀ | 12/3 + unfold | 13/3 + unfold | 11/4 + unfold |
|---|---|---|---|
| 128 | FAIL n 349 (β 13/32) | FAIL n 361 (h 184) | FAIL n 257 / 209 |
| 256 | FAIL n 655 (h 368) | **PASS** (every ≤ 1,025) | FAIL n 512 (h 368) |

Young 12 with old 3 fails at the first B life whatever W₀ (h 184 at 128, h 368 at 256): raising W₀ only moves the failure to
the next first B life. 13/3 passes at W₀ 256, but it has as many layers as 12/4 with a dearer young one and a fallback for every
n < 368: its compact grammar is 3,777 rules / 247,391 nodes and the greedy reaches 999 rules only at 631,159 nodes (12/4 at
W₀ 128 with the mirrored fallback: 528,756). Old 4 stays.

### The grammar of §3d

`out/anchors-role-y12-o4-w128-mirror-n996.peg.gz`: the §3c machine (12/4, A + unfold + `tmp`, β 7/16, W₀ 128) with the mirrored
fallback, greedy inlining and named common sub-expressions (threshold 1,100) at ×2.7 of its compact grammar
(`out/anchors-role-y12-o4-w128-mirror.peg.gz`): **996 rules, 472,260 rhs nodes, max RHS 25,613 (the start rule holds the
whole fallback), 2,033,703 bytes, 141,261 bytes gzipped** (`metrics/anchors-role-y12-o4-w128-mirror-n996.json`, sha256 of the
`.peg` `fe15be0b…`). The curve with naming: ×2 1,146, ×2.5 1,040, ×2.7 996, ×3 968, ×4 910 — **N < 1,000 inside the ×2–×3
band the handoff prefers.** Checks: no undefined reference; re-parsed, it equals the oracle on all words ≤ 9; the Rust runner
gives 0 mismatches on the 168 words of §3c (24–9,000 letters) and on 792 more words of every length 3–200 (a random palindrome,
a flip of it, the Thue–Morse palindrome and a flip, at each length: the fallback and its boundary at 184). The machine is the
§3c one, so its rates stand. Still an observation, not a proof.

| grammar | fewer than 1,000 rules at | bytes |
|---|---|---:|
| §3b (13/4, W₀ 64) | 724,780 nodes (×4.40) | 2,984,353 |
| §3c (12/4 + unfold + `tmp`, W₀ 128, naming) | 531,531 nodes (×3.5) | 2,283,029 |
| **§3d (+ mirrored fallback)** | **472,260 nodes (×2.7)** | **2,033,703** |

## 3e. Looking for a dramatic cut: seven ideas measured (2026-09-27)

The 996 rules of §3d are about 250 per-layer registers (young ≈ 18 per layer, every pointer twice: its value `x` and its colour
code `x_c`), 212 site fields, and about 500 shared helpers (conditions K, reads X, named sub-expressions, `Tail`). A young
layer is worth a lot: the same machine with young 12, 11, 10 reaches 999 rules at 528,756, 398,492 and 301,314 rhs nodes
(−25 % per layer). Seven ideas, each implemented and measured (every number below is measured):

| # | idea | result |
|---|---|---|
| A | **two steps per slot** (`pairs`: `Lpm.fuse` of two young/old steps) | worse: the same 3,648 compact rules but 208,896 nodes; 999 rules at 952,966 nodes. The second step reads the first step's registers many times, so their expressions are copied; the greedy kept those registers for a reason |
| B | **colours by use** (`recolour`: the most referenced colour gets the shortest `Tail_c`) | no effect (999 at 528,984): `Tail_c` is a chain of two-node rules, so its price hardly depends on c |
| C | **no RHS cap** (`maxRhs` 4,000,000) | no effect: the cap of 65,536 never binds |
| D | **a look at the steps** (E3, `step-bigrams`: the branch of every young step from its guards) | reversal chains 19 % of the young steps; `popG → takePop` 6 %, of which 79 % unfold a GNODE whose entry has an empty gap |
| E | **`lgap`**: an entry with an empty gap gets an LNODE, not a GNODE (gap(cur) ++ [cur] ++ L = [cur] ++ L) | free (+66 nodes at 999) and −5 … −16 % young steps; alone not enough for young 11 |
| F | **merging a pop with the pop that always follows it** without new cells (`merge2`: U = mismatch-unfold then popL, G = popG then popL, R = R1 + R2, the last two entries of a reversal (R1 `rev`, R2 `takeRev`); `merge3`: splice shortcuts, build + takeRev1) | young 11 at β 7/16 needs R1 for the period-3 words and U (or G) for the random ones; the cheapest combination that passes the seed-21 set, `lgap` + R1 + U, reaches 999 at 520,517 nodes (−1.6 %), because the merges' nested reads cost about what the saved layer is worth; all of `merge2` 865,767, `merge3` 1.49 M |
| G | **old 3** with the merges | at young 12, `lgap` alone fails (n 357); `lgap` + R1 + U passes every length ≤ 1,025; by the young-11 costs those merges cost more than an old layer (estimate) |

Two findings on the way:

- **A literal `False` guard is compiled in.** The first measurements of E and F were inflated: an option that was off still
  left `p.when(False)`, `(False, e)` cases and `all(…, True)` in the step, and the compiler turned them into rules and nodes (the
  12/4 compact grammar grew from 3,648 to 3,792–3,856 rules with every option off). The options now add branches and cases only
  when they are on; a test checks that the §3d compact grammar keeps its sha256.
- **Balanced anchors help the merges.** At β 13/32 (A and B at 4.92 and 4.74 letters per tick instead of 4.57 and 5.11), young 11
  passes with `lgap` + U alone (at 7/16 it needs R1 as well), and U is the cheapest merge.

### The grammar of §3e: role 11/4, `lgap` + U, β 13/32, W₀ 128

Role 11/4, configuration A + `unfold` + `tmp` + `lgap` + `merge2` without G and R (the mismatch-unfold-then-popL merge),
β = 13/32, W₀ = 128 (first machine answer 180), mirrored fallback. Rates (measured): every length ≤ 1,025 with seeds 5 and 7,
the three seeds (3,297 + 3,706 + 6,297 words), 25 sampled lengths ≤ 9,000 and every length ≤ 2,049: all pass (the last two on 283 and 14,326 words). Compact
3,529 rules, 171,517 nodes, 26 colours; greedy 999 rules at 458,189 nodes. With named common sub-expressions:

| budget | rules | rhs nodes | bytes |
|---|---:|---:|---:|
| ×2 | 1,098 | 342,699 | 1,577,434 |
| ×2.4 | 1,019 | 411,109 | 1,816,505 |
| ×2.5 | 1,003 | 428,030 | 1,877,649 |
| **×2.56** | **994** | **438,487** | **1,905,775** |
| ×3 | 951 | 512,816 | 2,183,116 |

`out/anchors-role-y11-o4-w128-n994.peg.gz`: **994 rules, 438,487 rhs nodes, 1,905,775 bytes** (§3d: 996 rules at 472,260 nodes,
2,033,703 bytes; at equal budgets −21 to −48 rules). Checks: no undefined reference; re-parsed = oracle on all words ≤ 9; the
Rust runner gives 0 mismatches on the 168 words of §3c and the 799 words of every length 3–200. Still an observation, not a proof.

**Not dramatic.** Each saved layer is paid back by the branch logic it needs: a merge adds nested reads, and a read costs a
dispatch over every site its pointer may reach. The per-layer cost of branching, not the number of steps, is now the limit.

| grammar | fewer than 1,000 rules at | bytes |
|---|---|---:|
| §3b (13/4, W₀ 64) | 724,780 nodes (×4.40) | 2,984,353 |
| §3c (12/4 + unfold + `tmp`, W₀ 128, naming) | 531,531 nodes (×3.5) | 2,283,029 |
| §3d (+ mirrored fallback) | 472,260 nodes (×2.7) | 2,033,703 |
| **§3e (11/4 + `lgap` + U, β 13/32)** | **438,487 nodes (×2.56)** | **1,905,775** |

Reproduce (from `research/`):

```sh
sbt "run lps-code export role 11 4 128 C.peg noacc fuse compact endent endonce star unfold tmp mirror lgap merge2 noG noR beta=13/32" \
    "run peg-cse-improve C.peg 2.56 65536 60 1000 10 out.peg out.json"
sbt "run step-bigrams 12 4 128 every 300 noacc fuse compact endent endonce unfold tmp beta=7/16"   # D (add lgap for E)
```

## 3f. Cutting the branching of one read (2026-09-27)

§3e ended on the price of branching: a read `Fld(x, f)` compiles to one arm per site x may point to
(`&(x_c Tail_c) x C_l_i_pf`), and every read dispatches twice (the extent and the colour code). Where the tokens of the
§3e compact grammar go (measured, 131,500 tokens, 12,213 arms):

| | share of the grammar |
|---|---:|
| dispatch arms | 56.3 % |
| arms inside the `_c` colour-code rules | 24.8 % |
| tag-bit tests inside field-read arms | 6.9 % |
| arms to old-role (OU) sites inside reads of young layers | 4.5 % |
| pure tag-test dispatches (`TagIs`) | 2.8 % |

Two of these cost nothing to remove, and the machine does not change (its steps, and so its rates, are those of §3e):

- **The tag tests were tautologies.** A list node is an LNODE or a GNODE, and the arm tested `&(!C_2_5_g0 / C_2_5_g0)`:
  always true, but it kept the tag-bit rules, and the guard rules behind them, alive. The colour already picks a site of
  the pointer's domain, and the pointer only holds cells of its kinds (`Lps.run` checks that after every slot), so a field
  read needs no tag test (`sound`; a tag test at a site with the tested tag only is the colour test alone).
- **Domains came from tags.** `sitesOf(kinds)` gives every site that can make the tag, so a young read also branched to
  the old role's sites, although the young registers are reset at every birth. `PointsTo` computes per register and per
  (site, field) the sites whose cells it may hold (an Andersen-style fixpoint over the machine, cut to the kinds' sites);
  with `pts` a read dispatches over those. A test checks the sets after every slot on the words ≤ 300 of §3e and that no
  young register may hold an old-role site. The colours fall from 26 to 22.

Measured on the §3e machine (role 11/4, `lgap` + U, β 13/32, W₀ 128, mirrored fallback):

| option | compact rules | compact nodes | colours | fewer than 1,000 rules at | bytes |
|---|---:|---:|---:|---|---:|
| none (§3e) | 3,529 | 171,517 | 26 | 994 at 438,487 nodes (×2.56) | 1,905,775 |
| `sound` | 3,529 | 161,077 | 26 | 987 at 426,202 (×2.65) | 1,849,149 |
| `pts` | 3,521 | 153,047 | 22 | 992 at 373,810 (×2.45) | 1,619,754 |
| **`sound pts`** | **3,521** | **143,051** | **22** | **996 at 349,849 (×2.45)** | **1,542,201** |

With both, the arms fall from 12,213 to 9,736; the old-role arms of young reads from 4.5 % to 0.2 %, the tag bits in arms
from 6.9 % to 1.0 %. The curve with naming: ×2.2 1,044, ×2.35 1,018, ×2.45 996, ×2.56 984, ×2.8 949, ×3 933 (428,580
nodes, where §3e needed 438,487 for 994). `out/anchors-role-y11-o4-w128-pts-n996.peg.gz`: **996 rules, 349,849 rhs nodes,
1,542,201 bytes** (−20 % nodes against §3e). Checks: no undefined reference; re-parsed = oracle on all words ≤ 9; the Rust
runner gives 0 mismatches on the 168 words of §3c, 796 words of every length 3–200 and every counterexample of both resource
files. The machine is the §3e one, so its rates stand. Still an observation, not a proof.

What is left is the inherent part. The largest group of dispatches now branches over 11 sites, one per young layer (IN and
the ten YU): a young cell can be made at any layer, so a pointer must name the layer. `tagpar` (the tag in the colour's parity,
one test per tag test) was not built: pure tag dispatches are 3.0 % of the compact grammar, and doubling the colours
(22 → up to 44) would add about 22 `Tail`/`Code` helper rules, which the 1,000-rule budget cannot spare.

**How far re-addressing could go** (`alloc-profile 11 4 128 400 …`, measured on 3,283 words ≤ 400): per tick, the cells born
in the tick that outlive it, by tag. The mean is 0.7–1.5 per tag, but every tag has ticks in which every one of its sites keeps
a cell (maximum = sites: 17, 11, 15, 11, 15, 11), in the young role's catch-up. A pointer addressed by (tick, index within
the tick) needs that many alternatives, so no re-addressing of the cells gets below one arm per layer. A smaller fan-out needs
a machine that caps the cells a tick may keep (for example the builds per tick); that changes the rates and is the next
structural step, not taken here.

Reproduce (from `research/`):

```sh
sbt "run lps-code export role 11 4 128 C.peg noacc fuse compact endent endonce star unfold tmp mirror lgap merge2 noG noR sound pts beta=13/32" \
    "run peg-cse-improve C.peg 2.45 65536 60 1000 10 out.peg out.json"
```

## 3g. Capping the cells a tick may keep (2026-09-27)

§3f left one arm per young layer: a young cell can be born at any of the 11 young layers, and every read of a young pointer
branches over them. The next step was a machine that caps the cells a tick may keep. Two ways were weighed:

- **Rank addressing** (a per-tick allocation counter; a cell named by its tick and its rank among the tick's allocations). A
  read branches over the K ranks, but finding the layer of rank k moves into shared bus rules, one per (rank, site, field).
  Estimated on the §3f compact grammar's dispatches: arms 99 % of today at K = 6, 87 % at 5; a gain only at K ≤ 4, below the
  catch-up rate of 2/β ≈ 4.9 letters per tick. Not built.
- **Fixed allocating layers** (built). `GapKmp.Core(noAlloc = true)` has no allocation site; in place of every branch that
  would store a new cell it has a stall (an empty body: the exclusive guards keep every later branch out). `RolePal.Options.
  youngPattern` (`ypat=`, one letter per young slot after IN: `A` the full step, `N` the non-allocating one) and `oldPattern`
  (`opat=`) place them. Domains shrink to the allocating layers with no bus (arms estimated at 60 % with 5 of 10). `CapSuite`
  checks that an N layer stalls exactly where the full step would store a cell and otherwise acts as the full step, and that
  the code backend compiles N slots to the interpreter's language.

**Every pattern fails the rates** (measured, `pool-grid 128 … beta=13/32 every=1025`, the §3e flags):

| machine | young pattern after IN | result |
|---|---|---|
| 11/4 | `ANANANANAN`, `ANANAANANA`, `ANAAANAANA` (5–7 of 10 allocating) | false at n 180 (the first A life, h 128) |
| 12/4 | `NANANANANAN`, `ANANANANANA` | false at n 180 |
| 13/4 | `NANANANNANAN`, `ANANANANANAN` (6 of 12) | false at n 180 |
| 11/4, old `ANN`, `NAN` / `NNA`, `NNN` | (the young full) | false at n 211 / 205 |

Why, two levels down (`cap-trace`, `young-alloc`, measured):

- **The young role is late at its first answer.** On `b a^178 b` the 13/4 machine builds its 128 letters at seven per tick
  (ticks 128–145, every N slot stalls), then spends ticks 146–163 in the pop cascade that ends the build, where every N slot
  stalls again, and the FIFO reversals and mismatches of the match phase stall it once more.
- **Almost every young step stores a cell.** Over 3,283 words ≤ 400, 29.8 % of the young steps store a new cell, 8.7 % act
  without one, 61.5 % idle: 77 % of the working steps allocate, and in half of the ticks with any, all 11 young steps store a
  cell. The gap matcher's structures are persistent: a build makes an entry, a rear cell and a list or K node; a pop that
  unfolds a gap pushes a K node; a reversal rebuilds a list cell by cell; a FIFO reversal makes a queue cell. Only `popL`,
  `takePop` and the fast matches are free. The old role idles 92.8 % of its steps, but its bursts after a handover need all
  four steps.

So with this matcher, 11 young steps that may store a cell are the floor, and the branching of a read with them. A smaller
fan-out needs a matcher whose pops do not allocate — for example unfolding a gap through cursors into the entries instead of
new list and K nodes — which is a redesign of `GapKmp`, not taken here.

Other angles, measured:

- **W₀ 64** (the fallback in `S` shrinks from 24,610 to 7,375 nodes; compact 3,474 rules, 125,760 nodes): 997 rules at 330,035
  nodes (×2.63), 5.7 % below §3f, and it passes every length ≤ 1,025. But a random palindrome of length 104 (seed 7) finds bank
  A of h 64 late at n 104, with or without `endonce`; β 27/64 needs W₀ ≥ 128. Not adopted.
- **Young 10 with every merge** (`merge2` and `merge3`) passes every length ≤ 1,025 at β 27/64 (it fails at 13/32, and with
  `merge2` alone), but its grammar is larger: 1,108 rules at 397,451 nodes at ×2.4 (compact 3,823 rules, 165,620 nodes).
- **The naming rounds** (`peg-cse-improve` with k 30–240, thresholds 300–3,000, 20 rounds) end at the same 996 rules at
  about 349,800 nodes.

The three machines' counterexamples (104, 180 and 211 letters) are in `src/test/resources/anchors-counterexamples.txt`;
every chosen machine answers them. The current best stays §3f.

Reproduce (from `research/`):

```sh
sbt "run pool-grid 128 3 3 3 0 3 11 beta=13/32 every=1025 r13/4:noacc,fuse,compact,endent,endonce,unfold,tmp,lgap,merge2,noG,noR,ypat=ANANANANANAN"
sbt "run cap-trace 13 128 b1a178b noacc fuse compact endent endonce unfold tmp lgap merge2 noG noR beta=13/32 ypat=ANANANANANAN"
sbt "run young-alloc 11 128 400 noacc fuse compact endent endonce unfold tmp lgap merge2 noG noR beta=13/32"   # add old for the old role
```

## 3h. Pops that store no cell (2026-09-27)

§3g ended on the need for pops that store no cell. Where the young role's busy steps go (`step-bigrams 11 4 128 every 300 …`,
2,075 words, measured):

| kind | share of busy steps | stores a cell |
|---|---:|---|
| builds (fast1–4, misZ, misL, mis, same-pop) | 33.4 % | yes (entry, rear cell, list or K node, replay cell) |
| pops: `rev` 15.5, `popG` 11.5, `takeRev1` 2.1, `popR` 1.8, `takeRev` 1.1, splices 2.4 | 34.4 % | yes |
| FIFO reversal | 7.0 % | yes (queue cell) |
| match mismatches outside `misZ` | 1.0 % | yes (K node) |
| fast matches, `rev-last`, `misZ`, same-pop, `popL`, `takePop`, `takeEmpty` | 24.2 % | no |

**The prize, measured with a what-if machine.** With `freepops` (`GapKmp.Core(stallPops = false)`) an N slot keeps the list and
K node sites and acts on every pop, stalling only on builds, the end, the FIFO reversal and mismatches — the rates a matcher with
free pops would reach with the same steps (`CapSuite` checks this stall rule). Every length ≤ 1,025 (seed 5):

| machine | allocating slots after IN | result |
|---|---|---|
| 11/4 | 4–9 of 10 | false at n 180–362 |
| 12/4 | 5, 7 of 11 / 8 of 11 | false at n 180, 360 / pass |
| 13/4 | 5 of 12 / 7, 8 of 12 | false at n 180 / pass |

On `b a^178 b` the pop cascade after the build now runs at full width and 13/4 answers at 180. By the arm model of §3g, 13/4
with 7 allocating slots would have about 85 % of today's arms and 12/4 with 8 about 89 % (estimates): the builds, the FIFO and
the mismatches still need 8 allocating young layers of 13.

**Why pops must store cells here.** A cell points only to cells born before it, and a role has a bounded set of registers.

- The rear of the live sequence is a run of entries in build order, consumed oldest first. After an entry is consumed the next
  one is newer, and nothing older points to it: a step either makes a cell (the reversal builds the run front first, one cell
  per entry) or walks back from a newer anchor, which is quadratic and breaks the real-time rates.
- A gap is built with its entry, before any context that unfolds it, and gaps nest (94 % of `popG` meet another GNODE at the
  head of the gap). Each nested unfold needs its continuation after the gap, and the link from one continuation to the next can
  only live in a cell made at the unfold (a frame), or be re-walked.

So pops that store no cell do not exist for this representation, and even free pops would buy about 15 % of the arms. A further
cut of the young role's branching needs a matcher that only walks toward older data during matching — KMP-style advances
through per-position data go the other way; none was found. The current best stays §3f.

## 4. The handoff's other routes, re-evaluated with these numbers

- **Worst-case catenable deques (§4.4 of the handoff).** The rate is set by accumulated work on random palindromes
  (throughput), not by pop spikes; a real-time deque does more work per operation and needs more fields and sites per
  layer. Not recommended.
- **Guarded allocation-site provenance (§5).** Register domains span every young layer because cells outlive their tick;
  a sound analysis can only prune cells born in the current tick. Measure the observed site sets per read before
  building it; little is expected.
- **Endpoint truncation (§6) and the eertree (§7).** Unchanged: the family `D^k` does not close, and an immutable
  descriptor cannot point to the palindrome created after it.
- **A two-letter build step** (paired entries) would halve the build allocations but double the ENTRY domain (+15 colours,
  helpers +30). Only after S1–S4, and only with a measured gain.
- **Fewer allocation sites per young step** is what ×2 would need: a young step allocates a list node, an entry, a stack
  node, a rear node and a FIFO cell (18 site fields per layer in the census). No construction is known that keeps the
  gap matcher's O(1) steps with fewer cell kinds; this is the open design question behind the ×2 point.

## 5. Reproduce

```sh
cd research
python3 probes/survivors.py out/pools-role-y15-o4-n999.peg.gz
python3 probes/survivors.py --cost out/pools-role-y15-o4.peg.gz 1000
python3 probes/keep_policy.py out/pools-role-y15-o4.peg.gz          # minutes
sbt "run overlap-profile 15 4 adversarial 600 32 role noacc compact endent endonce"            # E0
sbt "run lps-code export role 15 4 auto A-star.peg noacc fuse compact endent endonce star" \
    "run lps-check-file A-star.peg 9" "run lps-inline-curve A-star.peg 16 65536 curve.md 1000 out.peg out.json"   # E1
```
