package research

/** The budgeted inliner must preserve failure/success and the end position of every retained rule at every
  * position (plan §5: language equivalence of S alone is too weak for pointer rules). */
class PegBudgetInlineSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")

  private def assertExtents(g: Grammar, r: PegBudgetInline.Result, maxLen: Int, alphabet: String): Unit = {
    val kept = r.grammar.rules.map(_._1)
    for (w <- Exhaust.words(maxLen, alphabet)) {
      val before = g.matcher(w)
      val after = r.grammar.matcher(w)
      for (n <- kept; p <- 0 to w.length) { assertEquals(before.rule(n, p), after.rule(n, p), s"extent differs: '$w' pos $p rule $n") }
    }
  }
  private def measured(g: Grammar): (Long, Int) = { val s = g.rules.map { case (_, e) => Expr.size(e) }; (s.map(_.toLong).sum, s.max) }

  test("hand grammar with predicates, repetition and mutual recursion: extents kept; S and self-referencing rules stay") {
    val g = Grammar.parse("""
      S = A !B C* D;
      A = "a" A / "b";
      B = "b" E;
      C = "c" / "d";
      D = E "x" / "y";
      E = "e" / "f" D;
    """)
    val r = PegBudgetInline.inline(g, PegBudgetInline.Options(nodeBudget = 10000))
    assertEquals(r.grammar.start, "S")
    assert(r.grammar.table.contains("A"), "the self-referencing rule A must stay")
    assert(r.eliminated.nonEmpty)
    assert(r.grammar.table.contains("D") || r.grammar.table.contains("E"), "one of the mutually recursive rules must stay")
    assertExtents(g, r, 4, "abcdefxy")
    assertEquals(measured(r.grammar)._1, r.nodes, "the node total is tracked exactly")
  }

  test("a rule that becomes self-referencing after a substitution is not eliminated afterwards") {
    val g = Grammar.parse("""
      S = A "z";
      A = "p" B "x" / "y";
      B = "q" A "w" / "v";
    """)
    val r = PegBudgetInline.inline(g, PegBudgetInline.Options(nodeBudget = 10000))
    assertEquals(r.grammar.rules.size, 2)
    assertExtents(g, r, 5, "pqvwxyz")
  }

  test("the compiled KMP2 grammar (n = 2): nodes budget 1.5x — every retained rule keeps its extents on all words <= 5 over ab#") {
    val g = PegOptimize.compact(Lps.compile(LpsKmp2.machine(2))).grammar
    val (nodes, maxRhs) = measured(g)
    val r = PegBudgetInline.inline(g, PegBudgetInline.Options(nodeBudget = nodes * 3 / 2, maxRhs = maxRhs * 4))
    assert(r.grammar.rules.size < g.rules.size, s"${r.grammar.rules.size} of ${g.rules.size}")
    val (nodes2, max2) = measured(r.grammar)
    assert(nodes2 <= nodes * 3 / 2, s"nodes $nodes2 > budget ${nodes * 3 / 2}")
    assert(max2 <= maxRhs * 4, s"max rhs $max2")
    assertEquals(nodes2, r.nodes)
    assertExtents(g, r, 5, "ab#")
    val g2 = Grammar.parse(r.grammar.render)
    for (w <- Exhaust.words(6, "ab#")) { assertEquals(g2.accepts(w), g.accepts(w), s"'$w' re-parsed") }
  }

  test("bytes priority: the byte estimate stays within the budget and the measured bytes are close to it") {
    val g = PegOptimize.compact(Lps.compile(LpsKmp2.machine(1))).grammar
    val bytes = g.render.getBytes("UTF-8").length.toLong
    val r = PegBudgetInline.inline(g, PegBudgetInline.Options(nodeBudget = Long.MaxValue, byteBudget = bytes, priority = PegBudgetInline.Bytes))
    assert(r.grammar.rules.size < g.rules.size)
    assert(r.bytesEstimate <= bytes)
    val real = r.grammar.render.getBytes("UTF-8").length.toLong
    assert(real <= r.bytesEstimate, s"the estimate (${r.bytesEstimate}) must not undercount the rendered bytes ($real)")
    assertExtents(g, r, 4, "ab#")
  }
}
