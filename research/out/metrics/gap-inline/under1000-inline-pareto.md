# PegBudgetInline on `out/under1000-gap-a10-b13.peg` (source commit ed7e5a2)

Budgets are relative to the compacted input; every retained rule keeps its extents (PegBudgetInlineSuite).

| grammar | N | rhs nodes | max rhs | bytes | distinct DAG nodes | dispatch arms | eliminated | seconds |
|---|---|---|---|---|---|---|---|---|
| compact (input) | 5902 | 208201 | 204 | 1180743 | 49967 | 29084 | 0 | 0 |
| inline nodes x1.0 maxRhs 4096 | 3378 | 208197 | 474 | 1139664 | 47442 | 29084 | 2524 | 58 |
| inline nodes x1.25 maxRhs 4096 | 2319 | 260183 | 813 | 1357764 | 46383 | 33684 | 3583 | 134 |
| inline nodes x1.5 maxRhs 4096 | 2086 | 312263 | 974 | 1572831 | 46150 | 38501 | 3816 | 153 |
| inline nodes x2.0 maxRhs 4096 | 1816 | 416054 | 1943 | 2070035 | 45880 | 52323 | 4086 | 141 |
| inline nodes x3.0 maxRhs 4096 | 1561 | 624182 | 3432 | 2883917 | 45625 | 67904 | 4341 | 159 |
| inline nodes x4.0 maxRhs 4096 | 1494 | 831817 | 4078 | 3627605 | 45557 | 89421 | 4408 | 164 |
| inline nodes x3.0 maxRhs 16384 | 1561 | 624146 | 5232 | 2882311 | 45625 | 68168 | 4341 | 161 |
| inline nodes x4.0 maxRhs 16384 | 1492 | 832771 | 6086 | 3614037 | 45555 | 89919 | 4410 | 151 |
| inline bytes x1.0 maxRhs 4096 | 2684 | 228253 | 489 | 1166620 | 46747 | 30258 | 3218 | 115 |
