package pal

/** The border controller's compiled table: completeness, unit lowering, and
  * agreement of the head VM with the offline border enumeration.
  */
class GsHeadsSuite extends munit.FunSuite {
  import Event.*
  import GsHeads.{compileController, unitMoves}

  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")

  lazy val program: Program = compileController()
  lazy val unit: Program = unitMoves(program)

  test("complete finite table and unit lowering") {
    assert(program.code.size < 1200)
    unit.code.foreach { case Row(event, targets) =>
      targets.foreach(target => assert(0 <= target && target < unit.code.size))
      event match {
        case Move(moves) =>
          assertEquals(moves.size, 1)
          assert(moves(0).delta == -1 || moves(0).delta == 1)
        case _ => ()
      }
    }
  }

  test("all borders exhaustive and periodic") {
    val rng = new Mt19937(909)
    val samples = Words.below("ab", 10) ++
      (0 until 35).map(_ => rng.word("ab#", rng.randrange(1, 500))) ++
      Vector("a" * 1024, "ab" * 513, ("a" * 8 + "b") * 37, ("a" * 8 + "b" + "a" * 8 + "c") * 21)
    samples.foreach { word =>
      val expected = GsOverlap.iterBorders(word.toIndexedSeq, k = 8)
      val clue = (word.take(50), word.length)
      assertEquals(new HeadVM(word.toIndexedSeq, program).run(), expected, clue)
      assertEquals(new HeadVM(word.toIndexedSeq, unit).run(), expected, clue)
    }
  }

  test("k < 4 is rejected on the first activation, not at construction") {
    val controller = GsHeads.borderController(3)
    intercept[IllegalArgumentException](controller.send(None))
    intercept[IllegalArgumentException](compileController(3))
    assertEquals(GsHeads.borderController(4).send(None), Yielded(copy("End", "OriginalEnd")))
  }

  /** A one-line text rendering of an instruction, for diagnostics. */
  def render(event: Event): String = {
    event match {
      case Move(moves) => "move " + moves.map(m => s"${m.head}${if (m.delta >= 0) "+" else ""}${m.delta}").mkString(" ")
      case Copy(target, source) => s"copy $target $source"
      case Equal(left, right) => s"equal $left $right"
      case Less(left, right) => s"less $left $right"
      case Symbols(left, right) => s"symbols $left $right"
      case Border(head) => s"border $head"
      case Flag(value) => s"flag $value"
      case Available(head) => s"available $head"
      case AssertEqual(left, right) => s"assert_equal $left $right"
      case Match(head) => s"match $head"
      case Halt => "halt"
    }
  }
}
