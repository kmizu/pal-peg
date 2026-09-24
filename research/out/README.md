# Generated grammars

- `pal2-m12-f40.peg.gz`: the plain PEG for PAL produced by `run lps-pal2-size 12 40 print`
  (LpsPal2: universal local steps, 12 matcher steps and 40 flags steps per letter; 33,019 rules,
  3,976,769 symbols). Syntax of the Rust plain-PEG runner (`Name = expr;`, `"lit"`, `.`, `!`, `&`,
  `*`, `/`); start symbol `S`. Run with `zcat out/pal2-m12-f40.peg.gz > /tmp/pal2.peg &&
  ../runner/target/release/plain-peg-runner /tmp/pal2.peg abba abab`.
