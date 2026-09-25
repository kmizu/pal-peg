# PegBudgetInline curve of `out/r154-A.peg` (maxRhs 65536)

One greedy run (the eliminations under a smaller node budget are a prefix of those under a larger one). Input: 4216 rules, 199511 rhs nodes, 1078568 bytes. Final: 878 rules, 6346794 nodes (311 s).

| node budget | N | rhs nodes | byte estimate |
|---|---:|---:|---:|
| x1.00 | 2499 | 199507 | 1053026 |
| x1.25 | 1747 | 249213 | 1266897 |
| x1.50 | 1577 | 299003 | 1447146 |
| x2.00 | 1377 | 398621 | 1922124 |
| x3.00 | 1194 | 596775 | 2791991 |
| x4.00 | 1120 | 796230 | 3509186 |
| x6.00 | 1052 | 1196089 | 5064031 |
| x8.00 | 1023 | 1582180 | 6705420 |
| x12.00 | 977 | 2385294 | 10083624 |
| x16.00 | 946 | 3179139 | 13814327 |
| x24.00 | 905 | 4740295 | 20324711 |
| x32.00 | 878 | 6346794 | 26552136 |

first N < 1000: 999 rules at 1945495 nodes (x9.75), byte estimate 8211904
first N < 1100: 1099 rules at 887455 nodes (x4.45), byte estimate 3829319
first N < 1200: 1199 rules at 587999 nodes (x2.95), byte estimate 2753720
