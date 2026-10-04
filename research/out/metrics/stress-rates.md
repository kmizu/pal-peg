# Stress palindromes and worst-case rates (STRATEGY.md §3q), measured 2026-09-29/30

All with the role options noacc fuse tmp compact endent endonce unfold lgap merge2 (+ noG noR unless stated), W0 128, beta 13/32.

## The old role's lag model (`old-lag 11 128 400 …`)
```
running (fork) research.Main old-lag 11 128 400 noacc tmp compact endent endonce unfold lgap merge2 noG noR beta=13/32
old-lag ky=11 W0=128 beta=13/32: 3283 words, 179194 answering ticks, old steps a letter: max 12, 1:158650 2:12993 3:2976 4:407 5:1637 6:186 7:368 8:113 9:113 10:198 11:80 12:1473; lagged answers that were wrong: 0
  old 1 steps a tick with slack: violations 30480 (exact slack: 27859), ticks behind 81259, most letters behind 96
  old 2 steps a tick with slack: violations 3677 (exact slack: 3617), ticks behind 52789, most letters behind 63
  old 3 steps a tick with slack: violations 15 (exact slack: 15), ticks behind 21573, most letters behind 39
  old 4 steps a tick with slack: violations 9 (exact slack: 9), ticks behind 11417, most letters behind 27
```

## role-outputs (outputs against the oracle only)
```
role-outputs 10/3 W0=128 beta=13/32: 3483 words, 217 words wrong (1368 ticks)
role-outputs 10/4 W0=128 beta=13/32: 3483 words, 90 words wrong (568 ticks)
role-outputs 11/2 W0=128 beta=13/32: 3000 words, 458 words wrong (622 ticks)
role-outputs 11/3 W0=128 beta=13/32: 3000 words, 129 words wrong (129 ticks)
role-outputs 11/3 W0=128 beta=13/32: 7929 words, 0 words wrong (0 ticks)
role-outputs 11/4 W0=128 beta=13/32: 3000 words, 58 words wrong (58 ticks)
role-outputs 12/4 W0=128 beta=13/32: 3000 words, 16 words wrong (16 ticks)
role-outputs 14/4 W0=128 beta=13/32: 3000 words, 1 words wrong (1 ticks)
role-outputs 16/3 W0=128 beta=13/32: 1500 words, 0 words wrong (0 ticks)
role-outputs 16/3 W0=128 beta=13/32: 3000 words, 0 words wrong (0 ticks)
role-outputs 16/3 W0=128 beta=13/32: 7929 words, 0 words wrong (0 ticks)
role-outputs 16/4 W0=128 beta=13/32: 3000 words, 1 words wrong (1 ticks)
role-outputs 17/3 W0=128 beta=13/32: 1500 words, 0 words wrong (0 ticks)
role-outputs 17/3 W0=128 beta=13/32: 3000 words, 0 words wrong (0 ticks)
role-outputs 17/3 W0=128 beta=13/32: 7929 words, 0 words wrong (0 ticks)
role-outputs 18/4 W0=128 beta=13/32: 3000 words, 0 words wrong (0 ticks)
role-outputs 19/3 W0=128 beta=13/32: 3000 words, 0 words wrong (0 ticks)
role-outputs 20/3 W0=128 beta=13/32: 1500 words, 0 words wrong (0 ticks)
role-outputs 20/3 W0=128 beta=13/32: 3000 words, 0 words wrong (0 ticks)
role-outputs 20/3 W0=128 beta=13/32: 7929 words, 0 words wrong (0 ticks)
role-outputs 20/4 W0=128 beta=13/32: 3000 words, 0 words wrong (0 ticks)
role-outputs 20/5 W0=128 beta=13/32: 3000 words, 0 words wrong (0 ticks)
role-outputs 9/3 W0=128 beta=13/32: 3483 words, 570 words wrong (5239 ticks)
role-outputs 9/4 W0=128 beta=13/32: 3483 words, 440 words wrong (3098 ticks)
```
Also measured (standard families up to 400, 3,483 words): 11/4 0 wrong, 11/3 0 wrong, 11/2 39 wrong.
The 16/3 and 17/3 lines are with merge3 (16/3 both with noG noR and with noG only, 17/3 with noG noR); 3,000 = lagwords 3000:1 (≤ 400),
1,500 = lagwords 1500:3 (≤ 1,000), 7,929 = standard ≤ 1,000 with every length to 1,025.
(standard = switch ≤ maxLen + 150 adversarial + every-length + 200 random; lagwords=<n>:<seed> = OldLag.lagWords from length 179.)

## Extra validations (lagwords 3000:<seed> up to 600, and 15/3)
```
[16/3 noG noR merge3 3000:5] role-outputs 16/3 W0=128 beta=13/32: 3000 words, 0 words wrong (0 ticks)
[16/3 noG noR merge3 3000:7] role-outputs 16/3 W0=128 beta=13/32: 3000 words, 0 words wrong (0 ticks)
[15/3 noG noR merge3 3000:1] role-outputs 15/3 W0=128 beta=13/32: 3000 words, 0 words wrong (0 ticks)
[15/3 noG noR merge3 3000:5] role-outputs 15/3 W0=128 beta=13/32: 3000 words, 0 words wrong (0 ticks)
[15/3 noG noR merge3 3000:7] mismatch len 461 at Some(460): aaaaaaaaaaaaaaaaaaaaaaaaaaaaabaaaaaaaaabaaaaaaaaaaaaaaaaaaaa
[15/3 noG noR merge3 3000:7] role-outputs 15/3 W0=128 beta=13/32: 3000 words, 1 words wrong (1 ticks)
```
15/3 compiled: exported r153m.peg: rules=4512 symbols=220890 colours=30; final 1287 rules, 541057 rhs nodes, 2421529 bytes (not correct).

