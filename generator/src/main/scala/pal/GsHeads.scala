package pal

import Action.Return
import Coroutine.Local

/** Local-head version of the GS overlap pass.
  *
  * The controller observes symbols and head order only. Coordinates live in the
  * VM below, never in the controller. Its only integer local is a phase bounded by
  * the fixed parameter k. Compilation closes over that finite control and emits a
  * table, so the controller is not a runtime primitive.
  *
  * The table types are in [[GsProgram]], the observer in [[HeadVM]]. The
  * controllers are [[Generator]] state machines (see [[Coroutine]] for the
  * encoding). Each `site` number is one suspension point (an emitted event or a
  * delegation to a sub-controller), numbered in order of appearance; the comment
  * above each `case` names what that site suspended on. A control key of
  * [[GsHeads.compileController]] is the pending event together with (frame name,
  * site, bound locals) for every frame of the suspended chain.
  *
  * Background: `GS_OVERLAP.md` (algorithm and what the head implementation
  * establishes) and `GS_LOCAL_CLOCK.md` (instruction accounting).
  */
object GsHeads {
  import Event.*

  val HEADS: Vector[String] = Vector("Origin", "OriginalEnd", "End", "Cut", "Tail", "A", "B", "P",
    "First", "Reach", "Walk", "KP", "KFirst", "Second")

  /** Heads that never read a symbol and may hold negative coordinates. */
  val BLIND: Set[String] = Set("KP", "KFirst", "Second")

  /** The branching instructions of the border controller. */
  val TESTS: Set[String] = Set("equal", "less", "symbols")

  /** A coroutine of head events. */
  type HeadGenerator[R] = Generator[Event, R]

  /** The table compiler lives in [[GsProgram]]; re-exported here. */
  export GsProgram.{compileController, minimize, unitMoves}

  private def requireK(k: Int): Unit = {
    if (k < 4) {
      throw new IllegalArgumentException("fixed integer k >= 4 required")
    }
  }

  /** Place the search heads at the current cut. */
  final class Initialize(k: Int) extends HeadGenerator[Unit]("_initialize") {
    override def locals: Vector[(String, Local)] = Vector("k" -> k)

    protected def step(response: Option[Boolean]): Action[Event, Unit] = {
      site match {
        // site 0: first activation
        case 0 => emitAt(1, copy("A", "Cut"))
        // site 1: copy("A", "Cut")
        case 1 => emitAt(2, copy("P", "Cut"))
        // site 2: copy("P", "Cut")
        case 2 => emitAt(3, move(Movement("P", 1)))
        // site 3: move(P+1)
        case 3 => emitAt(4, copy("B", "P"))
        // site 4: copy("B", "P")
        case 4 => emitAt(5, copy("KP", "Cut"))
        // site 5: copy("KP", "Cut")
        case 5 => emitAt(6, move(Movement("KP", k)))
        // site 6: move(KP+k)
        case _ => Return(())
      }
    }
  }

  /** Shift by `max(1, ceil(matched / k))` while rewinding
    * `A` (and `B` during matching) to the cut. `phase` counts rewound cells modulo k.
    */
  final class ResetShift(k: Int, search: Boolean) extends HeadGenerator[Unit]("_reset_shift") {
    private var phase: Int = 0
    private var nonempty: Option[Boolean] = None

    override def locals: Vector[(String, Local)] = {
      Vector[(String, Local)]("k" -> k, "search" -> search, "phase" -> phase) ++
        nonempty.map(value => "nonempty" -> (value: Local))
    }

    private def shiftEvent: Move = {
      if (search) {
        move(Movement("P", 1), Movement("B", 1), Movement("KP", -1))
      } else {
        move(Movement("P", 1), Movement("KP", k))
      }
    }

    private def loopHead(): Action[Event, Unit] = emitAt(2, equal("A", "Cut"))

    private def afterLoop(): Action[Event, Unit] = {
      if (!nonempty.get || phase != 0) {
        emitAt(if (search) 7 else 8, shiftEvent)
      } else {
        tail()
      }
    }

    private def tail(): Action[Event, Unit] = {
      if (!search) {
        emitAt(9, copy("B", "P"))
      } else {
        Return(())
      }
    }

