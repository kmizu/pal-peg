# Generated grammars

- `anchors-role-y11-o4-w128-n994.peg.gz` — **the current best (2026-09-27, `STRATEGY.md` §3e): 994 rules.** Role machine
  11/4, configuration A + `unfold` + `tmp` + `lgap` + the unfold-then-popL merge (`merge2 noG noR`), β = 13/32, W₀ = 128 (first
  machine answer 180), mirrored fallback, greedy inlining and named common sub-expressions at ×2.56: 438,487 rhs nodes, max rhs
  24,605, 1,905,775 bytes (135,554 gzipped). Rates: every length ≤ 1,025 (seeds 5 and 7), the three seeds, 25 sampled lengths
  ≤ 9,000 and every length ≤ 2,049. No undefined reference; re-parsed = oracle on all words ≤ 9; Rust runner 0 mismatches on the
  168 words of §3c and 799 words of every length 3–200. `metrics/anchors-role-y11-o4-w128-n994.json`. Reproduce: `run lps-code
  export role 11 4 128 C.peg noacc fuse compact endent endonce star unfold tmp mirror lgap merge2 noG noR beta=13/32` then
  `run peg-cse-improve C.peg 2.56 65536 60 1000 10 out.peg out.json`.
- `anchors-role-y11-o4-w128.peg.gz` — the same before inlining: 3,529 rules, 171,517 rhs nodes, 26 colours.
- `anchors-role-y12-o4-w128-mirror-n996.peg.gz` — the best of §3d (2026-09-27, `STRATEGY.md` §3d): 996 rules. The §3c
  machine with the mirrored fallback (`lps-code … mirror`), greedy inlining and named common sub-expressions at ×2.7:
  472,260 rhs nodes, max rhs 25,613, 2,033,703 bytes (141,261 gzipped). No undefined reference; re-parsed = oracle on all words ≤ 9;
  Rust runner 0 mismatches on 168 words of 24–9,000 letters and 792 words of every length 3–200. Rates: those of §3c.
  `metrics/anchors-role-y12-o4-w128-mirror-n996.json`. Reproduce: `run lps-code export role 12 4 128 C.peg noacc fuse compact
  endent endonce star unfold tmp mirror beta=7/16` then `run peg-cse-improve C.peg 2.7 65536 60 1100 10 out.peg out.json`.
- `anchors-role-y12-o4-w128-mirror.peg.gz` — the same before inlining: 3,648 rules, 175,153 rhs nodes.
- `anchors-role-y12-o4-w128-n998.peg.gz` — the best of §3c (2026-09-27, `STRATEGY.md` §3c): 998 rules. Role machine
  12/4, configuration A + `unfold` + `tmp`, `Code` by star, β = 7/16, W₀ = 128 (first machine answer 184), greedy inlining and
  named common sub-expressions (`peg-cse-improve`) at ×3.5: 531,531 rhs nodes, max rhs 7,035, 2,283,029 bytes (149,481 gzipped).
  No undefined reference; re-parsed = oracle on all words ≤ 9; Rust runner on 168 words of 24–9,000 letters: 0 mismatches.
  Rates: three seeds, every length ≤ 2,049, 25 sampled lengths ≤ 9,000. `metrics/anchors-role-y12-o4-w128-n998.json`,
  `metrics/perspectives-curves.md`. Reproduce: `run lps-code export role 12 4 128 C.peg noacc fuse compact endent endonce star
  unfold tmp beta=7/16` then `run peg-cse-improve C.peg 3.5 65536 60 1300 10 out.peg out.json`.
- `anchors-role-y12-o4-w128.peg.gz` — the same machine before inlining: 3,739 rules, 152,266 rhs nodes.
- `anchors-role-y13-o4-w64-n999.peg.gz` — the best of §3b (2026-09-26, `STRATEGY.md` §3b): 999 rules. Role machine
  13/4, configuration A, `Code` by star, anchors at β = 7/16 (the staged clock), W₀ = 64 (first machine answer 92), inlined at
  ×4.40: 724,780 rhs nodes, max rhs 21,425, 2,984,353 bytes (167,877 gzipped). Re-parsed = oracle on all words ≤ 9; Rust runner
  on the 140 raw words and the 16 palindromes the grids found: 0 mismatches. Rates: three seeds and every length ≤ 2,049;
  25 sampled lengths up to 9,000 (283 words); the runner on 12 words of 4,097 and 9,000 letters: 0 mismatches.
  `metrics/anchors-role-y13-o4-w64-n999.json`, curves of all S3/S2′ configurations `metrics/anchors-inline-curves.md`.
  Reproduce: `run lps-code export role 13 4 64 A.peg noacc fuse compact endent endonce star beta=7/16` then
  `run lps-inline-curve A.peg 16 65536 curve.md 1000 out.peg out.json`.
- `anchors-role-y13-o4-w64.peg.gz` — the same machine before inlining: 3,860 rules, 164,724 rhs nodes.
- **The two `pools-role-y15-o4*-n999` grammars below answer false on a 79-letter palindrome** (STRATEGY.md §3b); kept for
  the record.
