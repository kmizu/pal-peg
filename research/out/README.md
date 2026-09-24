# Generated grammars

- `pal3-code-m12-f36.peg.gz` — **the current best**: PAL3 with the warmup (no stage below W0 = 64) compiled
  by the site-code backend `LpsCode` (a pointer's site as an EOF-anchored second extent, 36 colours, direct
  dispatch) and compacted by `PegOptimize`: 14,819 rules, 739,084 right-hand-side nodes, 4.08 MB. Lengths
  below 128 are decided by the bounded fallback `Short`, the machine answers from n = 128. Produced by
  `run lps-code export 12 36 auto out/pal3-code-m12-f36.peg`; checked by `run lps-check-file` (re-parsed, all
  words <= 9), `run lps-code check 12 36 64 <words>` (Scala matcher, boundary words 127/128) and the Rust
  runner on 40 raw words of 127..600 letters. Metrics: `metrics/a3-pal3-code-m12-f36.json` (`-trie.json`:
  the trie dispatch, larger).

- `pal3-m12-f36.peg.gz` — the previous best (old site-bit backend): the plain PEG for PAL from `LpsPal3` (flags truncated to
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