    protected def step(response: Option[Boolean]): Action[Event, Unit] = {
      site match {
        // site 0: first activation
        case 0 =>
          phase = 0
          emitAt(1, equal("A", "Cut"))
        // site 1: equal("A", "Cut")
        case 1 =>
          nonempty = Some(!response.get)
          loopHead()
        // site 2: equal("A", "Cut")
        case 2 =>
          if (response.get) {
            afterLoop()
          } else if (search) {
            emitAt(3, move(Movement("A", -1), Movement("B", -1)))
          } else {
            emitAt(4, move(Movement("A", -1)))
          }
        // site 3: move(A-1, B-1)
        // site 4: move(A-1)
        case 3 | 4 =>
          phase += 1
          if (phase == k) {
            emitAt(if (search) 5 else 6, shiftEvent)
          } else {
            loopHead()
          }
        // site 5: move(P+1, B+1, KP-1)
        // site 6: move(P+1, KP+k)
        case 5 | 6 =>
          phase = 0
          loopHead()
        // site 7: move(P+1, B+1, KP-1)
        // site 8: move(P+1, KP+k)
        case 7 | 8 => tail()
        // site 9: copy("B", "P")
        case _ => Return(())
      }
    }
  }

  /** Shift by the first period `First - Cut` while
    * keeping the matched suffix.
    */
  final class PeriodShift(k: Int, search: Boolean) extends HeadGenerator[Unit]("_period_shift") {
    override def locals: Vector[(String, Local)] = Vector("k" -> k, "search" -> search)

    private def loopHead(): Action[Event, Unit] = emitAt(2, less("Walk", "First"))

    protected def step(response: Option[Boolean]): Action[Event, Unit] = {
      site match {
        // site 0: first activation
        case 0 => emitAt(1, copy("Walk", "Cut"))
        // site 1: copy("Walk", "Cut")
        case 1 => loopHead()
        // site 2: less("Walk", "First")
        case 2 =>
          if (response.get) {
            if (search) {
              emitAt(3, move(Movement("Walk", 1), Movement("A", -1), Movement("P", 1), Movement("KP", -1)))
            } else {
              emitAt(4, move(Movement("Walk", 1), Movement("A", -1), Movement("P", 1), Movement("KP", k)))
            }
          } else {
            Return(())
          }
        // site 3: move(Walk+1, A-1, P+1, KP-1)
        // site 4: move(Walk+1, A-1, P+1, KP+k)
        case _ => loopHead()
      }
    }
  }

  /** Find the shortest basic prefix of the pattern (from
    * `Cut`) repeated k times; `bounded` restricts the period below `Second`.
    * Returns whether a first period was found (`First = Cut + p`, `B = KP`).
    */
  final class First(k: Int, bounded: Boolean) extends HeadGenerator[Boolean]("_first") {
    override def locals: Vector[(String, Local)] = Vector("k" -> k, "bounded" -> bounded)

    private def outerHead(): Action[Event, Boolean] = emitAt(2, less("P", "End"))

    private def innerHead(): Action[Event, Boolean] = emitAt(4, less("B", "End"))

    private def afterInner(): Action[Event, Boolean] = emitAt(8, equal("B", "KP"))

    protected def step(response: Option[Boolean]): Action[Event, Boolean] = {
      site match {
        // site 0: first activation
        case 0 => callAt(1, new Initialize(k))
        // site 1: call Initialize(k)
        case 1 => outerHead()
        // site 2: less("P", "End")
        case 2 =>
          if (!response.get) {
            Return(false)
          } else if (bounded) {
            emitAt(3, less("P", "Second"))
          } else {
            innerHead()
          }
        // site 3: less("P", "Second")
        case 3 => if (response.get) innerHead() else Return(false)
        // site 4: less("B", "End")
        case 4 =>
          if (response.get) {
            emitAt(5, less("B", "KP"))
          } else {
            afterInner()
          }
        // site 5: less("B", "KP")
        case 5 =>
          if (response.get) {
            emitAt(6, symbols("A", "B"))
          } else {
            afterInner()
          }
        // site 6: symbols("A", "B")
        case 6 =>
          if (response.get) {
            emitAt(7, move(Movement("A", 1), Movement("B", 1)))
          } else {
            afterInner()
          }
        // site 7: move(A+1, B+1)
        case 7 => innerHead()
        // site 8: equal("B", "KP")
        case 8 =>
          if (response.get) {
            Return(true)
          } else {
            callAt(9, new ResetShift(k, false))
          }
        // site 9: call ResetShift(k, false)
        case _ => outerHead()
      }
    }
  }

