# Inline curves of the S3/S2′ configurations (STRATEGY.md §3b)

Each row is one greedy `PegBudgetInline` run (`lps-inline-curve <compact.peg> 16 65536 …`); the node budget is a multiple of the
row's own compact grammar. All use `Code` by star (S1) and configuration A's options (noacc fuse compact endent endonce)
unless stated. Rates: role machine, *seeds* = the three sets of POOLS (seed 21 big set, seed 7, seed 11), *every* =
`pool-grid … every=1025` (the runner words at every length 3 … 1,025, 7,158 words).

| configuration | N compact | ×2 | ×3 | ×4 | ×8 | ×16 | N < 1,000 at (rhs nodes) | rates |
|---|---:|---:|---:|---:|---:|---:|---|---|
| 15/4 A, β 1/2, W₀ 32 (E1; POOLS, main) | 4,216 | 1,368 | 1,184 | 1,105 | 1,003 | 926 | ×8.28, 1,652,383 | seeds PASS; **every FAIL n 79** |
| 13/4 A, β 7/16, W₀ 32 | 3,812 | 1,243 | 1,077 | 1,005 | 911 | 838 | ×4.11, 674,895 | seed 21 FAIL n 71 |
| 13/4 A − endonce, β 7/16, W₀ 32 | 3,962 | 1,287 | 1,120 | 1,041 | 941 | 860 | ×5.04, 862,330 | seeds PASS, every PASS |
| **13/4 A, β 7/16, W₀ 64 (chosen)** | 3,860 | 1,257 | 1,090 | 1,018 | 919 | 845 | ×4.40, 724,780 | seeds PASS; every PASS (1,025 seeds 5 and 7, 2,049) |
| 13/4 A + unfold, β 7/16, W₀ 32 | 3,919 | 1,292 | 1,122 | 1,037 | 929 | 844 | ×4.77, 797,926 | every PASS (seeds not run) |
| 13/3 A + unfold, β 7/16, W₀ 32 | 3,730 | 1,231 | 1,068 | 987 | 883 | 802 | ×3.80, 588,081 | seeds PASS; every FAIL n 177 |
| 13/3 A + unfold − endonce, β 7/16, W₀ 32 | 3,880 | 1,272 | 1,109 | 1,025 | 921 | 837 | ×4.49, 726,010 | every FAIL n 361 |
| 13/3 A + unfold, β 7/16, W₀ 64 | 3,778 | 1,245 | 1,081 | 1,000 | 892 | 809 | ×4.01, 621,854 | every FAIL n 177 |
| 12/4 A + unfold, β 7/16, W₀ 64 | 3,745 | 1,235 | 1,071 | 991 | 884 | 800 | ×3.86, 581,793 | every FAIL n 155 |
| 12/3 A + unfold, β 13/32, W₀ 64 | 3,564 | 1,177 | 1,022 | 944 | 842 | 762 | ×3.22, 448,192 | seeds FAIL (n 96, 104, 222); runner n 121 |
