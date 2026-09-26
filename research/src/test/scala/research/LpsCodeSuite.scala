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
    val opts = LpsCode.Options(fallbackBelow = Some(8), minBirth = 4)
    val res = LpsCode.compileMeta(m, opts)
    assert(res.coloring.count <= 4, s"colours ${res.coloring.count}")
    assertEquals(LpsCode.checkSim(m, 10, opts), None)
    for (w <- Exhaust.words(6, "ab") if w.nonEmpty) { assertEquals(LpsCode.checkRules(m, w, opts), None, s"'$w'") }
    // the machine really answers from n = 8: at 1/1 it is too slow to be right, so its outputs differ from the oracle somewhere
    assert(Exhaust.words(10, "ab").exists(w => w.length >= 8 && Lps.run(m, w).outputs.last != LpsPal.isPal(w)), "the 1/1 machine should be visibly reached")
    val g2 = Grammar.parse(res.grammar.render)
    for (w <- Exhaust.words(9, "ab")) { assertEquals(g2.accepts(w), res.grammar.accepts(w), s"'$w' re-parsed") }
  }

  test("OverlapPal with minAnchor 4 at 1/1 through the code backend: the oracle below 6, the machine (PEG = interpreter) from n = 6") {
    val m = OverlapPal.machine(1, 1, 4)
    val opts = LpsCode.Options(fallbackBelow = Some(6), minBirth = 4)
    val res = LpsCode.compileMeta(m, opts)
    assert(res.coloring.count <= 4, s"colours ${res.coloring.count}")
    assertEquals(LpsCode.checkSim(m, 10, opts), None)
    for (w <- Exhaust.words(6, "ab") if w.nonEmpty) { assertEquals(LpsCode.checkRules(m, w, opts), None, s"'$w'") }
    for (w <- Exhaust.words(7, "ab") if w.nonEmpty) { assertEquals(LpsWarmup.checkReachable(m, w, 4), None, s"'$w'") }
  }

  test("the trie dispatch gives the same language") {
    val m = LpsWarmup.machine(1, 1, 4)
    assertEquals(LpsCode.checkSim(m, 10, LpsCode.Options(fallbackBelow = Some(8), minBirth = 4, dispatch = LpsCode.Trie)), None)
    assertEquals(LpsCode.checkSim(LpsKmp2.machine(2), 6, LpsCode.Options(dispatch = LpsCode.Trie)), None)
  }

  test("every rule the code backend references is defined, for every size option of the role machine (a strict PEG tool rejects an undefined rule)") {
    val a = RolePal.Options(youngReport = false, fuseIn = true, compact = true, endAsEntry = true, endOnce = true)
    val variants = Vector("A" -> a, "tmp" -> a.copy(mergeTmp = true), "rk" -> a.copy(sharedRK = true), "rk p3" -> a.copy(sharedRK = true, rearInP3 = true),
      "unfold" -> a.copy(unfold = true), "unfold tmp" -> a.copy(unfold = true, mergeTmp = true), "tmp beta 7/16" -> a.copy(mergeTmp = true, sched = OverlapPal.Sched(7, 16)))
    for ((name, o) <- variants; dispatch <- Vector(LpsCode.Direct, LpsCode.Trie)) {
      val w0 = if (o.sched.half) { 16 } else { 32 }
      val g = LpsCode.compileMeta(RolePal.machine(2, 2, w0, o), LpsCode.Options(fallbackBelow = Some(OverlapPal.firstAnswer(w0, o.sched)), minBirth = w0, dispatch = dispatch)).grammar
      val defined = g.rules.map(_._1).toSet
      val undefined = g.rules.flatMap { case (_, e) => PegBudgetInline.occurrences(e).keys }.toSet -- defined
      assert(undefined.isEmpty, s"$name $dispatch: undefined ${undefined.toVector.sorted.take(5)}")
    }
  }
}

