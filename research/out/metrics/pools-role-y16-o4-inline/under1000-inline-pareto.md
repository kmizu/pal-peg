# PegBudgetInline on `out/pools-role-y16-o4.peg` (source commit 7c0ab81)

Budgets are relative to the compacted input; every retained rule keeps its extents (PegBudgetInlineSuite).

| grammar | N | rhs nodes | max rhs | bytes | distinct DAG nodes | dispatch arms | eliminated | seconds |
|---|---|---|---|---|---|---|---|---|
| compact (input) | 4987 | 241021 | 241 | 1325770 | 54086 | 35110 | 0 | 0 |
| inline nodes x1.0 maxRhs 4096 | 2917 | 241019 | 525 | 1292080 | 52015 | 35110 | 2070 | 165 |
| inline nodes x1.25 maxRhs 4096 | 1972 | 301060 | 1440 | 1541946 | 51070 | 40575 | 3015 | 505 |
| inline nodes x1.5 maxRhs 4096 | 1774 | 361161 | 1494 | 1751071 | 50872 | 43839 | 3213 | 558 |
| inline nodes x2.0 maxRhs 4096 | 1549 | 481585 | 2550 | 2319259 | 50647 | 59253 | 3438 | 527 |
| inline nodes x3.0 maxRhs 4096 | 1344 | 722752 | 4084 | 3261688 | 50441 | 83826 | 3643 | 533 |
| inline nodes x4.0 maxRhs 4096 | 1301 | 954309 | 4096 | 4071895 | 50398 | 99502 | 3686 | 502 |
| inline nodes x3.0 maxRhs 16384 | 1344 | 721435 | 5721 | 3264319 | 50441 | 83750 | 3643 | 508 |
| inline nodes x4.0 maxRhs 16384 | 1280 | 963368 | 15687 | 4068795 | 50377 | 106940 | 3707 | 510 |
| inline bytes x1.0 maxRhs 4096 | 2325 | 258688 | 639 | 1314564 | 51422 | 36239 | 2662 | 166 |
