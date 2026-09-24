# A guided tour of the proof of `PAL ∈ PEG`

*日本語版: [PROOF.ja.md](PROOF.ja.md)*

This note explains how `PalPeg.PalInPeg.unconditional : RecognizedByTotalPEG PAL` is proved. It
goes top-down, from the final theorem to the machine, and names the file and the theorem for every
step. Files are under `PalPeg/`. All theorems named here use only the standard axioms.

## 1. The statement

- `PalPeg.PAL : Language (Fin 2) := { w | w.reverse = w }` (`Basic.lean`).
- `PegSeparation.RecognizedByTotalPEG L` is defined in the Kim–Park artifact. It says that some PEG
  `G` over `Fin 2` satisfies both of the following:
  - `IsLoffTotal`, so `G` is total;
  - `G.Recognizes w ↔ w ∈ L` for all `w`.

The final theorem is in `PalInPegFinal.lean`:

```lean
theorem unconditional : RecognizedByTotalPEG PAL :=
  pal_in_peg_of_fast startupFast_all flagsFast
```

The file also proves `evenPal_in_peg : RecognizedByTotalPEG EvenPal`, where
`EvenPal = { u uᴿ }`. It intersects `PAL` with the regular language of even-length words
(`EvenLength.lean`), which refutes Loff–Moreira–Reis Conjecture 7.

## 2. From an automaton to a PEG

We never write a PEG by hand. We build a *scaffolding automaton* (SCA), in the sense of
Loff–Moreira–Reis as formalized in the Kim–Park artifact, and let the artifact produce the PEG.

| Step | File | Theorem |
|---|---|---|
| SCA → total PEG, via Kim–Park's `SCAToPEG.loffBackward` (which yields a PEG for the reversed language) and `PAL_reverse_mem` | `PalInPegSca.lean` | `pal_recognizedByTotalPEG_of_sca` |
| A typed SCA (states and labels as structured types) → Kim–Park's `Fin`-indexed SCA | `ScaTyped.lean` | `pal_in_peg_of_typed` |
| A machine with a finite control, `K` persistent stacks and one bounded-radius *local* stack program per letter → a typed SCA | `ScaStackMachine.lean`, `ScaLocal.lean`, `ScaProg.lean` | the compiler `ScaStackMachine` |
| A letter-by-letter abstract machine, a representation relation `Rep`, and one stack program per letter simulating each step → `PAL ∈ PEG` | `ScaEncode.lean` | `ScaEncode.pal_in_peg` |

The rest of the proof therefore has to supply three things:

- an abstract machine that decides `PAL` letter by letter;
- a stack program per letter that simulates that machine;
- a representation invariant that the simulation keeps.

## 3. The abstract machine: the window-pal controller

The machine is the one produced by the Scala generator `GenerateWindowPal`
(`ScaffoldWindowPal.scala`). We transcribe it in `ScaWindowPal.lean`. The machine consists of three
parts:

- a controller with two **dyadic stages**;
- one **matcher** worker per stage;
- one **flag** worker per stage.

It reads one letter per tick.

**Why it decides `PAL`.** Take `|w| ≥ 4` and `W = stageOf |w| = 2^(⌊log₂|w|⌋−1)`, so that
`2W ≤ |w| < 4W`. Then `w` is a palindrome iff both of the following hold:

- (a) `(w.take W)ᴿ` is a suffix of `w`;
- (b) the middle `w[W, |w|−W)` is a palindrome.

`ScaWindowOutput.window_correct` proves that the controller accepts exactly `PAL`, given two
promises:

- the matcher of the answering stage outputs (a);
- that stage's `middle` bit is (b).

The controller handles `|w| < 4` itself.

**Schedule** (`ScaWindowSchedule.lean`).

- A stage is born at tick `2^j`, into slot `idx j`.
- At birth its matcher starts on the pattern `(w.take 2^j)ᴿ`, the reversed text read so far.
- The flag worker releases four jobs at ticks `2^j + (r+1)·2^(j−1)`, for `r < 4`.
- `answering_run` identifies the stage that answers at length `|w|`: it is the one born at
  `stageOf |w|`.

The remaining obligations are collected in `ScaWindowReal.pal_in_peg_of_real_promises`. They are
about the *real* workers:

- `hmatch`: the answering matcher's output is (a);
- `FlagsContract`: each flags job is captured before the next release, with the right bits;
  `ScaWindowPlumbing` derives the `middle` bit (b) and "no controller violation" from it;
