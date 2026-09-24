package research

/** Semantics of the slot compiler that the exhaustive machine tests do not exercise. */
class LpsCompilerSuite extends munit.FunSuite {
  private def machine(step: Lpm.Step, regs: Vector[(String, Set[Lps.K])] = Vector.empty): Lps.Machine =
    Lps.Machine("ab", regs, Vector("out"), Set.empty, "out", Map.empty, Vector("T"), Map("T" -> step))

  test("a selected null does not fall through to a later true guard") {
    val e = Lpm.E.Ite(Lpm.C.True, Lpm.E.Null, Lpm.E.Ite(Lpm.C.True, Lpm.E.In, Lpm.E.Null))
    val step = Lpm.Step(Map.empty, Map("out" -> Lpm.C.IsNull(e)), None)
    assertEquals(Lps.checkSim(machine(step), 1), None)
  }

  test("a selected null does not fall through to the default") {
    val e = Lpm.E.Ite(Lpm.C.True, Lpm.E.Null, Lpm.E.In)
    val step = Lpm.Step(Map.empty, Map("out" -> Lpm.C.IsNull(e)), None)
    assertEquals(Lps.checkSim(machine(step), 1), None)
  }

  test("a selected pointer whose observation fails does not fall through: separator test") {
    // out = isSep(if true then In else Sep): In is a letter, so out must be false, never Sep's true
    val e = Lpm.E.Ite(Lpm.C.True, Lpm.E.In, Lpm.E.Sep)
    val step = Lpm.Step(Map.empty, Map("out" -> Lpm.C.TagIs(e, 2)), None)
    assertEquals(Lps.checkSim(machine(step), 2), None)
  }

  test("a register keeps its value across the tick after the last letter (no phantom tick)") {
    // r := In at every tick; out := IsNull(r) read before the update, so out is true only at the first tick
    val step = Lpm.Step(Map("r" -> Lpm.E.In), Map("out" -> Lpm.C.IsNull(Lpm.E.Reg("r"))), None)
    assertEquals(Lps.checkSim(machine(step, Vector("r" -> Set(Lps.KInp))), 3), None)
  }
}
