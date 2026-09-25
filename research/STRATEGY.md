# Below 999 rules: what the grammar is made of, and the levers that move it (2026-09-26)

A strategy document on top of main `75c8494` (the 999-rule grammar of `POOLS.md`), following the routes of
`pal-peg-under-1000-claude-code-c4b38c5.md`. It ranks levers by measured evidence and names, for each, the cheapest
experiment that can falsify it. **Not a Lean proof; the theorem in `../proof` is about the automaton.** Numbers are
labelled *measured*, *estimate* or *target*.

## 0. Where we are (measured, `POOLS.md`)

The role machine 15/4 with the options noacc fuse compact endent endonce: 4,216 rules compact; the greedy budgeted
inliner gives 1,377 (×2 nodes), 1,194 (×3), 1,120 (×4), **999 (×9.75, 1,945,495 rhs nodes, 8.0 MB)**, 946 (×16), 878
(×32). The handoff prefers a compact grammar (×2–×3); that is 195–378 rules away.

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
