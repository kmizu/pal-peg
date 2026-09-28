# two-way-machine and TwoWayPal (STRATEGY.md §3p), measured 2026-09-28

The step machine on the banks (`two-way-machine 128 1000 <ky> 1 beta=13/32 [kb=<n>]`):

```
two-way-machine W0=128 beta=13/32, young 40, old 1 steps a tick: 7554 words, 21267 banks (6899 double), wrong 0, late 0
  young steps used a tick of the window: mean 19.18, max 25.82
  steps by branch: idle 50.4%, pass-eq 16.4%, BNP 5.9%, BN 5.9%, match-replay 3.5%, ru 3.2%, pass-lt 2.9%, old-match 2.7%, match-fifo 2.4%, rr 2.0%, ub 2.0%, fifo 1.1%, sf 0.5%, sf-mis 0.2%, pass-gt 0.2%, xr 0.2%, pt 0.1%, PINIT 0.1%, pass-end1 0.1%, pass-end2 0.1%, mw 0.0%, ru-end 0.0%, sb 0.0%, sk 0.0%, sf-back 0.0%, ut 0.0%, mw-end 0.0%, sb-found 0.0%, rr-end 0.0%, ub-yes 0.0%, ut-bound 0.0%, xr-la 0.0%, ut-no 0.0%, sf-none 0.0%, pt-no 0.0%, pt-yes 0.0%, sb-skip 0.0%, sk-end 0.0%, sk-none 0.0%, xr-mu 0.0%, sb-mem 0.0%
two-way-machine W0=128 beta=13/32, young 27, old 1 steps a tick: 7554 words, 21267 banks (6899 double), wrong 0, late 0
  young steps used a tick of the window: mean 19.42, max 26.15
  steps by branch: idle 26.6%, pass-eq 23.9%, BNP 8.6%, BN 8.6%, match-replay 5.1%, ru 4.7%, pass-lt 4.3%, old-match 4.0%, match-fifo 3.5%, rr 2.9%, ub 2.9%, fifo 2.5%, sf 0.8%, sf-mis 0.3%, pass-gt 0.3%, xr 0.2%, pt 0.2%, PINIT 0.2%, pass-end1 0.1%, pass-end2 0.1%, mw 0.0%, ru-end 0.0%, sb 0.0%, sk 0.0%, sf-back 0.0%, ut 0.0%, mw-end 0.0%, sb-found 0.0%, rr-end 0.0%, ub-yes 0.0%, ut-bound 0.0%, xr-la 0.0%, ut-no 0.0%, sf-none 0.0%, pt-no 0.0%, pt-yes 0.0%, sb-skip 0.0%, sk-end 0.0%, sk-none 0.0%, xr-mu 0.0%, sb-mem 0.0%
```

Young steps a tick (ky) and the layers that may store a cell (kb), same words:

```
ky=26 kb=all wrong=0 late=2
ky=24 kb=all wrong=2218 late=6659
ky=22 kb=all wrong=3012 late=11025
ky=20 kb=all wrong=3501 late=12568
ky=27 kb=all wrong=0 late=0
ky=28 kb=all wrong=0 late=0
ky=36 kb=8 wrong=3503 late=12629
ky=40 kb=8 wrong=3503 late=12573
ky=44 kb=8 wrong=3503 late=12573
ky=50 kb=8 wrong=3502 late=12571
ky=60 kb=8 wrong=3030 late=9327
ky=36 kb=12 wrong=1985 late=5113
ky=44 kb=12 wrong=0 late=0
ky=32 kb=16 wrong=0 late=1
ky=36 kb=16 wrong=0 late=0
```

The Lpm machine against the oracle (`two-way-pal-check 27 1 64 200 100000 beta=13/32`): 1662 words, 0 mismatches, 262.7 s.

Compiled (`lps-code optimize twoway 27 1 <W0> out.json star mirror sound pts beta=13/32`):

| grammar | N | rhs nodes | max rhs | bytes | distinct DAG nodes | dispatch arms |
|---|---|---|---|---|---|---|
| twoway+code 27/1 W0=64 raw | 20237 | 874971 | 272 | 5050838 | 251743 | 123019 |
| twoway+code 27/1 W0=64 compact | 18553 | 871391 | 272 | 4847282 | 249766 | 122890 |
colours=30 W0=64 firstAnswer=90 code rules=3379 helper=60 fallback=48
| twoway+code 27/1 W0=128 raw | 20284 | 892352 | 491 | 5085904 | 252559 | 123201 |
| twoway+code 27/1 W0=128 compact | 18600 | 888682 | 489 | 4882168 | 250537 | 123072 |
colours=30 W0=128 firstAnswer=180 code rules=3379 helper=60 fallback=93

  "N_by_component": {"-": 203, "CT": 53, "IN": 210, "OU": 98, "OUT": 2, "YU": 17879, "fallback": 48, "helper": 60},
  "rhs_nodes_by_component": {"-": 1064, "CT": 577, "IN": 1300, "OU": 3036, "OUT": 28, "YU": 857891, "fallback": 7166, "helper": 329}

Inliner (`peg-cse-improve tw64.peg 2.45 65536 60 1000 10`):

```
budget 2134907 (x2.45 of 871391); rounds (rules, nodes): (5153,2134193) (5134,2134634) (5130,2134267) (5129,2134186) (5129,2133586); final 5129 rules, 2134186 rhs nodes, 10448238 bytes (1615 s)
```

Size probes (`… drop=<branch prefixes>`; the machine is then wrong):

```
drop=none | twoway+code 27/1 W0=64 compact | 18553 | 871391 | 272 | 4847282 | 249766 | 122890 |
drop=pass | twoway+code 27/1 W0=64 compact | 8014 | 152766 | 264 | 820078 | 54016 | 22635 |
drop=BN,BNP | twoway+code 27/1 W0=64 compact | 8603 | 122323 | 264 | 650342 | 44961 | 18589 |
drop=sf,sb,sk | twoway+code 27/1 W0=64 compact | 14881 | 646340 | 272 | 3587201 | 188499 | 91107 |
drop=ru,rr,ub,ut,xr,mw,pt | twoway+code 27/1 W0=64 compact | 11409 | 525484 | 272 | 2907107 | 154280 | 74587 |
drop=match,fifo | twoway+code 27/1 W0=64 compact | 17327 | 838191 | 272 | 4661111 | 239607 | 118130 |
```

§3f compiled the same way (`lps-code optimize role 11 4 128 … noacc fuse compact endent endonce star unfold tmp mirror lgap merge2 noG noR sound pts beta=13/32`):

```
| role+code 11/4 W0=128 compact | 3521 | 143051 | 489 | 679577 | 31829 | 15173 |
"N_by_component": {"-": 71, "CT": 54, "IN": 503, "OU": 558, "OUT": 2, "YU": 2196, "fallback": 93, "helper": 44}
"rhs_nodes_by_component": {"-": 381, "CT": 582, "IN": 17055, "OU": 22896, "OUT": 28, "YU": 77422, "fallback": 24446, "helper": 241}
```
