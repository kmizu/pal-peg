package research

/** The optimizer must preserve failure/success and the end position of every retained rule. */
class PegOptimizeSuite extends munit.FunSuite {
  import Expr.*

  def assertEquivalent(g: Grammar, result: PegOptimize.Result, maxLen: Int, alphabet: String): Unit = {
    for (w <- Exhaust.words(maxLen, alphabet)) {
      val before = g.matcher(w)
      val after = result.grammar.matcher(w)
      for ((oldName, newName) <- result.oldToNew; p <- 0 to w.length) {
        assertEquals(before.rule(oldName, p), after.rule(newName, p), s"extent differs: word=$w pos=$p rule=$oldName -> $newName")
      }
      assertEquals(g.accepts(w), result.grammar.accepts(w), s"acceptance differs on '$w'")
    }
  }

  test("identities: !!e = &e, &!e = !e, e / FAIL = e, FAIL / e = e, eps e = e; not !!e = e") {
    val g = Grammar.parse("""
      S = A B C D E;
      A = !!"a";
      B = &!"z";
      C = "" / !"";
      D = !"" / "b";
      E = "" "c";
    """)
    val r = PegOptimize.compact(g)
    assertEquivalent(g, r, 4, "abcz")
    val t = r.grammar.table
    assertEquals(t("A"), And(Lit("a")))
    assertEquals(t("B"), Not(Lit("z")))
    assertEquals(t("C"), Seq(Vector()))
    assertEquals(t("D"), Lit("b"))
    assertEquals(t("E"), Lit("c"))
  }

  test("aliases are resolved, the start keeps its name, cycles are left alone") {
    val g = Grammar.parse("""
      S = T;
      T = U "a";
      U = V;
      V = "b";
      P = Q;
      Q = P;
      W = P "c";
    """)
    val r = PegOptimize.compact(g)
    assertEquivalent(g, r, 4, "abc")
    assertEquals(r.grammar.start, "S")
    assertEquals(r.grammar.table("S"), Seq(Vector(Ref("V"), Lit("a"))))
    assert(!r.grammar.table.contains("U"))
    assert(!r.grammar.table.contains("W"), "unreachable rules are dropped")
    assertEquals(r.oldToNew("T"), "S")
    assertEquals(r.oldToNew("U"), "V")
    assert(!r.oldToNew.contains("W"))
  }

  test("identical right-hand sides are merged, transitively") {
    val g = Grammar.parse("""
      S = A B;
      A = X "a";
      B = Y "a";
      X = "x" / "y";
      Y = "x" / "y";
    """)
    val r = PegOptimize.compact(g)
    assertEquivalent(g, r, 4, "axy")
    assertEquals(r.grammar.rules.size, 3)
    assertEquals(r.oldToNew("B"), "A")
    assertEquals(r.oldToNew("Y"), "X")
  }

  test("the compiled KMP2 grammar is compacted equivalently (all rules, words <= 5)") {
    val g = Lps.compile(LpsKmp2.machine(2))
    val r = PegOptimize.compact(g)
    assert(r.grammar.rules.size < g.rules.size)
    assertEquivalent(g, r, 5, "ab#")
    val r2 = PegOptimize.compact(g, PegOptimize.Options(inlineSingleUse = true))
    assertEquivalent(g, r2, 5, "ab#")
  }
}
