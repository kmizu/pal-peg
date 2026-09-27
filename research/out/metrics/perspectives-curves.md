# The §3c perspectives (STRATEGY.md §3c), measured 2026-09-27

All grammars: role machine, configuration A (noacc fuse compact endent endonce), `Code` by star, β = 7/16. Node budgets are
multiples of each grammar's own compact size (`lps-inline-curve <compact.peg> 16 65536 …`); compare absolute rhs nodes when the
compact sizes differ. The `tmp` rows are after the LpsCode fix of §3c (before it, `tmp` grammars referenced undefined site-field
rules).

## Encoding options on 13/4, W₀ 64

| variant | compact rules / rhs nodes | colours | ×2 | ×3 | ×4 | ×8 | 999 rules at (rhs nodes) |
|---|---|---:|---:|---:|---:|---:|---:|
| §3b (none) | 3,860 / 164,724 | 19 | 1,257 | 1,090 | 1,018 | 919 | 724,780 |
| `p3` | identical to §3b | 19 | 1,257 | 1,090 | 1,018 | 919 | 724,780 |
| `rk` | 3,882 / 182,863 | 30 | 1,256 | 1,084 | 1,017 | 909 | 800,723 |
| `rk p3` | 3,890 / 184,454 | 34 | 1,249 | 1,086 | 1,016 | 925 | 808,024 |
| `tmp` | 3,755 / 164,934 | 30 | 1,247 | 1,075 | 993 | 884 | 638,740 |
| `trie` dispatch | 3,865 / 242,352 | 19 | 1,209 | 1,047 | 980 | 895 | 876,054 |
| W₀ 128 (no option) | 3,954 / 166,023 | 19 | 1,286 | 1,115 | 1,041 | 935 | 820,882 |

## 12/4 + unfold + `tmp`, W₀ 128 (the §3c machine)

| budget | greedy | + naming common sub-expressions (`peg-cse-improve`, k 60) |
|---|---:|---:|
| ×2 | 1,256 | 1,235 |
| ×3 | 1,082 | 1,040 |
| ×3.4 | 1,040 | 1,007 |
| ×3.5 | 1,031 | **998 (531,531 rhs nodes, 2,283,029 bytes)** |
| ×3.6 | 1,022 | 1,001 |
| ×3.7 | 1,014 | 995 |
| ×4 | 992 | 978 |

Compact 3,739 rules, 152,266 rhs nodes, 28 colours; greedy 999 rules at 591,546 nodes (×3.88). Thresholds per budget: 700 (×2),
900 (×3), 1,200–1,500 (×3.4–×3.7), 1,700 (×4). The loop is not monotone in the budget (×3.6 ends at 1,001).

## Look-ahead priority (`lps-inline-curve … la=<λ>`) on `tmp` 13/4, W₀ 64

| λ | ×2 | ×3 | ×4 | 999 rules at |
|---|---:|---:|---:|---:|
| 0 (the greedy) | 1,247 | 1,075 | 993 | 638,740 |
| 0.05 | 1,404 | 1,215 | 1,110 | 887,968 |
| 0.2 | 1,580 | 1,371 | 1,186 | 1,014,965 |
| 0.5 | 1,737 | 1,478 | 1,316 | 1,296,427 |

## §3e (2026-09-27): the seven ideas, measured

999 rules reached at (greedy `lps-inline-curve`, β 7/16, W₀ 128, A + unfold + `tmp` unless stated):

| machine | rhs nodes at 999 |
|---|---:|
| young 12 / old 4 | 528,756 |
| young 11 / old 4 (rates fail) | 398,492 |
| young 10 / old 4 (rates fail) | 301,314 |
| 12/4 + `pairs` | 952,966 |
| 12/4 + `recolour` | 528,984 |
| 11/4 + `lgap` + R1 + U (passes the seed-21 set) | 520,517 |
| 11/4 + `lgap` + all of `merge2` | 865,767 |
| 11/4 + `lgap` + `merge2` + `merge3` | 1.49 M |
| **11/4 + `lgap` + U, β 13/32** | **458,189** |

The adopted grammar (11/4 + `lgap` + U, β 13/32, W₀ 128, mirrored fallback) with `peg-cse-improve`:

| budget | rules | rhs nodes | bytes |
|---|---:|---:|---:|
| ×2 | 1,098 | 342,699 | 1,577,434 |
| ×2.4 | 1,019 | 411,109 | 1,816,505 |
| ×2.5 | 1,003 | 428,030 | 1,877,649 |
| **×2.56** | **994** | **438,487** | **1,905,775** |
| ×3 | 951 | 512,816 | 2,183,116 |

