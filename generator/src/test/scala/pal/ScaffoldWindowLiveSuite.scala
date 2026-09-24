package pal

import scala.collection.immutable.VectorMap

object ScaffoldWindowLiveSuite {
  val names: Vector[String] = Vector("A", "B", "OriginalEnd")
  val program: Program = Program(Vector(
    Row(Event.Less("A", "B"), Vector(1, 1)),
    Row(Event.move(Event.Movement("A", 2), Event.Movement("B", -1)), Vector(2)),
    Row(Event.Copy("B", "A"), Vector(3)),
    Row(Event.Equal("B", "A"), Vector(4, 4)),
    Row(Event.Available("A"), Vector(5, 5)),
    Row(Event.AssertEqual("B", "OriginalEnd"), Vector(6)),
    Row(Event.Equal("A", "A"), Vector(0, 0))), 0, 1)

  def fixture(availability: Boolean): Scaffold = {
    val c = new Circuit("abcdefg")
    val distances = new WindowLiveDistances(c, program, names, 16, extra = Vector("extra"), availabilityDistance = availability)
    val table = ScaffoldRom.controllerTable(program, names, batched = true, augment = Some(distances.augmentRows))
    val char = c.input()
    distances.loadOne(Expr.TRUE)
    distances.values.add("extra", 2)
    distances.initialize(char.eqTo('a'))
    val mapping = VectorMap.from(distances.analysis.before(program.start).toVector.sorted.map { pair =>
      pair -> (if (pair._2 == "OriginalEnd") { Some(("extra", -1)) } else { None })
    })
    distances.initializeValues(mapping, char.eqTo('g'))
    val fields = table.read(char.map(ch => "abcdefg".indexOf(ch)).bits)
    val (zero, less) = distances.compare(fields)
    c.put("zero", Value.select(zero, Value.constant(true), Value.constant(false)), Vector(false, true), true)
    c.put("less", Value.select(less, Value.constant(true), Value.constant(false)), Vector(false, true), false)
    distances.execute(fields, Expr.TRUE)
    distances.commit()
    c.machine(less)
  }
}

class ScaffoldWindowLiveSuite extends munit.FunSuite {
  import ScaffoldWindowLiveSuite.*
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(600, "s")
  test("initialization requires every live entry source") {
    val distances = new WindowLiveDistances(new Circuit(), program, names, 16)
    intercept[IllegalArgumentException] { distances.initializeValues(Map.empty, Expr.TRUE) }
  }
  test("absent comparison source is exactly zero in either orientation") {
    val distances = new WindowLiveDistances(new Circuit(), program, names, 16)
    for (reverse <- Vector(false, true)) {
      val fields = Map("distance.test" -> Value.constant(None), "distance.reverse" -> Value.constant(reverse))
      assertEquals(distances.compare(fields), (Expr.TRUE, Expr.FALSE))
    }
  }
}
