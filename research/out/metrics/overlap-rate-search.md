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

## Correction after the final review (adversarial start-up words)

The families above never made the bank's *first* answer depend on the pattern `a^(h-1) b` — the most expensive
table build for `Kmp2` (about 4 steps per `a`: load, entry, one-cell queue reversal, compare again; then a
full-chain back-up of 2 steps per unit on the `b`) — so 15/24 was accepted although the family `b a^(n-2) b`
(a palindrome at every length; the bank answering at n has that pattern) breaks it: at 15/24 the interpreter
answers false at n = 48–52, 64–65, 96–106, 128–133, 192–199 (`overlap-profile 15 24 adversarial 300 32`:
84 of 456 words with violations; the exported 6,960-rule grammar rejects those palindromes too — the backend
is faithful, the machine is late). `OverlapProfile.adversarialWords` now contains `b a^(n-2) b` for every n and
`a^i b a^j b a^i` around the anchors, and the rate search is redone with it below.

Start-up accounting (from the per-operation costs of `Kmp2`, not a proof): a bank with pattern length h and
first answer n0 must, within n0 − h ticks, build the table (≤ ~6 steps per letter incl. the back-ups and the
entry-queue reversals), reverse the archive (h + 1), match the k = 2h − n0 replayed letters (2 each: load,
compare) and the n0 − h new letters (3 each: reversal, load, compare). Family A (h = W, n0 = 3W/2, W/2 ticks):
(6W + W + 2·W/2 + 3·W/2) / (W/2) = 19 steps per tick. Family B (h = 3W/2, n0 = 2W, W/2 ticks, k = W):
(9W + 1.5W + 2W + 1.5W) / (W/2) = 28 steps per tick. Measured (all families, minAnchor 2 and 32):

| A / B | adversarial ≤ 300 | switch ≤ 257 | structured ≤ 513 | note |
|---|---|---|---|---|
| 15 / 24 | 84 violations | 0 | 0 | **invalid** (the previous "adopted" row) |
| 16 / 28 | 38 violations | 0 | — | bank A late |
| 17 / 28 | 20 violations | 0 | — | bank A late |
| 18 / 28 | 7 violations | 0 | — | bank A late |
| 19 / 25 | 21 violations | 0 | — | bank B late |
| 19 / 26 | 13 violations | 0 | — | bank B late |
| 19 / 27 | 3 violations | 0 | — | bank B late |
| **19 / 28** | 0 | 0 | 0 | = the accounting's 19 / 28; **adopted** (full validation below) |
| 20 / 30 | 0 | 0 | 0 | |
| 24 / 36 | 0 | 0 | 0 | |

Full validation of 19/28: all words <= 10; 300 random <= 121; 100 random <= 513; 788 adversarial words <= 600
(minAnchor 2 and 32); 412 switch words <= 513 and 122 structured words <= 513 (minAnchor 32); PEG = oracle on
all words <= 5 at 19/28. Old backend: compact 14,955 rules / 1,256,793 rhs nodes (`b2-overlap-old-a19-b28.json`);
code backend (31 colours, W0 = 32): compact 8,336 / 431,165 / 617 / 2,502,032 bytes (`ab-overlap-code-a19-b28.json`);
the exported grammar re-parsed on all words <= 9 and correct on 112 adversarial raw words in the Rust runner.
