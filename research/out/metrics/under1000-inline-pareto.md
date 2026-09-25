# PegBudgetInline on the baseline `overlap-code-a19-b28` (8,336 rules; source commit ed7e5a2)

Budgets are relative to the compacted input; every retained rule keeps its extents (PegBudgetInlineSuite). Rows as
printed by `lps-inline-sweep out/under1000-baseline.peg …` (the same numbers as the per-row json files that this
sweep writes; see `under1000-inline-nodes-x1.0-r4096.json` etc. for the baseline rows regenerated at the end).

| grammar | N | rhs nodes | max rhs | bytes | distinct DAG nodes | dispatch arms | eliminated | seconds |
|---|---|---|---|---|---|---|---|---|
| compact (input) | 8336 | 431165 | 617 | 2502032 | 86834 | 65866 | 0 | 0 |
| inline nodes x1.0 maxRhs 4096 | 5557 | 431164 | 699 | 2458633 | 84054 | 66258 | 2779 | 118 |
| inline nodes x1.25 maxRhs 4096 | 3301 | 538921 | 1443 | 2885277 | 81798 | 74233 | 5035 | 322 |
| inline nodes x1.5 maxRhs 4096 | 2981 | 646689 | 1657 | 3394875 | 81478 | 81724 | 5355 | 347 |
| inline nodes x2.0 maxRhs 4096 | 2670 | 861873 | 1849 | 4429365 | 81167 | 100736 | 5666 | 401 |
| inline nodes x3.0 maxRhs 4096 | 2337 | 1293283 | 3937 | 6444888 | 80834 | 144399 | 5999 | 448 |
| inline nodes x4.0 maxRhs 4096 | 2198 | 1721040 | 4094 | 8336512 | 80694 | 190874 | 6138 | 465 |
| inline nodes x3.0 maxRhs 16384 | 2335 | 1292477 | 4374 | 6447730 | 80832 | 142454 | 6001 | 438 |
| inline nodes x4.0 maxRhs 16384 | 2151 | 1721424 | 14561 | 8347898 | 80647 | 195905 | 6185 | 485 |
| inline bytes x1.0 maxRhs 4096 | 4171 | 450469 | 725 | 2489908 | 82667 | 67884 | 4165 | 269 |

The same sweep on the gap grammar at the withdrawn rate 8/11 (4,380 rules, 21 universal-step layers; kept only to show
the compression ratio of the gap core's layers): x1.0 → 2357, x1.25 → 1661, x1.5 → 1505, x2.0 → 1314, x3.0 → 1175,
x4.0 → 1126 (maxRhs 4096; 529,024 rhs nodes, 2,252,550 bytes at x4.0). The final grammar's sweep is
`gap-inline/under1000-inline-pareto.md`.