- `hworkers`: no worker ever faults.

## 4. Encoding the controller into stacks

`ScaWindowEncode.lean`, `ScaWindowEncodeTick.lean` and `ScaWorkerEnc.lean` give:

- the stack program `letterProg` for one tick;
- the proof `sim_tick` that it simulates `tick`, as long as no worker faults;
- the stack encodings of the two real workers (`matcherEnc`, `flagsEnc`).

In these encodings, heads live in persistent stacks, and counters and flags live in unary and
boolean stacks (`ScaHeadRep.lean`, `ScaProgEmbed.lean`).

## 5. The workers and their head VMs

**The workers.**

- The workers are the Scala tables themselves (`ScaGsTables.lean`): the matcher table has 294 rows
  and the flags table 492.
- The tables are Scala's compilation of generator-style coroutines, which we transcribe in
  `ScaGsCoroutine.lean`.
- `ScaGsCertData.lean` checks a certificate with `decide +kernel`, showing that the tables agree
  with the transcribed coroutines. The result is `ScaGsCertFunctional.matcher_certified` and
  `flags_certified`.
- A worker runs a fixed number of table rows per tick, its *service quantum*: 2048 for the matcher
  and 32768 for the flags. The worker semantics is in `ScaWindowWorker.lean` and
  `ScaWindowInstance.lean`.

**The head VM.** We reason about a logical *head VM* (`ScaHeadVM.lean`). It keeps named head
positions over the word `pattern ++ text`, and its control is the coroutine.

- `ScaWorkerLink.service_link` (matcher) and `ScaFlagsLink.service_link` (flags) show that one
  service quantum of a real worker is the same number of head-VM steps.
- Both hold under side conditions (`StepSide`) that the VM must keep at every step:
  - reads are on the correct side of the pattern boundary;
  - `available` tests a forward head;
  - reversed heads stay inside the pattern;
  - `match` happens at the arrival frontier.
- The table facts that these links need (reader liveness) are proved from certificates in
  `ScaMatcherReaders.lean` and `ScaFlagsReaders.lean`.
- The register invariant is in `ScaWorkerRegs.lean`.

## 6. The matcher

### 6.1 List level

These files are reused from the earlier development.

- **Decomposition.**
  - `GSPreprocess.decompose x k` is the Galil–Seiferas decomposition `x = u v`, where `v` has a
    unique shortest `k`-repetition period `p₁` and a reach `r`.
  - `GSDecomposeL1.lean` proves the properties the verifier needs, for `k = 8`.
  - `ScaMatcherAnswer.lean` normalizes the first period for the no-period case (`normP1`) and
    proves the side conditions: `ShiftDeadline`, `KSimple`, L1.
- **The real-time verifier.**
  - `GSVerifier.vStep` is one step of the verifier. It scans `v` with Galil–Seiferas shifts and
    checks the short prefix `u` two comparisons per step.
  - `GSDrained.orbit` is its orbit from `(⟨|u|, 0⟩, 0)`.
  - `GSReportDeadline.orbit_deadlineInv`: whenever `v` is fully matched, the prefix check has either
    finished or failed on a real mismatch.
- **Answers.** `ScaMatcherAnswer.answer_iff_occursAt` says that a match event on the orbit ending
  at `n = |T|` exists iff `x` is a suffix of `T`.

### 6.2 Head level

- **Guarded steps** (`ScaHeadSafe.lean`).
  - `stepS` / `iterS` are head-VM steps guarded by the side conditions, so a guarded run carries
    them at every state (`iterS_safe`).
  - A guarded run also commutes with letter arrivals (`iterS_append`), so letters may arrive in the
    middle of a run.
- **Pieces of the main loop.**
  - `ScaShiftSafe.lean`: the shift generators.
  - `ScaMatcherLoop.lean`: the loop-head cases, i.e. hit, miss, and a hit that completes `v` (which
    reports).
  - `ScaMatcherRun.seg`: one *segment*. From a loop head encoding the orbit state `z` with the next
    letter arrived, the VM reaches the loop head of `vStep^[d] z` (`d ∈ {1, 2}`).
- **Step counts.** A segment is paid by the potential `Φ = (k+1)·pos + q`:
  - `n + 35·Φ(z) ≤ 35·Φ(z')`, for `k = 8`;
  - so a letter costs at most `35 · 9 = 315` steps of the main loop, below the quantum 2048.
