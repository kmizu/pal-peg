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
