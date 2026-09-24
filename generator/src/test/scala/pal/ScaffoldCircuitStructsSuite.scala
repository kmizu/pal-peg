package pal


/** Stack, Tape and Queue paths not covered by `ScaffoldCircuitSuite`. */
class ScaffoldCircuitStructsSuite extends munit.FunSuite {

  test("Tape.withSides gives the two sides different cell counts") {
    val c = new Circuit("ab")
    val tape = Tape.withSides(c, "t", "_ab", (1, 3))
    assertEquals(tape.left.pool.tags.length, 1)
    assertEquals(tape.right.pool.tags.length, 3)
    assertEquals(new Tape(c, "u", "_ab", slots = 2).right.pool.tags.length, 2)
  }
}