  /** Search a second k-prefix-period beyond the reach of the first.
    * Returns whether one was found (`P = Cut + p2`).
    */
  final class Second(k: Int) extends HeadGenerator[Boolean]("_second") {
    override def locals: Vector[(String, Local)] = Vector("k" -> k)

    private def outerHead(): Action[Event, Boolean] = emitAt(2, less("P", "End"))

    private def innerHead(): Action[Event, Boolean] = emitAt(3, less("B", "End"))

    private def afterInner(): Action[Event, Boolean] = emitAt(8, less("A", "KFirst"))

    private def resetShift(): Action[Event, Boolean] = callAt(11, new ResetShift(k, false))

    protected def step(response: Option[Boolean]): Action[Event, Boolean] = {
      site match {
        // site 0: first activation
        case 0 => callAt(1, new Initialize(k))
        // site 1: call Initialize(k)
        case 1 => outerHead()
        // site 2: less("P", "End")
        case 2 => if (response.get) innerHead() else Return(false)
        // site 3: less("B", "End")
        case 3 =>
          if (response.get) {
            emitAt(4, symbols("A", "B"))
          } else {
            afterInner()
          }
        // site 4: symbols("A", "B")
        case 4 =>
          if (response.get) {
            emitAt(5, move(Movement("A", 1), Movement("B", 1)))
          } else {
            afterInner()
          }
        // site 5: move(A+1, B+1)
        case 5 => emitAt(6, less("Reach", "B"))
        // site 6: less("Reach", "B")
        case 6 =>
          if (response.get) {
            emitAt(7, less("B", "KP"))
          } else {
            innerHead()
          }
        // site 7: less("B", "KP")
        case 7 => if (response.get) innerHead() else Return(true)
        // site 8: less("A", "KFirst")
        case 8 =>
          if (response.get) {
            resetShift()
          } else {
            emitAt(9, less("Reach", "A"))
          }
        // site 9: less("Reach", "A")
        case 9 =>
          if (response.get) {
            resetShift()
          } else {
            callAt(10, new PeriodShift(k, false))
          }
        // site 10: call PeriodShift(k, false)
        // site 11: call ResetShift(k, false)
        case _ => outerHead()
      }
    }
  }

  /** The GS prefix decomposition with local heads. Leaves `Cut`
    * at the short prefix boundary and, when a period exists, `First`/`KFirst`/
    * `Reach` describing it. Returns whether a period exists.
    */
  final class Decompose(k: Int) extends HeadGenerator[Boolean]("_decompose") {
    override def locals: Vector[(String, Local)] = Vector("k" -> k)

    private var first: First = new First(k, false)
    private var second: Second = new Second(k)

    private def outer(): Action[Event, Boolean] = {
      first = new First(k, false)
      callAt(2, first)
    }

    private def extendHead(): Action[Event, Boolean] = emitAt(5, less("B", "End"))

    private def afterExtend(): Action[Event, Boolean] = emitAt(8, copy("Reach", "B"))

    private def boundedHead(): Action[Event, Boolean] = {
      first = new First(k, true)
      callAt(11, first)
    }

    private def cutHead(): Action[Event, Boolean] = emitAt(12, less("Cut", "P"))

    protected def step(response: Option[Boolean]): Action[Event, Boolean] = {
      site match {
        // site 0: first activation
        case 0 => emitAt(1, copy("Cut", "Origin"))
        // site 1: copy("Cut", "Origin")
        case 1 => outer()
        // site 2: call First(k, false)
        case 2 =>
          if (!first.result) {
            Return(false)
          } else {
            emitAt(3, copy("First", "P"))
          }
        // site 3: copy("First", "P")
        case 3 => emitAt(4, copy("KFirst", "B"))
        // site 4: copy("KFirst", "B")
        case 4 => extendHead()
        // site 5: less("B", "End")
        case 5 =>
          if (response.get) {
            emitAt(6, symbols("A", "B"))
          } else {
            afterExtend()
          }
        // site 6: symbols("A", "B")
        case 6 =>
          if (response.get) {
            emitAt(7, move(Movement("A", 1), Movement("B", 1)))
          } else {
            afterExtend()
          }
        // site 7: move(A+1, B+1)
        case 7 => extendHead()
        // site 8: copy("Reach", "B")
        case 8 =>
          second = new Second(k)
          callAt(9, second)
        // site 9: call Second(k)
        case 9 =>
          if (!second.result) {
            Return(true)
          } else {
            emitAt(10, copy("Second", "P"))
          }
        // site 10: copy("Second", "P")
        case 10 => boundedHead()
        // site 11: call First(k, true)
        case 11 => if (first.result) cutHead() else outer()
        // site 12: less("Cut", "P")
        case 12 =>
          if (response.get) {
            // Second remains at Cut + the saved second-period length. A copy of
            // Cut alone would lose that relative bound, so move both in lockstep.
            emitAt(13, move(Movement("Cut", 1), Movement("Second", 1)))
          } else {
            boundedHead()
          }
        // site 13: move(Cut+1, Second+1)
        case _ => cutHead()
      }
    }
  }