## young-need (the young role's work per window tick, 40 young steps)
```
young-need W0=128 beta=13/32: 3283 words, work a window tick: p50 0.00, p99 10.79, max 11.88
young-need W0=128 beta=13/32: 3000 words, work a window tick: p50 9.51, p99 13.21, max 18.54
young-need W0=128 beta=13/32: 1500 words, work a window tick: p50 9.91, p99 14.79, max 19.07
[noG+noR] young-need W0=128 beta=13/32: 3000 words, work a window tick: p50 9.51, p99 13.21, max 18.54
[noG] young-need W0=128 beta=13/32: 3000 words, work a window tick: p50 9.20, p99 13.05, max 18.50
[none] young-need W0=128 beta=13/32: 3000 words, work a window tick: p50 9.14, p99 12.92, max 18.46
[noG+merge3] young-need W0=128 beta=13/32: 3000 words, work a window tick: p50 8.92, p99 11.87, max 15.56
[noG+merge3 L=1000] young-need W0=128 beta=13/32: 1500 words, work a window tick: p50 9.36, p99 13.14, max 15.37
[merge3 L=1000] young-need W0=128 beta=13/32: 1500 words, work a window tick: p50 9.28, p99 13.03, max 15.34
[merge3 L=400] young-need W0=128 beta=13/32: 3000 words, work a window tick: p50 8.85, p99 11.68, max 15.52
[noG+noR+merge3 L=400] young-need W0=128 beta=13/32: 3000 words, work a window tick: p50 9.24, p99 12.04, max 15.60
```

## Grammars (lps-code export … star mirror sound pts; peg-cse-improve 2.45 65536 60 1000 10)
```
exported role113.peg: rules=3335 symbols=134996 colours=22 W0=128 firstAnswer=180
budget 330740 (x2.45 of 134996); rounds (rules, nodes): (950,329911) (942,329636) (938,330677) (938,330455); final 938 rules, 330677 rhs nodes, 1420472 bytes (20 s)
budget 349855 (x2.5916 of 134996); rounds (rules, nodes): (933,348733) (923,348743) (920,349698) (920,348923); final 920 rules, 349698 rhs nodes, 1496437 bytes (19 s)
exported role204.peg: rules=5528 symbols=305987 colours=40 W0=128 firstAnswer=180
budget 749668 (x2.45 of 305987); rounds (rules, nodes): (1596,749333) (1587,749238) (1581,748559) (1572,748732) (1569,749208) (1569,749208); final 1569 rules, 749208 rhs nodes, 3561199 bytes (137 s)
exported r173m.peg: rules=4990 symbols=262414 colours=34 W0=128 firstAnswer=180
budget 642914 (x2.45 of 262414); rounds (rules, nodes): (1443,642030) (1428,642795) (1427,642835) (1427,642835); final 1427 rules, 642835 rhs nodes, 2939686 bytes (111 s)
exported r163m.peg: rules=4751 symbols=241155 colours=32 W0=128 firstAnswer=180
budget 590829 (x2.45 of 241155); rounds (rules, nodes): (1372,590467) (1358,589738) (1357,590263) (1357,590263); final 1357 rules, 590263 rhs nodes, 2664616 bytes (91 s)
exported r203.peg: rules=5342 symbols=294053 colours=40 W0=128 firstAnswer=180
budget 720429 (x2.45 of 294053); rounds (rules, nodes): (1538,719813) (1529,719867) (1529,719867); final 1529 rules, 719867 rhs nodes, 3448986 bytes (142 s)
exported r163g.peg: rules=5017 symbols=267357 colours=32 W0=128 firstAnswer=180
budget 655024 (x2.45 of 267357); rounds (rules, nodes): (1448,654515) (1430,653707) (1429,653894) (1428,654546) (1428,654546); final 1428 rules, 654546 rhs nodes, 2963112 bytes (128 s)
```

## Rust runner (`probes/runner_suffixes.py`: every 7th suffix length and the last 40 of each word)
```
g996 (anchors-role-y11-o4-w128-pts-n996): 58 words, 4472 inputs, 58 mismatches
anchors-role-y11-o4-w128-n994.peg: 58 words, 4472 inputs, 58 mismatches
anchors-role-y12-o4-w128-mirror-n996.peg: 58 words, 4472 inputs, 6 mismatches
anchors-role-y13-o4-w64-n999.peg: 58 words, 4472 inputs, 5 mismatches
anchors-role-y12-o4-w128-n998.peg: 58 words, 4472 inputs, 6 mismatches
overlap-code-a19-b28.peg.gz
pal2-m12-f40.peg.gz
pal3-code-m12-f40.peg.gz
pools-role-y15-o4-n999.peg.gz
pools-role-y15-o4-star-n999.peg.gz
pools-role-y15-o4.peg.gz
under1000-gap-a10-b13-inline-nodes-x1.0-r4096.peg.gz
under1000-gap-a10-b13-inline-nodes-x1.25-r4096.peg.gz
under1000-gap-a10-b13-inline-nodes-x2.0-r4096.peg.gz
under1000-gap-a10-b13-inline-nodes-x4.0-r16384.peg.gz
anchors-role-y16-o3-w128-m3-pts-n1357: 58 words, 4472 inputs, 0 mismatches; all 8190 words of length 1..12: 0 mismatches
```
