# Towards a small PEG for PAL: the current approach (2026-09-24)

This note is the status of the `research/` experiments for a reviewer (it was written for a
discussion with GPT-6, whose proposal is the starting point: the *window-KMP + shared-gap flags*
construction, DESIGN.md in the author's notes). It says what was built, what was measured, why the
grammar is still about 5× above the target, and which questions are open. The numbers in
[`NOTES.md`](NOTES.md) §4–§5 are the primary record; this note is the map.

**Status in one line.** A plain PEG for the binary palindromes with **33,019 rules** (3.98M
symbols), generated from a pointer machine of 70 slots per letter, correct on all words of length
≤ 10, on 600 random words up to 513 letters, and accepted by the plain PEG runner
(`out/pal2-m12-f40.peg.gz`). The generator of the proof produces 3.1M rules for the same
construction, so this is a 100× reduction; the target ("a few thousand rules") needs another 5×.

## 1. The pipeline

```
recognizer as a pointer machine        LpsPal2.scala (controller + 2 matchers + shared flags)
  = registers, bits, one schedule of slots per letter
    ↓ Lps.compile                       Lps.scala
plain PEG: one node per letter, one layer of nonterminals per slot
```

A **slot** is one guarded update of the machine state: it may read registers, bits and cell fields
through fixed-depth dereferences, allocate a fixed number of cells (its *sites*), and write
registers and bits. The state of the machine after slot `t` of the letter at PEG position `p` is
the set of nonterminals `r_t` evaluated at `p`. The PEG reads the word from its end (position `p`
sees the suffix), so the machine reads `w` reversed; PAL is closed under reversal, and the oracle in
the tests is applied to `w.reverse`.

Representation of a pointer in the PEG (the part that decides the size):

- **extent**: a pointer to a cell allocated at position `q ≥ p` is the rule's match length `q − p`.
  Pointers can only point to older cells (larger `q`), and walking toward the start of the word is
  free (`p1` of an input cell is the previous letter's separator, whose `p1` is the previous letter).
- **site bits**: the cell's allocation site is `(slot, index)`; a pointer of static kind `K`
  carries `⌈log₂ |sites(K)|⌉` predicate rules `x_y0, x_y1, …` naming the site among those that can
  allocate kind `K`. Dereferencing `Fld(x, f)` is an ordered choice over the possible sites:
  `&(site bits) x C_<slot>_<i>_p<f>`.
- **kinds**: every register and every record field has a declared set of tags it may hold
  (`schema`), so the choice above ranges only over the sites that allocate those tags. Tags are
  per module (matcher 0, matcher 1, flags), otherwise every pointer would dispatch over all
  modules' sites.
- registers alias to their last writer (`cur_7 = cur_3` is not emitted; uses of `cur` at slot 7
  refer to `cur_3`), and the layer-0 rules read the previous letter's last slot (`r_0 = . &. r_last`;
  at the node after the last letter there is no tick, so the initial state is used there).
- conditions (`K` rules) are named when shared, a chain of conditionals is one ordered choice, and
  the top-level dereference or conditional of a register write is emitted under the register's
  own name.

Each slot is written as a `Prog`: exclusive guarded branches over one shared allocation site per
kind of cell (`Prog.site`, fields selected by the guards). `Lpm.fuse` composes slots sequentially
(exact substitution); it was used to test that fewer layers do **not** make the grammar smaller
(§4 below).

The interpreter (`Lps.run`) executes the same machine directly; every grammar is checked against
it (`lps-*-peg`), and the interpreter against the palindrome oracle (`lps-*-sim`, `lps-*-rand`).

## 2. The recognizer (`LpsPal2.scala`)

The construction is the one of the proof (`../proof/PROOF.md` §3) with GPT-6's workers.

**Controller** (`LpsPal.ct1/ct2/ct3`, reused). Tick `n` reads `w[n−1]`. A stage is born at tick
`W = 2^j` (`j ≥ 1`) with half `S = W/2`; the dyadic clocks are pointers into the input history:
the next birth is found by walking a pointer from the current cell back to the start (`W` ticks),
the half-window boundaries by walking the previous birth cell (depth `S`) to the start, again and
again. Boundary `i` raises the stage's interval to `i` (1..6); intervals 1..4 release the flags
job `r = i − 1` on `y = w[W, W + (r+1)S)`; intervals 2..5 answer, consuming one flag per tick from
result `r = i − 2`; the stage dies at interval 6. Words with `|w| < 4` are decided directly.
Output at tick `n` (for `|w| ≥ 4`) = the answering stage's matcher bit ∧ its current flag.

**Stage matcher** (`Kmp2` over module `m0_`/`m1_`). At birth it resets and reads the pattern
`(w.take W)ᴿ` backwards from the birth cell (free walk); text letters arriving after birth go
through a two-stack FIFO. The KMP table is DESIGN §2: entries `(letter, fail, gap root, previous
entry)`, `d_i = i − π_i` as roots into one shared growing unary list, the table cursor as a stack
of passed entries (`front`) plus a rear stack of new entries (`rear`, reversed into `front` one cell
per step). Its bit `acc` after a tick says "the text read so far ends with the pattern".

**Shared flags worker** (`Kmp2` over module `f_`, with extra phases). One job: build the forward
view of `y` by walking back from the release cell (a stack, `|y|` steps; the length `(r+1)S` is
counted by walking the stage's half pointer `r + 1` times in lockstep, no numbers); build the table
of `y`; match the text `yᴿ` (again a free walk) with the full match kept as the final state; walk
the failure chain from the final state pushing gap roots onto a stack (the palindromic prefix
lengths in ascending order as differences); advance the resulting iterator `lo = rS` times (same
lockstep counting); store the iterator `(gap stack, countdown, at-a-palindrome bit)` as one cell
in `res_<stage>_<r>`. Requests are queued per stage; the older stage's job is taken first.

**Universal step.** Every worker is `n` copies per tick of one step `U` that performs exactly one
local step (a back-up unit, an archive-reversal step, a queue-reversal step, a letter load, or a
letter processed with its entry allocated, enqueued and compared in the same step; for the flags
worker also one forward-view push, one chain push, one iterator advance). Each kind of cell has one
site per `U`, so a pointer's site bits only name the copy.

## 3. Measured

| machine | slots/letter | rules | symbols | evidence |
|---|---|---|---|---|
| KMP matcher alone (`LpsKmp2`, U × 4) | 5 | 1,022 | 18,391 | all words ≤ 8 (interpreter), PEG = oracle ≤ 6 |
| PAL, matchers 8 / flags 40 | 62 | 30,059 | 3.77M | all words ≤ 10; fails on a 64-letter word (the `W = 32` matcher is late) |
| **PAL, matchers 12 / flags 40** | 70 | **33,019** | 3.98M | all words ≤ 10; 500 random words ≤ 121, 100 ≤ 513; PEG = interpreter; PEG = oracle ≤ 5; runner correct up to 161 letters |

Reproduce (`cd research`, `XDG_RUNTIME_DIR=/tmp/xdg-1000 sbt` under WSL2):

```sh
sbt "run lps-kmp2-size 4" "run lps-kmp2 6 4"            # the matcher alone: size, PEG vs oracle
sbt "run lps-pal2-sim 10 12 40"                          # interpreter vs oracle, all words <= 10
sbt "run lps-pal2-rand 100 256 12 40"                    # random words up to 513 letters
sbt "run lps-pal2-peg 6 1 1"                             # PEG vs interpreter (small rates, fast)
sbt "run lps-pal2-size 12 40 print" > pal2.log           # sizes per layer, then the grammar
zcat out/pal2-m12-f40.peg.gz > /tmp/pal2.peg && ../runner/target/release/plain-peg-runner /tmp/pal2.peg abba abab
```

The earlier route (one slot per micro-step, `LpsPal.scala`) gave 62,012 rules at 318 slots; fusing
the micro-steps into 34 slots gave 53,280; the compiler improvements above brought it to 33,850;
rewriting the workers as universal steps gave the table above.

## 4. Where the rules are

At 12 / 40 a matcher `U` costs about 215 rules and a flags `U` about 560, of which about 310 are
**site-bit rules**: 40 copies of the flags step mean 6 bits per pointer to a flags cell, and every
dereference is a 40-way choice (this is also most of the 4M symbols). The remaining 250 are the
step's own dereferences and conditions.

Two facts learned the hard way:

- **Fewer layers do not help.** The rules are the distinct expression nodes; fusing ten
  micro-slots into one keeps every node. Only fewer *operations* per letter, or fewer copies, or a
  cheaper site encoding, reduce the count.
- **The rates are the bound of the construction.** The flags job of size `4S` must finish within
  `S` ticks: DESIGN's `7m + lo + 2 ≈ 31S`, plus the S = 1 and S = 2 stages, whose jobs need more
  steps per tick than the bound (the `|w| = 7` failure at 32 flag steps is the `S = 1`, `r = 3`
  job of four letters in one tick). The matcher needs ~12 steps per letter because the `W = 32`
  table (with its back-ups and queue reversals) plus the matching of the 32 queued text letters
  must complete within 32 ticks.

Roughly: `rules ≈ Σ_copies (nodes of the step + pointers × log₂ copies)`.

## 5. Open questions

1. **Lower the flags rate.** The four jobs of a stage rebuild the table from scratch; the table of
   `y_r` is a prefix of the table of `y_{r+1}`, only the matching against `yᴿ` restarts. Is there a
   job structure whose worst case is well under 31 local steps per letter? Any reduction of the
   copy count `n` is worth `n × 560` rules and lowers the bit count of every pointer.
2. **Drop the smallest stages.** `W = 2, 4` could be replaced by a finite check of `|w| < 16` in the
   controller (seven stored letters against fixed-depth history reads), removing the small-`S`
   overhead that forces 40 rather than 32 flag steps.
3. **A cheaper encoding of "which copy allocated this cell".** The `log₂ n` predicate rules per
   pointer and the `n`-way dispatch per dereference are the dominant term. Is there a PEG encoding
   of a pointer into "the cell of copy `i` at position `q`" that costs `O(1)` rules per pointer?
   (Padding the word so that copies live at distinct positions is not available: the grammar must
   read `{a, b}*`.)
4. **Fewer dereferences per step.** The flags step reads about 60 pointer-valued expressions. Any
   representation with fewer fields per local step (e.g. no separate `pFail`/`pRoot` registers)
   cuts 6–7 rules per removed dereference per copy.
5. **The proof.** Nothing here is proved. The plan is the one of `../proof`: the machine is a
   deterministic transition system, so its PEG is total by construction, and correctness is the
   machine's; the Lean development would replace the head-VM workers of `ScaWindowWorker` by these
   local steps.

## 6. Files

| File | Contents |
|---|---|
| `Lpm.scala` | the pointer-machine model: expressions `E`, conditions `C`, cells, `Prog` (exclusive guarded branches, shared sites), `Subst`, `fuse`, the interpreter |
| `Lps.scala` | slot-scheduled machines and their compiler to PEG (site bits, aliasing, `checkSim`, `fused`) |
| `LpsKmp2.scala` | the window-KMP matcher as a universal step, standalone (`pattern # text`) |
| `LpsPal2.scala` | PAL: the controller of `LpsPal.scala`, matchers and the shared flags worker on universal steps |
| `LpsKmp.scala`, `LpsPal.scala` | the earlier one-slot-per-micro-step versions (kept for the measurements) |
| `LpmSlisenko.scala`, `SlisenkoRT.scala` | the Slisenko 1977 route, abandoned at ≈ 5,000 rules per layer × ≥ 300 layers |
| `NOTES.md` | the measurements and their interpretation, in order |
| `out/` | the generated grammar |