  /** Report a found border, either as a `border` coordinate or
    * as descending flag bits from `Cursor` down to the border length. Returns
    * whether the flag job is complete (`Cursor < Lower`).
    */
  final class Report(flags: Boolean) extends HeadGenerator[Boolean]("_report") {
    override def locals: Vector[(String, Local)] = Vector("flags" -> flags)

    private def fillHead(): Action[Event, Boolean] = emitAt(4, less("KP", "Cursor"))

    private def finalTest(): Action[Event, Boolean] = emitAt(9, less("Cursor", "Lower"))

    protected def step(response: Option[Boolean]): Action[Event, Boolean] = {
      site match {
        // site 0: first activation
        case 0 =>
          if (!flags) {
            emitAt(1, Border("KP"))
          } else {
            emitAt(2, less("KP", "Lower"))
          }
        // site 1: Border("KP")
        case 1 => Return(false)
        // site 2: less("KP", "Lower")
        case 2 =>
          if (response.get) {
            Return(true)
          } else {
            emitAt(3, less("Cursor", "KP"))
          }
        // site 3: less("Cursor", "KP")
        case 3 => if (response.get) finalTest() else fillHead()
        // site 4: less("KP", "Cursor")
        case 4 =>
          if (response.get) {
            emitAt(5, Flag(false))
          } else {
            emitAt(7, Flag(true))
          }
        // site 5: Flag(false)
        case 5 => emitAt(6, move(Movement("Cursor", -1)))
        // site 6: move(Cursor-1)
        case 6 => fillHead()
        // site 7: Flag(true)
        case 7 => emitAt(8, move(Movement("Cursor", -1)))
        // site 8: move(Cursor-1)
        case 8 => finalTest()
        // site 9: less("Cursor", "Lower")
        case _ => Return(response.get)
      }
    }
  }

  /** Emit the remaining flags down to `Lower`; only the
    * empty prefix (`Cursor == Origin`) is a palindrome.
    */
  final class FinishFlags extends HeadGenerator[Unit]("_finish_flags") {
    private def head(): Action[Event, Unit] = emitAt(1, less("Cursor", "Lower"))

    protected def step(response: Option[Boolean]): Action[Event, Unit] = {
      site match {
        // site 0: first activation
        case 0 => head()
        // site 1: less("Cursor", "Lower")
        case 1 =>
          if (response.get) {
            Return(())
          } else {
            emitAt(2, equal("Cursor", "Origin"))
          }
        // site 2: equal("Cursor", "Origin")
        case 2 =>
          if (response.get) {
            emitAt(3, Flag(true))
          } else {
            emitAt(4, Flag(false))
          }
        // site 3: Flag(true)
        // site 4: Flag(false)
        case 3 | 4 => emitAt(5, move(Movement("Cursor", -1)))
        // site 5: move(Cursor-1)
        case _ => head()
      }
    }
  }

