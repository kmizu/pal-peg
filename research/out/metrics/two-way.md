# two-way (STRATEGY.md §3m), measured 2026-09-28

```
two-way W0=128 beta=13/32, budget 18 steps a young tick: 3283 words, 3002 banks (1091 two-instance, 1043 switches, at most 4 in a bank), wrong 0, late 0, young windows too slow 14
  preprocessing steps a pattern letter: mean 5.18, max 5.96 (p50 5.23, p95 5.80, p99 5.80, max 5.96)
  matcher work a letter (comparisons): max 3
  cells a young tick (N, R and the replay list): mean 6.19, max 6.32
  steps a young tick needed (preprocessing + matching over the window): max 17.62 (p50 14.98, p95 17.23, p99 17.32, max 17.62)
```

```
two-way W0=128 beta=13/32, budget 19 steps a young tick: 3283 words, 3002 banks (1091 two-instance, 1043 switches, at most 4 in a bank), wrong 0, late 0, young windows too slow 0
  preprocessing steps a pattern letter: mean 5.18, max 5.96 (p50 5.23, p95 5.80, p99 5.80, max 5.96)
  matcher work a letter (comparisons): max 3
  cells a young tick (N, R and the replay list): mean 6.19, max 6.32
  steps a young tick needed (preprocessing + matching over the window): max 18.15 (p50 14.98, p95 17.23, p99 17.32, max 18.15)
```

```
two-way W0=128 beta=13/32, budget 18 steps a young tick: 7554 words, 21267 banks (6898 two-instance, 7316 switches, at most 6 in a bank), wrong 0, late 0, young windows too slow 30
  preprocessing steps a pattern letter: mean 5.18, max 6.08 (p50 5.19, p95 5.80, p99 5.82, max 6.08)
  matcher work a letter (comparisons): max 3
  cells a young tick (N, R and the replay list): mean 6.19, max 6.35
  steps a young tick needed (preprocessing + matching over the window): max 17.89 (p50 14.98, p95 17.26, p99 17.26, max 17.89)
```

```
two-way W0=128 beta=13/32, budget 19 steps a young tick: 7554 words, 21267 banks (6898 two-instance, 7316 switches, at most 6 in a bank), wrong 0, late 0, young windows too slow 0
  preprocessing steps a pattern letter: mean 5.18, max 6.08 (p50 5.19, p95 5.80, p99 5.82, max 6.08)
  matcher work a letter (comparisons): max 3
  cells a young tick (N, R and the replay list): mean 6.19, max 6.35
  steps a young tick needed (preprocessing + matching over the window): max 18.17 (p50 14.98, p95 17.26, p99 17.26, max 18.17)
```

