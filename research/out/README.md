# Generated grammars

- `overlap-code-a19-b28.peg.gz` — **the current best**: two KMP matcher banks on overlapping anchors
  (`OverlapPal`, A 19 / B 28 steps per letter, no flags worker) compiled by the site-code backend (`LpsCode`,
  31 colours, W0 = 32, the machine answers from n = 48, bounded fallback below) and compacted: 8,336 rules,
  431,165 right-hand-side nodes, 2.50 MB. `run lps-code export overlap 19 28 auto out/overlap-code-a19-b28.peg`.
  Checks: interpreter on 788 adversarial, 412 switch and 122 structured words up to 600 letters; re-parsed on
  all words <= 9; Scala matcher on `b a^k b` words at 47–49; Rust runner on 112 adversarial raw words (47–600).
  Metrics: `metrics/ab-overlap-code-a19-b28.json`.
- `pal3-code-m12-f40.peg.gz` — PAL3 12/40 with the warmup (W0 = 64) and the site-code backend: 15,967 rules
  (`metrics/a3-pal3-code-m12-f40.json`); Rust runner on the same 112 adversarial words.
- `pal2-m12-f40.peg.gz` — the `LpsPal2` grammar (33,019 rules) from before the compression plan; validated on
  structured and random words only, not on the adversarial family.
- `metrics/` — the JSON records of every measurement; `overlap-rate-search.md` and `task3-rate-search.md` the
  rate searches with their counterexamples. The `*a15-b24*` and `*m12-f36*` records are of grammars that were
  **withdrawn** (late first answers on `b a^(n-2) b`, a missed flags deadline); those grammars are no longer here.

Syntax of the Rust plain-PEG runner (`Name = expr;`, `"lit"`, `.`, `!`, `&`, `*`, `/`); start symbol `S`.
`zcat out/overlap-code-a19-b28.peg.gz > /tmp/pal.peg && ../runner/target/release/plain-peg-runner /tmp/pal.peg abba abab`.
