# Generated grammars

- `pal3-m12-f36.peg.gz` — **the current best**: the plain PEG for PAL from `LpsPal3` (flags truncated to
  the needed interval) at 12 matcher steps and 36 flags steps per letter, compacted by `PegOptimize`:
  25,510 rules, 2,361,254 right-hand-side nodes, 12.4 MB rendered. Produced by
  `run lps-export pal3 12 36 out/pal3-m12-f36.peg`; checked by `run lps-check-file` (re-parsed, all words
  <= 9 against the oracle) and by the Rust runner on raw inputs up to 600 letters. Metrics:
  `metrics/task5-pal3-m12-f36.json`.
- `pal2-m12-f40.peg.gz` — the earlier `LpsPal2` grammar (33,019 rules, before the compression plan).
- `metrics/` — the JSON records of every measurement (§3 of the plan), `task3-rate-search.md` the flags
  rate search with its counterexamples.

Syntax of the Rust plain-PEG runner (`Name = expr;`, `"lit"`, `.`, `!`, `&`, `*`, `/`); start symbol `S`.
`zcat out/pal3-m12-f36.peg.gz > /tmp/pal3.peg && ../runner/target/release/plain-peg-runner /tmp/pal3.peg abba abab`.
