package pal

import scala.collection.immutable.VectorMap

class ScaffoldRomSuite extends munit.FunSuite {
  private def constantBits(value: Int, width: Int): Vector[Expr] = {
    Vector.tabulate(width)(bit => if ((value & (1 << bit)) != 0) Expr.TRUE else Expr.FALSE)
  }

  test("every GS instruction decodes exactly, including colored readers") {
    val program = GsHeads.unitMoves(GsHeads.compileController())
    val readers = GsHeadLiveness.analyzeReaders(program)
    val names = Vector.tabulate(readers.registers)(i => s"r$i")
    val table = ScaffoldRom.controllerTable(program, GsHeads.HEADS, Some(readers), names)
    table.rows.zipWithIndex.foreach { case (wanted, state) =>
      table.read(constantBits(state, table.width)).foreach { case (key, value) =>
        assert(value.bits.forall(bit => bit == Expr.TRUE || bit == Expr.FALSE))
        val index = value.bits.zipWithIndex.collect { case (Expr.TRUE, bit) => 1 << bit }.sum
        assertEquals(value.domain(index), wanted(key), s"state=$state key=$key")
      }
    }
  }

  test("batched reader deltas and augmentation decode, unused addresses pad with row zero") {
    val program = Program(Vector(
      Row(Event.Move(Vector(Event.Movement("A", 2), Event.Movement("B", -1))), Vector(1)),
      Row(Event.Symbols("A", "B"), Vector(2, 2)), Row(Event.Halt, Vector.empty)), 0, 4)
    val readers = GsHeadLiveness.analyzeReaders(program)
    val table = ScaffoldRom.controllerTable(program, Vector("A", "B"), Some(readers),
      Vector.tabulate(readers.registers)(i => s"r$i"), batched = true,
      augment = Some((rows, domains) => {
        rows.foreach(_("extra") = true)
        domains("extra") = Vector(false, true)
      }))
    assertEquals(table.rows.head("data_delta.r" + readers.colors("A")), 2)
    assertEquals(table.rows.head("data_delta.r" + readers.colors("B")), -1)
    val padded = table.read(constantBits(3, table.width))
    table.read(constantBits(0, table.width)).foreach { case (key, value) =>
      assertEquals(padded(key).bits, value.bits)
      assertEquals(padded(key).domain, value.domain)
    }
    assertEquals(table.read(constantBits(1, table.width))("extra").eqTo(true), Expr.TRUE)
  }

  test("invalid finite tables and address widths fail") {
    intercept[IllegalArgumentException](new ROM(Vector.empty, VectorMap.empty))
    intercept[IllegalArgumentException](new ROM(Vector(VectorMap("x" -> 0)), VectorMap("y" -> Vector(0))))
    intercept[IllegalArgumentException](new ROM(Vector(VectorMap("x" -> 0)), VectorMap("x" -> Vector(0, 0))))
    val table = new ROM(Vector(VectorMap("x" -> 0)), VectorMap("x" -> Vector(0)))
    intercept[IllegalArgumentException](table.read(Vector(Expr.TRUE)))
    assertEquals(table.read(Vector.empty)("x").domain, Vector[Any](0))
  }
}
