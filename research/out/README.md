# Generated grammars

- `overlap-code-a15-b24.peg.gz` — **the current best**: two KMP matcher banks on overlapping anchors
  (`OverlapPal`, A 15 / B 24 steps per letter, no flags worker) compiled by the site-code backend (`LpsCode`,
  27 colours, W0 = 32, the machine answers from n = 48, bounded fallback below) and compacted: 6,960 rules,
  315,161 right-hand-side nodes, 1.82 MB. `run lps-code export overlap 15 24 auto out/overlap-code-a15-b24.peg`.
  Checks: re-parsed on all words <= 9, Scala matcher on 12 boundary words (47–49), Rust runner on 56 raw words
  (47–600). Metrics: `metrics/ab-overlap-code-a15-b24.json`.
- `overlap-old-a15-b24.peg.gz` — the same recognizer with the old site-bit backend: 12,048 rules
  (`metrics/b2-overlap-old-a15-b24.json`).

- `pal3-code-m12-f36.peg.gz` — PAL3 with the warmup (no stage below W0 = 64) compiled
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
