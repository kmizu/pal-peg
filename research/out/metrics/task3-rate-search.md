# Task 3 — flags rate search for LpsPal3 (matchers fixed at 12 steps)

`run lps-pal3-sim 10 12 <f>` (all words <= 10) and `run lps-pal3-rand 300 60 12 <f>` (300 random words <= 121, seed 1).

| fSteps | all words <= 10 | 300 random <= 121 | first failing input |
|---|---|---|---|
| 40 | ok | ok | — (also 100 random words <= 513: ok) |
| 36 | ok | ok | — (100 random words <= 513: ok; structured words <= 513: no deadline violation, at most 36 flags units used in a tick) |
| 34 | ok | ok | — (long-word and deadline checks below) |
| 32 | ok | fail | `aabbaabbaaaabbaaaaaabbabbaabbbaaababaaaaaaaabaabbaabbabbaabbabbaabbaabaaaaaaaababaaabbbaabbabbaaaaaabbaaaabbaabbaa` (113 letters) |
| 28 | ok | fail | `baabbabaaaabaaaababaabaabbabbaabbabbaabaababaaaabaaaababbaab` (60 letters) |
| 24 | fail | fail | `aaaaaaa` (the S = 1, r = 3 job of four letters within one tick) |

PAL2 (with SKIP) needed 40: it failed at 32 on `aaaaaaa` too (|w| = 7). The truncation removes the
`lo` skip steps and the chain below `lo`, worth 4 flags steps per letter here; the remaining rate is
the forward view + table + matching of the job (≈ 7|y| local steps) against the deadline S.
