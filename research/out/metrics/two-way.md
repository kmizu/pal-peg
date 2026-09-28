# two-way (STRATEGY.md §3o), measured 2026-09-28

```
two-way W0=128 beta=13/32, budget 32 steps a young tick: 7554 words, 21267 banks (6899 two-instance, 5674 switches, at most 1 in a bank), wrong 0, late 0, young windows too slow 59
  preprocessing steps a pattern letter: mean 7.58, max 11.83 (p50 8.34, p95 10.57, p99 10.58, max 11.83)
  matcher work a letter (comparisons): max 3
  cells a young tick (N, N' and the replay list): mean 6.19, max 6.35
  steps a young tick needed (preprocessing + matching over the window): max 31.61 (p50 22.88, p95 28.23, p99 28.38, max 31.61)
```

```
two-way W0=128 beta=13/32, budget 33 steps a young tick: 7554 words, 21267 banks (6899 two-instance, 5674 switches, at most 1 in a bank), wrong 0, late 0, young windows too slow 0
  preprocessing steps a pattern letter: mean 7.58, max 11.83 (p50 8.34, p95 10.57, p99 10.58, max 11.83)
  matcher work a letter (comparisons): max 3
  cells a young tick (N, N' and the replay list): mean 6.19, max 6.35
  steps a young tick needed (preprocessing + matching over the window): max 32.50 (p50 22.88, p95 28.23, p99 28.38, max 32.50)
```