## §3f (2026-09-27): the branching of one read, measured

The §3e machine; `peg-cse-improve C.peg <x> 65536 60 1000 10`:

| options | compact rules / nodes / colours | x | rules | rhs nodes | bytes |
|---|---|---:|---:|---:|---:|
| none (§3e) | 3,529 / 171,517 / 26 | 2.56 | 994 | 438,487 | 1,905,775 |
| `sound` | 3,529 / 161,077 / 26 | 2.56 | 1,004 | 411,152 | 1,811,114 |
| `sound` | | 2.65 | 987 | 426,202 | 1,849,149 |
| `pts` | 3,521 / 153,047 / 22 | 2.45 | 992 | 373,810 | 1,619,754 |
| `pts` | | 2.56 | 975 | 391,673 | 1,697,712 |
| `sound pts` | 3,521 / 143,051 / 22 | 2.2 | 1,044 | 314,246 | 1,403,402 |
| `sound pts` | | 2.35 | 1,018 | 336,165 | 1,504,548 |
| **`sound pts`** | | **2.45** | **996** | **349,849** | **1,542,201** |
| `sound pts` | | 2.56 | 984 | 365,203 | 1,582,784 |
| `sound pts` | | 2.8 | 949 | 399,416 | 1,730,216 |
| `sound pts` | | 3.0 | 933 | 428,580 | 1,856,960 |

## §3g (2026-09-27): capping the cells of a tick, measured

Grammars of the configurations that were measured (`peg-cse-improve <x> 65536 60 1000 10`, sound pts); none replaced §3f:

| machine | compact rules / nodes / colours | x | rules | rhs nodes | bytes | rates |
|---|---|---:|---:|---:|---:|---|
| §3f (11/4 `lgap` U, β 13/32, W₀ 128) | 3,521 / 143,051 / 22 | 2.45 | 996 | 349,849 | 1,542,201 | pass |
| same at W₀ 64 | 3,474 / 125,760 / 22 | 2.63 | 997 | 330,035 | 1,502,265 | false at n 104 (seed 7) |
| same at W₀ 64 | | 2.7 | 990 | 338,974 | 1,521,178 | |
| 10/4 `lgap` `merge2` `merge3`, β 27/64 | 3,823 / 165,620 / 20 | 2.4 | 1,108 | 397,451 | 1,697,593 | every ≤ 1,025 pass |

Allocation (`young-alloc 11 128 400 …`, 3,283 words): young steps 29.8 % storing a cell, 8.7 % acting without one, 61.5 %
idle; ticks with any storing step: 11 of 11 in 50.2 %, 10 in 17.2 %. Old role: 1.9 % / 5.3 % / 92.8 %.

## §3h (2026-09-27): the free-pops what-if, measured

`pool-grid 128 3 3 3 0 3 2 beta=13/32 every=1025 r<ky>/4:<§3e flags>,freepops,ypat=<P>` (seed 5):

| machine | pattern after IN | result |
|---|---|---|
| 11/4 | NANANNANAN, ANANANANAN, ANANAANANA, ANAAANAANA, AANAAAANAA, AAAAANAAAA | false at n 180, 180, 180, 180, 187, 362 |
| 12/4 | NANANANANAN, ANAANANAANA / ANAAANAAANA | false at n 180, 360 / pass (7,158 words) |
| 13/4 | NANANANNANAN / ANANAANANANA, ANAANAANAANA | false at n 180 / pass, pass |

## §3j (2026-09-28): arms by kind and the burst profile

Arms of the §3f compact grammar by the kind of cell read: K 33.1 %, list 20.4 %, tag tests 18.6 %, register leaves 10.0 %, R 9.6 %,
entry 6.4 %, queue 1.9 %.

`kind-profile 11 128 400 noacc fuse compact endent endonce unfold tmp lgap merge2 noG noR beta=13/32` (3,283 words, 271,117 ticks
with young work): per tick list 5.63 / p95 11 / max 11, K 1.81 / 11 / 11, R 1.91 / 9 / 11, entry 2.98 / 11 / 11, queue 3.40 / 12 / 12;
longest runs (p99 / max) list 172 / 508, K 60 / 254, R 34 / 255, entry 128 / 360, queue 60 / 208; busy width 11 in 84.2 % of the ticks.