  /** Enumerate all nonempty proper
    * borders of the loaded view in strictly decreasing order, stage by stage
    * (see `GS_OVERLAP.md`). With `flags`, report them as palindrome-prefix flag
    * bits between `Upper` and `Lower` instead of `border` coordinates.
    * `tailOrigin` is the text origin of the two-view variant ([[GsDualFlags]]).
    */
  final class BorderController(k: Int, flags: Boolean = false, tailOrigin: String = "Origin")
    extends HeadGenerator[Unit]("border_controller") {
    private var firstStage: Boolean = true
    private var periodExists: Option[Boolean] = None
    private var decompose: Decompose = new Decompose(k)
    private var report: Report = new Report(flags)

    override def locals: Vector[(String, Local)] = {
      Vector[(String, Local)]("k" -> k, "flags" -> flags, "tail_origin" -> tailOrigin,
        "first_stage" -> firstStage) ++
        periodExists.map(value => "period_exists" -> (value: Local))
    }

    private def stageHead(): Action[Event, Unit] = emitAt(3, less("Origin", "End"))

    private def finish(nextSite: Int): Action[Event, Unit] = callAt(nextSite, new FinishFlags)

    private def startDecomposition(): Action[Event, Unit] = {
      decompose = new Decompose(k)
      callAt(6, decompose)
    }

    private def offsetHead(): Action[Event, Unit] = emitAt(14, less("Walk", "Cut"))

    private def matchHead(): Action[Event, Unit] = emitAt(18, less("Second", "P"))

    private def scanHead(): Action[Event, Unit] = emitAt(19, less("B", "OriginalEnd"))

    private def endTest(): Action[Event, Unit] = emitAt(22, equal("B", "OriginalEnd"))

    private def prefixHead(): Action[Event, Unit] = emitAt(25, less("Walk", "Cut"))

    private def prefixDone(): Action[Event, Unit] = emitAt(28, equal("Walk", "Cut"))

    private def restoreB(): Action[Event, Unit] = emitAt(31, copy("B", "OriginalEnd"))

    private def shiftDecision(): Action[Event, Unit] = {
      if (periodExists.get) {
        emitAt(32, less("A", "KFirst"))
      } else {
        resetShift()
      }
    }

    private def resetShift(): Action[Event, Unit] = callAt(35, new ResetShift(k, true))

    private def shrinkStart(): Action[Event, Unit] = emitAt(36, copy("Second", "Origin"))

    private def shrinkWalk(): Action[Event, Unit] = emitAt(38, less("Walk", "Cut"))

    private def shrinkEnd(): Action[Event, Unit] = emitAt(42, less("Second", "End"))

    protected def step(response: Option[Boolean]): Action[Event, Unit] = {
      site match {
        // site 0: first activation
        case 0 =>
          // k is checked on the first activation, not at construction.
          requireK(k)
          firstStage = true
          emitAt(1, copy("End", "OriginalEnd"))
        // site 1: copy("End", "OriginalEnd")
        case 1 => emitAt(2, copy("Tail", tailOrigin))
        // site 2: copy("Tail", tailOrigin)
        case 2 => stageHead()
        // site 3: less("Origin", "End")
        case 3 =>
          if (!response.get) {
            if (flags) finish(44) else Return(())
          } else if (flags && tailOrigin != "Origin") {
            emitAt(4, less("End", "Lower"))
          } else {
            startDecomposition()
          }
        // site 4: less("End", "Lower")
        case 4 => if (response.get) finish(5) else startDecomposition()
        // site 5: call FinishFlags
        case 5 => Return(())
        // site 6: call Decompose(k)
        case 6 =>
          // During matching, KP is the current overlap length, and Second is
          // the last permitted text start (OriginalEnd - max(1, 2*Cut)).
          periodExists = Some(decompose.result)
          emitAt(7, copy("P", "Tail"))
        // site 7: copy("P", "Tail")
        case 7 => emitAt(8, copy("KP", "End"))
        // site 8: copy("KP", "End")
        case 8 =>
          if (firstStage) {
            emitAt(9, move(Movement("P", 1), Movement("KP", -1)))
          } else {
            emitAt(10, copy("A", "Cut"))
          }
        // site 9: move(P+1, KP-1)
        case 9 => emitAt(10, copy("A", "Cut"))
        // site 10: copy("A", "Cut")
        case 10 => emitAt(11, copy("B", "P"))
        // site 11: copy("B", "P")
        case 11 => emitAt(12, copy("Second", "OriginalEnd"))
        // site 12: copy("Second", "OriginalEnd")
        case 12 => emitAt(13, copy("Walk", "Origin"))
        // site 13: copy("Walk", "Origin")
        case 13 => offsetHead()
        // site 14: less("Walk", "Cut")
        case 14 =>
          if (response.get) {
            emitAt(15, move(Movement("Walk", 1), Movement("B", 1), Movement("Second", -2)))
          } else {
            emitAt(16, equal("Cut", "Origin"))
          }
        // site 15: move(Walk+1, B+1, Second-2)
        case 15 => offsetHead()
        // site 16: equal("Cut", "Origin")
        case 16 =>
          if (response.get) {
            emitAt(17, move(Movement("Second", -1)))
          } else {
            matchHead()
          }
        // site 17: move(Second-1)
        case 17 => matchHead()
        // site 18: less("Second", "P")
        case 18 => if (response.get) shrinkStart() else scanHead()
        // site 19: less("B", "OriginalEnd")
        case 19 =>
          if (response.get) {
            emitAt(20, symbols("A", "B"))
          } else {
            endTest()
          }
        // site 20: symbols("A", "B")
        case 20 =>
          if (response.get) {
            emitAt(21, move(Movement("A", 1), Movement("B", 1)))
          } else {
            endTest()
          }
        // site 21: move(A+1, B+1)
        case 21 => scanHead()
        // site 22: equal("B", "OriginalEnd")
        case 22 =>
          if (response.get) {
            // B can be reused for the prefix check, since its saved position
            // here is exactly OriginalEnd. A retains the matched suffix length.
            emitAt(23, copy("Walk", "Origin"))
          } else {
            shiftDecision()
          }
        // site 23: copy("Walk", "Origin")
        case 23 => emitAt(24, copy("B", "P"))
        // site 24: copy("B", "P")
        case 24 => prefixHead()
        // site 25: less("Walk", "Cut")
        case 25 =>
          if (response.get) {
            emitAt(26, symbols("Walk", "B"))
          } else {
            prefixDone()
          }
        // site 26: symbols("Walk", "B")
        case 26 =>
          if (response.get) {
            emitAt(27, move(Movement("Walk", 1), Movement("B", 1)))
          } else {
            prefixDone()
          }
        // site 27: move(Walk+1, B+1)
        case 27 => prefixHead()
        // site 28: equal("Walk", "Cut")
        case 28 =>
          if (response.get) {
            report = new Report(flags)
            callAt(29, report)
          } else {
            restoreB()
          }
        // site 29: call Report(flags)
        case 29 => if (report.result) finish(30) else restoreB()
        // site 30: call FinishFlags
        case 30 => Return(())
        // site 31: copy("B", "OriginalEnd")
        case 31 => shiftDecision()
        // site 32: less("A", "KFirst")
        case 32 =>
          if (response.get) {
            resetShift()
          } else {
            emitAt(33, less("Reach", "A"))
          }
        // site 33: less("Reach", "A")
        case 33 =>
          if (response.get) {
            resetShift()
          } else {
            callAt(34, new PeriodShift(k, true))
          }
        // site 34: call PeriodShift(k, true)
        // site 35: call ResetShift(k, true)
        case 34 | 35 => matchHead()
        // site 36: copy("Second", "Origin")
        case 36 =>
          // Retain only lengths below the completed interval. Tail = N - End.
          emitAt(37, copy("Walk", "Origin"))
        // site 37: copy("Walk", "Origin")
        case 37 => shrinkWalk()
        // site 38: less("Walk", "Cut")
        case 38 =>
          if (response.get) {
            emitAt(39, move(Movement("Walk", 1), Movement("Second", 2)))
          } else {
            emitAt(40, equal("Cut", "Origin"))
          }
        // site 39: move(Walk+1, Second+2)
        case 39 => shrinkWalk()
        // site 40: equal("Cut", "Origin")
        case 40 =>
          if (!response.get) {
            emitAt(41, move(Movement("Second", -1)))
          } else {
            shrinkEnd()
          }
        // site 41: move(Second-1)
        case 41 => shrinkEnd()
        // site 42: less("Second", "End")
        case 42 =>
          if (response.get) {
            emitAt(43, move(Movement("End", -1), Movement("Tail", 1)))
          } else {
            firstStage = false
            stageHead()
          }
        // site 43: move(End-1, Tail+1)
        case 43 => shrinkEnd()
        // site 44: call FinishFlags
        case _ => Return(())
      }
    }
  }

  /** The border controller; by default k = 8, border coordinates, text origin `Origin`. */
  def borderController(k: Int = 8, flags: Boolean = false, tailOrigin: String = "Origin"): BorderController = {
    new BorderController(k, flags, tailOrigin)
  }
}
