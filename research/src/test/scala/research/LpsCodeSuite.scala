package research

/** Task A3: the site-code backend agrees with the interpreter, with the old compiler's semantics tests, and
  * with the palindrome oracle through the warmup fallback; register rules end where the cells are. */
class LpsCodeSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")
  private def machine(step: Lpm.Step, regs: Vector[(String, Set[Lps.K])] = Vector.empty): Lps.Machine =
    Lps.Machine("ab", regs, Vector("out"), Set.empty, "out", Map.empty, Vector("T"), Map("T" -> step))

  test("the compiler-semantics cases of LpsCompilerSuite hold for the code backend") {
    val e1 = Lpm.E.Ite(Lpm.C.True, Lpm.E.Null, Lpm.E.Ite(Lpm.C.True, Lpm.E.In, Lpm.E.Null))
    assertEquals(LpsCode.checkSim(machine(Lpm.Step(Map.empty, Map("out" -> Lpm.C.IsNull(e1)), None)), 1), None)
    val e2 = Lpm.E.Ite(Lpm.C.True, Lpm.E.Null, Lpm.E.In)
    assertEquals(LpsCode.checkSim(machine(Lpm.Step(Map.empty, Map("out" -> Lpm.C.IsNull(e2)), None)), 1), None)
    val e3 = Lpm.E.Ite(Lpm.C.True, Lpm.E.In, Lpm.E.Sep)
    assertEquals(LpsCode.checkSim(machine(Lpm.Step(Map.empty, Map("out" -> Lpm.C.TagIs(e3, 2)), None)), 2), None)
    val step4 = Lpm.Step(Map("r" -> Lpm.E.In), Map("out" -> Lpm.C.IsNull(Lpm.E.Reg("r"))), None)
    assertEquals(LpsCode.checkSim(machine(step4, Vector("r" -> Set(Lps.KInp))), 3), None)
  }

  test("KMP2 with the code backend: PEG = interpreter on all words <= 6 (n = 1, 2); register extents and codes match the cells") {
    for (n <- Vector(1, 2)) {
      val m = LpsKmp2.machine(n)
      assertEquals(LpsCode.checkSim(m, 6), None, s"n=$n")
      for (w <- Exhaust.words(5, "ab#") if w.nonEmpty) { assertEquals(LpsCode.checkRules(m, w), None, s"n=$n '$w'") }
    }
  }

  test("PAL3 warmup W0 = 4 at 1/1 through the code backend with the fallback: the oracle below 2 W0, the machine from n = 8 (PEG = interpreter)") {
    val m = LpsWarmup.machine(1, 1, 4)
    val opts = LpsCode.Options(fallbackW0 = Some(4))
    val res = LpsCode.compileMeta(m, opts)
    assert(res.coloring.count <= 4, s"colours ${res.coloring.count}")
    assertEquals(LpsCode.checkSim(m, 10, opts), None)
    for (w <- Exhaust.words(6, "ab") if w.nonEmpty) { assertEquals(LpsCode.checkRules(m, w, opts), None, s"'$w'") }
    // the machine really answers from n = 8: at 1/1 it is too slow to be right, so its outputs differ from the oracle somewhere
    assert(Exhaust.words(10, "ab").exists(w => w.length >= 8 && Lps.run(m, w).outputs.last != LpsPal.isPal(w)), "the 1/1 machine should be visibly reached")
    val g2 = Grammar.parse(res.grammar.render)
    for (w <- Exhaust.words(9, "ab")) { assertEquals(g2.accepts(w), res.grammar.accepts(w), s"'$w' re-parsed") }
  }

  test("the trie dispatch gives the same language") {
    val m = LpsWarmup.machine(1, 1, 4)
    assertEquals(LpsCode.checkSim(m, 10, LpsCode.Options(fallbackW0 = Some(4), dispatch = LpsCode.Trie)), None)
    assertEquals(LpsCode.checkSim(LpsKmp2.machine(2), 6, LpsCode.Options(dispatch = LpsCode.Trie)), None)
  }
}
