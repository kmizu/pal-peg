# B2 — rate search for OverlapPal (two matcher banks, no flags worker; minAnchor 2)

Checks per rate pair (A steps / B steps): `run lps-overlap-sim 10 a b` (all words <= 10 vs oracle),
`run lps-overlap-rand 300 60 a b` (300 random words <= 121), `run overlap-profile a b switch 257` (349 words
around the bank switches 3W/2, 2W, 3W), `run overlap-profile a b structured 513` (122 structured words).
A violation is a wrong report bit at an answering tick, a wrong answering bank, a wrong output, a wrong
queue order or a replay not at the front; the backlog on false ticks is a statistic.

| A / B | all words <= 10 | 300 random <= 121 | switch <= 257 | structured <= 513 | max units/tick | max backlog | first failing input |
|---|---|---|---|---|---|---|---|
| 48 / 72 | ok | ok | 0 | 0 | 120 | 141 | — |
| 32 / 48 | ok | ok | 0 | 0 | 80 | 148 | — |
| 24 / 36 | ok | ok | 0 | 0 | 60 | 155 | — |
| 20 / 30 | ok | ok | 0 | 0 | 50 | 161 | — |
| 16 / 24 | ok | ok | 0 | 0 | 40 | 169 | — (60 random words <= 513 also ok) |
| 15 / 24 | ok | ok (also 100 random <= 513) | 0 (also 412 switch words <= 513) | 0 | 39 | 212 | — **adopted** |
| 15 / 23 | — | — | 0 | 0 | 38 | 171 | — (not fully validated) |
| 16 / 22 | ok | — | 0 | 1 | 38 | 173 | a structured word <= 513 (bank B) |
| 16 / 20 | ok | — | 21 | 15 | 36 | 178 | bank B late |
| 16 / 16 | fail | — | 154 | 63 | 32 | 191 | `abbbbbba` (8): bank B (h = 6) late at tick 8 |
| 14 / 24 | ok | — | 28 | 15 | 38 | 169 | bank A late |
| 13 / 24 | ok | — | 59 | 27 | 37 | 169 | bank A late |
| 12 / 18 | ok | fail | 113 | 53 | 30 | 183 | `aaaaaaaaaaaa` (12): bank A (h = 8) reports false at tick 12 (its first answering tick) |
| 8 / 12 and below | fail | fail | most | most | | | `aaa` (3): bank A (h = 2) late at tick 3 |

Adopted working rate: **A = 15, B = 24** (43 slots per letter). With the old site-bit backend: compact 12,048
rules / 870,025 rhs nodes / max rhs 594 / 4,835,137 bytes (`b2-overlap-old-a15-b24.json`); PEG = oracle on all
words <= 5 at 15/24 (Scala matcher), the exported grammar re-parsed and checked on all words <= 9, the Rust
runner correct on 40 raw words of 127..600 letters. The minimal rates are observed on these inputs, not proved.
