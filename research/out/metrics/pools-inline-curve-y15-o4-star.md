# PegBudgetInline curve of `out/pools-role-y15-o4.peg + codeByStar` (maxRhs 65536)

One greedy run (the eliminations under a smaller node budget are a prefix of those under a larger one). Input: 4216 rules, 199553 rhs nodes, 1078610 bytes. Final: 926 rules, 3190264 nodes (116 s).

| node budget | N | rhs nodes | byte estimate |
|---|---:|---:|---:|
| x1.00 | 2496 | 199552 | 1053039 |
| x1.25 | 1737 | 249359 | 1263772 |
| x1.50 | 1569 | 299051 | 1470375 |
| x2.00 | 1368 | 398916 | 1941553 |
| x3.00 | 1184 | 598546 | 2766002 |
| x4.00 | 1105 | 796176 | 3482100 |
| x6.00 | 1034 | 1195906 | 5030548 |
| x8.00 | 1003 | 1595311 | 6737011 |
| x12.00 | 957 | 2392535 | 10035173 |
| x16.00 | 926 | 3190264 | 13766232 |

first N < 1000: 999 rules at 1652383 nodes (x8.28), byte estimate 6966545
first N < 1100: 1099 rules at 818060 nodes (x4.10), byte estimate 3558073
first N < 1200: 1199 rules at 576046 nodes (x2.89), byte estimate 2703402
