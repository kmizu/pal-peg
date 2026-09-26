package research

/** Naming common sub-expressions (`PegCse`, STRATEGY.md §3c) preserves the extent of every rule at every position. */
class PegCseSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")

  private def assertExtents(g: Grammar, h: Grammar, maxLen: Int, alphabet: String): Unit =
    for (w <- Exhaust.words(maxLen, alphabet)) {
      val before = g.matcher(w)
      val after = h.matcher(w)
      for ((n, _) <- g.rules if h.table.contains(n); p <- 0 to w.length) { assertEquals(before.rule(n, p), after.rule(n, p), s"extent differs: '$w' pos $p rule $n") }
    }

  private val g = Grammar.parse("""
    S = A !B (&. "c" / "d")* D;
    A = "a" A / "b" (&. "c" / "d");
    B = "b" E / &. "c";
    D = E "x" (&. "c" / "d") / "y";
    E = "e" / "f" D (&. "c" / "d");
  """)

  test("the statistics count every occurrence and the saving of the repeated alternation") {
    val st = PegCse.stats(g).filter(s => s.count >= 2 && s.size >= 2).sortBy(-_.saving)
    val top = st.head
    assertEquals(Expr.render(top.sample), Expr.render(Grammar.parse("""T = &. "c" / "d";""").rules.head._2))
    assertEquals(top.count, 4L)
    assertEquals(top.saving, (top.count - 1) * (top.size - 1) - 1)
  }

  test("extract: the named sub-expression replaces every occurrence, the extents of all rules are kept, the node total falls by the saving") {
    val st = PegCse.stats(g).filter(s => s.count >= 2 && s.size >= 2).maxBy(_.saving)
    val h = PegCse.extract(g, st.sample, "Z0")
    assert(h.table.contains("Z0"))
    assertEquals(h.rules.size, g.rules.size + 1)
    assertEquals(g.size._2.toLong - h.size._2.toLong, st.saving)
    assertExtents(g, h, 5, "abcdefxy")
  }

  test("improve on a site-code grammar: the rule count does not rise and the start rule answers as before on all words <= 9") {
    val m = RolePal.machine(1, 1, 8, RolePal.Options(youngReport = false, fuseIn = true, compact = true, mergeTmp = true))
    val opts = LpsCode.Options(fallbackBelow = Some(OverlapPal.firstAnswer(8)), minBirth = 8)
    val g0 = PegOptimize.compact(LpsCode.compileMeta(m, opts).grammar).grammar
    val budget = PegMetrics.of(g0, PegMetrics.componentOfName).rhsNodes * 3
    val plain = PegBudgetInline.inline(g0, PegBudgetInline.Options(nodeBudget = budget)).grammar
    val (h, trace) = PegCse.improve(g0, budget, Int.MaxValue, 10, 0L, 3)
    assert(h.rules.size <= plain.rules.size, s"${h.rules.size} > ${plain.rules.size}")
    assertEquals(trace.head._1, plain.rules.size)
    for (w <- Exhaust.words(9, "ab")) {
      val before = g0.matcher(w)
      val after = h.matcher(w)
      assertEquals(after.rule(h.start, 0), before.rule(g0.start, 0), s"'$w'")
    }
  }

  test("the price of a rule is the delta of the greedy's next elimination, and improveAdaptive does not raise the rule count") {
    val m = RolePal.machine(1, 1, 8, RolePal.Options(youngReport = false, fuseIn = true, compact = true, mergeTmp = true))
    val opts = LpsCode.Options(fallbackBelow = Some(OverlapPal.firstAnswer(8)), minBirth = 8, mirrorFallback = true)
    val g0 = PegOptimize.compact(LpsCode.compileMeta(m, opts).grammar).grammar
    val budget = PegMetrics.of(g0, PegMetrics.componentOfName).rhsNodes * 3
    val plain = PegBudgetInline.inline(g0, PegBudgetInline.Options(nodeBudget = budget)).grammar
    // one more elimination with an unlimited budget costs exactly the price
    val price = PegBudgetInline.cheapest(plain).get
    val one = PegBudgetInline.inline(plain, PegBudgetInline.Options(nodeBudget = Long.MaxValue, maxEliminations = 1))
    assertEquals(one.nodes - plain.size._2.toLong, price)
    val (h, _) = PegCse.improveAdaptive(g0, budget, Int.MaxValue, 10, 3)
    assert(h.rules.size <= plain.rules.size, s"${h.rules.size} > ${plain.rules.size}")
    for (w <- Exhaust.words(9, "ab")) { assertEquals(h.matcher(w).rule(h.start, 0), g0.matcher(w).rule(g0.start, 0), s"'$w'") }
  }
}