- **Timing** (`ScaMatcherTick.lean`).
  - A report must happen during the tick of the last letter of its occurrence; otherwise the worker
    faults, because `match` requires `B` at the frontier.
  - `Cur` tracks the VM across steps and arrivals. It records a reference `(gr, pr)`: the slot where
    the VM last stopped waiting, and the potential there.
  - Since the VM does not idle after the reference, `g + 35·pr ≤ gr + 35·Φ + e`.
  - `refOK_wait` is the potential arithmetic: after a wait with `L` letters, a report ending at `n`
    is reached within `315(n − L) − 304` steps, which is less than `2048(n − L)`.
  - `cur_step` keeps the side conditions and `Cur` through one step.
  - `cur_live` shows that by the end of tick `L` every report ending by `L` is out.
- **Startup.**
  - `ScaHeadDecompose.lean` / `ScaDecomposeSafe.decompose_runS`: the head `Decompose` returns
    `decompose x 8` within `1698·|x| + 229` steps.
  - `ScaMatcherStart.lean`: the copies, and the offset walk to the first loop head.
  - `refOK_start` is the timing reference at the first loop head.
- **Lives.**
  - `ScaMatcherLife.lean` / `ScaMatcherLife2.lean` follow each matcher slot through the controller:
    it is born at `2^j` on the reversed prefix and fed the following letters.
  - `ScaMatcherLifeSafe.lifeSafe'`: every life keeps all its quanta safe, so no matcher faults.
  - `tick_output_last`: the answering matcher's output at `|w|` says whether a report ends at `|w|`.

## 7. The flag workers

- `ScaFlagsHead.flags_head_gs_palindromes`: the flags head program computes the palindrome bits of
  the prefixes of `y` with lengths `up − 1` down to `lo`, within `8098·|y| + 10` steps.
- `ScaFlagsLife.flagsContract`: the jobs, their releases and their captures give `FlagsContract`,
  and no flag worker faults, provided each job halts within `32768·S` steps.
- `ScaFlagsJob.lean` connects the two statements.

## 8. The two step-count facts

`ScaWindowFast.pal_in_peg_of_fast` assembles `hmatch`, `FlagsContract` and `hworkers` from §6–§7.
Two step-count facts are left, and `PalInPegFinal.lean` discharges both by arithmetic from the
proven linear bounds:

| Fact | Needed | Proved |
|---|---|---|
| `startupFast_all`: the matcher's startup reaches site 3 within | `2013·|x| + 32·s + 2063` steps | `1698·|x| + 230` (`start_pre`) |
| `flagsFast`: a flags job on `|y| = (r+1)·S ≤ 4S` halts within | `32768·S` steps | `8098·|y| + 10` (`flags_head_gs_palindromes`; the halting time is unique, `ScaFlagsLife.halt_unique`) |

The needed side depends on the service quanta.

- **What the proof uses.** The proof uses the quanta 2048 and 32768 (`GsBatchClock.VERIFIED_BATCH`
  in Scala, `GenerateWindowPal --verified`).
- **The Scala defaults.** The defaults (512 and 1024) are derived by a finer accounting. They are
  also sufficient in measurements: the worst case we found for the decomposition is about 104 steps
  per letter, and flags jobs use at most about 0.37 of their budget.
- **Proving the defaults.** Proving the defaults in Lean would need a sharper amortized bound on the
  decomposition's work.
- **Scope.** The Lean proof covers the verified quanta. For the defaults we have measurements only.

## 9. Notes

- **Faithfulness to Scala.** The workers are the Scala tables (certified). The controller follows
  `ScaffoldWindowPal.scala` field by field (see the docstrings of `ScaWindowPal.lean` and
  `ScaWindowWorker.lean`). The only deliberate difference from the default Scala output is the
  quanta above.
- **The printed grammar.** The Lean theorem gets its PEG from Kim–Park's `loffBackward`. The explicit
  grammar that the Scala generator prints goes through a different translator (`SymbolicSca2Peg`)
  and is not verified.
- **Relation to Galil's machine.** This construction does not formalize Galil's real-time palindrome
  recognizer. It combines Galil–Seiferas real-time string matching (for condition (a)) with
  palindrome flags computed in batches (for condition (b)), inside dyadic stages. An earlier route
  through a 118-tape real-time TM is not included in this repository. The conditional theorem from
  a real-time TM, `Existence.pal_in_peg_of_realTime`, is included.
- **Size.** The development is 78 modules, about 50,000 lines, plus Mathlib and the Kim–Park artifact.