- `pools-role-y15-o4-star-n999.peg.gz` — the smallest 999-rule grammar of E1 (2026-09-26, `STRATEGY.md`): the same machine as
  below with non-recursive code helpers (`lps-code … star`), inlined at ×8.28: 999 rules, 1,652,383 rhs nodes, max rhs
  12,773, 6,754,563 bytes. Re-parsed = oracle on all words ≤ 9; Rust runner on the 140 raw words: 0 mismatches.
  `metrics/pools-role-y15-o4-star-n999.json`, curve `metrics/pools-inline-curve-y15-o4-star.md`.
- `pools-role-y15-o4-n999.peg.gz` — **the current best (2026-09-25, `POOLS.md`): 999 rules.** The role machine (`RolePal`,
  young pool 15, old pool 4, options noacc fuse compact endent endonce, W0 = 32, first machine answer 48) compiled by the
  site-code backend and inlined by `PegBudgetInline` to the first grammar of the greedy run with fewer than 1,000 rules
  (node budget ×9.75): 1,945,495 rhs nodes, max rhs 16,224, 7,958,678 bytes. Re-parsed = oracle on all words ≤ 9; Rust runner
  on 140 raw words of 24–601 letters: 0 mismatches. `metrics/pools-role-y15-o4-n999.json` (sha256 of both files),
  `metrics/pools-inline-curve-y15-o4.md` (the whole N-versus-nodes curve). Reproduce: `run lps-code export role 15 4 auto
  out/pools-role-y15-o4.peg noacc fuse compact endent endonce` then `run lps-inline-curve out/pools-role-y15-o4.peg 32 65536
  curve.md 1000 out.peg out.json`.
- `pools-role-y15-o4.peg.gz` — the same machine before inlining: 4,216 rules, 199,511 rhs nodes, 1.08 MB
  (`metrics/pools-role-y15-o4.json`).
- **Warning:** the two `under1000-gap-a10-b13*` grammars below answer false on a palindrome of 128 letters (`POOLS.md` §0).
- `under1000-gap-a10-b13.peg.gz` — **the current best (2026-09-25, `UNDER1000.md`)**: the overlap recognizer with the
  gap matcher (`GapKmp`: persistent nested gap sequences instead of Kmp2's failure-chain walks, A 10 / B 13 steps
  per letter) compiled by the site-code backend (16 colours, W0 = 16, the machine answers from n = 24) and compacted:
  5,902 rules, 208,201 right-hand-side nodes, max rhs 204, 1.18 MB. `run lps-code export gap 10 13 auto
  out/under1000-gap-a10-b13.peg`. Checks: interpreter on 788 adversarial (≤ 600), 475 switch (≤ 1025), 122 structured
  (≤ 1025) and 60 random words without violation; re-parsed on all words ≤ 9; Scala matcher on 7 words of 23–64
  letters; Rust runner on 152 raw words of 24–601 letters (0 mismatches). Metrics: `metrics/under1000-gap-a10-b13.json`
  (`-trie.json`: the trie dispatch, 5,906 rules, no gain).
- `under1000-gap-a10-b13-inline-*.peg.gz` — the same grammar after `PegBudgetInline` (every retained rule keeps its
  extents): nodes ×1.25 → 2,319 rules / 260,183 nodes / 1.36 MB; nodes ×4 → 1,494 rules / 831,817 nodes / 3.63 MB
  (the smallest N of this repository, at four times the nodes; `metrics/gap-inline/under1000-inline-pareto.md`).
  The inliner's Pareto tables: `metrics/under1000-inline-pareto.md` (baseline), `metrics/gap-inline/` (gap core).
- `overlap-code-a19-b28.peg.gz` — the previous best (`BREAKTHROUGH.md`): two KMP matcher banks on overlapping anchors
  (`OverlapPal`, A 19 / B 28 steps per letter, no flags worker) compiled by the site-code backend (`LpsCode`,
  31 colours, W0 = 32, the machine answers from n = 48, bounded fallback below) and compacted: 8,336 rules,
  431,165 right-hand-side nodes, 2.50 MB. `run lps-code export overlap 19 28 auto out/overlap-code-a19-b28.peg`.
  Checks: interpreter on 788 adversarial, 412 switch and 122 structured words up to 600 letters; re-parsed on
  all words <= 9; Scala matcher on `b a^k b` words at 47–49; Rust runner on 112 adversarial raw words (47–600).
  Metrics: `metrics/ab-overlap-code-a19-b28.json`.
- `pal3-code-m12-f40.peg.gz` — PAL3 12/40 with the warmup (W0 = 64) and the site-code backend: 15,967 rules
  (`metrics/a3-pal3-code-m12-f40.json`); Rust runner on the same 112 adversarial words.
- `pal2-m12-f40.peg.gz` — the `LpsPal2` grammar (33,019 rules) from before the compression plan; validated on
  structured and random words only, not on the adversarial family.
- `metrics/` — the JSON records of every measurement; `overlap-rate-search.md` and `task3-rate-search.md` the
  rate searches with their counterexamples. The `*a15-b24*` and `*m12-f36*` records are of grammars that were
  **withdrawn** (late first answers on `b a^(n-2) b`, a missed flags deadline); those grammars are no longer here.

Syntax of the Rust plain-PEG runner (`Name = expr;`, `"lit"`, `.`, `!`, `&`, `*`, `/`); start symbol `S`.
`zcat out/overlap-code-a19-b28.peg.gz > /tmp/pal.peg && ../runner/target/release/plain-peg-runner /tmp/pal.peg abba abab`.
