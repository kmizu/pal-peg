# rt-kmp (STRATEGY.md §3l), measured 2026-09-28

```
rt-kmp W0=128 beta=13/32, per young tick 3 builder + 3 matcher letters: 3283 words, 3002 banks, 0 late young windows, 0 wrong answers
-- builder letter (475774)
  buf    mean   5.72  p95   10  max   15
  cons   mean   7.75  p95   13  max   27
  entry  mean   1.00  p95    1  max    1
  hq     mean   3.31  p95    5  max    9
  node   mean   5.26  p95    7  max    9
  pair   mean   0.49  p95    1  max    2
  rot    mean   4.33  p95    8  max   13
  total  mean  27.86  p95   41  max   68
-- matcher letter (654968)
  buf    mean   1.32  p95    2  max    9
  cons   mean   0.69  p95    4  max    8
  hq     mean   0.77  p95    1  max    6
  node   mean   1.06  p95    2  max    7
  pair   mean   0.01  p95    0  max    2
  rot    mean   0.71  p95    3  max    8
  total  mean   4.55  p95   10  max   39
-- young tick (200558)
  buf    mean  16.61  p95   30  max   49
  cons   mean  19.96  p95   39  max   77
  entry  mean   2.37  p95    3  max    3
  hq     mean   9.88  p95   15  max   24
  node   mean  15.01  p95   23  max   32
  pair   mean   1.18  p95    4  max    8
  rot    mean  12.21  p95   23  max   40
  total  mean  77.23  p95  123  max  199
-- old tick (176192)
  buf    mean   1.45  p95    2  max    9
  cons   mean   0.79  p95    4  max    8
  hq     mean   0.53  p95    1  max    6
  node   mean   1.04  p95    2  max    7
  pair   mean   0.01  p95    0  max    2
  rot    mean   0.43  p95    3  max    8
  total  mean   4.25  p95   10  max   39
```

```
rt-kmp W0=128 beta=13/32, per young tick 3 builder + 3 matcher letters: 7554 words, 21267 banks, 0 late young windows, 0 wrong answers
-- builder letter (5131617)
  buf    mean   5.66  p95   10  max   15
  cons   mean   7.84  p95   13  max   27
  entry  mean   1.00  p95    1  max    1
  hq     mean   3.36  p95    5  max    9
  node   mean   5.32  p95    7  max    9
  pair   mean   0.56  p95    2  max    2
  rot    mean   4.42  p95    8  max   13
  total  mean  28.15  p95   40  max   68
-- matcher letter (7528714)
  buf    mean   1.37  p95    3  max    9
  cons   mean   0.70  p95    4  max    9
  hq     mean   0.74  p95    1  max    6
  node   mean   1.06  p95    2  max    7
  pair   mean   0.01  p95    0  max    2
  rot    mean   0.67  p95    3  max    9
  total  mean   4.55  p95   10  max   41
-- young tick (2149911)
  buf    mean  16.59  p95   30  max   49
  cons   mean  20.25  p95   39  max   83
  entry  mean   2.39  p95    3  max    3
  hq     mean  10.06  p95   15  max   24
  node   mean  15.22  p95   23  max   32
  pair   mean   1.34  p95    4  max    8
  rot    mean  12.43  p95   23  max   40
  total  mean  78.28  p95  125  max  210
-- old tick (2375830)
  buf    mean   1.53  p95    3  max    9
  cons   mean   0.82  p95    4  max    9
  hq     mean   0.52  p95    1  max    6
  node   mean   1.08  p95    2  max    7
  pair   mean   0.01  p95    0  max    2
  rot    mean   0.42  p95    3  max    9
  total  mean   4.39  p95   10  max   41
```

```
rt-kmp W0=128 beta=13/32, per young tick 3 builder + 2 matcher letters: 3283 words, 3002 banks, 3002 late young windows, 0 wrong answers
-- builder letter (475774)
  buf    mean   5.72  p95   10  max   15
  cons   mean   7.77  p95   13  max   27
  entry  mean   1.00  p95    1  max    1
  hq     mean   3.31  p95    5  max    9
  node   mean   5.26  p95    7  max    9
  pair   mean   0.49  p95    1  max    2
  rot    mean   3.96  p95    8  max   13
  total  mean  27.50  p95   40  max   68
-- matcher letter (577308)
  buf    mean   1.33  p95    2  max    9
  cons   mean   1.06  p95    4  max    8
  hq     mean   0.76  p95    1  max    6
  node   mean   1.13  p95    2  max    7
  pair   mean   0.01  p95    0  max    2
  rot    mean   0.84  p95    3  max    8
  total  mean   5.13  p95   10  max   39
-- young tick (200558)
  buf    mean  16.12  p95   28  max   44
  cons   mean  20.81  p95   41  max   78
  entry  mean   2.37  p95    3  max    3
  hq     mean   9.55  p95   14  max   21
  node   mean  14.81  p95   22  max   32
  pair   mean   1.18  p95    4  max    7
  rot    mean  11.46  p95   24  max   37
  total  mean  76.32  p95  123  max  195
-- old tick (176192)
  buf    mean   1.45  p95    2  max    9
  cons   mean   0.76  p95    4  max    8
  hq     mean   0.53  p95    1  max    6
  node   mean   1.04  p95    2  max    7
  pair   mean   0.01  p95    0  max    2
  rot    mean   0.40  p95    3  max    8
  total  mean   4.18  p95   10  max   39
```

```
rt-kmp W0=128 beta=13/32, per young tick 2 builder + 3 matcher letters: 3283 words, 3002 banks, 3002 late young windows, 0 wrong answers
-- builder letter (401116)
  buf    mean   5.70  p95   10  max   15
  cons   mean   7.14  p95   12  max   27
  entry  mean   1.00  p95    1  max    1
  hq     mean   3.30  p95    5  max    9
  node   mean   5.21  p95    7  max    9
  pair   mean   0.46  p95    1  max    2
  rot    mean   4.31  p95    8  max   13
  total  mean  27.12  p95   39  max   68
-- matcher letter (616264)
  buf    mean   1.34  p95    2  max    9
  cons   mean   0.74  p95    3  max    8
  hq     mean   0.75  p95    1  max    6
  node   mean   0.93  p95    2  max    7
  pair   mean   0.01  p95    0  max    2
  rot    mean   0.94  p95    3  max    8
  total  mean   4.71  p95   10  max   39
-- young tick (200558)
  buf    mean  14.25  p95   23  max   40
  cons   mean  15.82  p95   26  max   55
  entry  mean   2.00  p95    2  max    2
  hq     mean   8.45  p95   11  max   19
  node   mean  12.38  p95   17  max   25
  pair   mean   0.94  p95    2  max    6
  rot    mean  11.10  p95   16  max   29
  total  mean  64.93  p95   89  max  144
-- old tick (176192)
  buf    mean   1.45  p95    2  max    9
  cons   mean   0.83  p95    4  max    8
  hq     mean   0.53  p95    1  max    6
  node   mean   1.04  p95    2  max    7
  pair   mean   0.01  p95    0  max    2
  rot    mean   0.47  p95    3  max    8
  total  mean   4.32  p95   10  max   39
```

