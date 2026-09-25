package research

/** The endpoint-truncation transformer (plan §6): Z/P/D against the original rule at every position on all short
  * words — nullable choices, mutual recursion, predicates, repetition, positive-prefix failures. */
class PegTruncateSuite extends munit.FunSuite {
  private val grammars = Vector(
    "S = A B; A = \"a\" A / \"\"; B = \"b\" / \"\";",
    "S = (\"a\" / \"ab\") \"c\";",
    "S = &\"a\" \"a\" \"b\" / !\"a\" \"b\";",
    "S = \"a\"* \"b\";",
    "S = A; A = \"a\" B / \"b\"; B = \"c\" A / \"\";",
    "S = \"ab\" \"ba\" / \"a\";",
    "S = (\"a\" \"b\")* !\"a\";",
    "S = A !B; A = \"a\" / \"\"; B = \"b\" \"b\";",
    "S = \"a\" (\"b\" / \"\") \"c\" / \"a\";",
    "S = X* \"c\"; X = \"a\" / \"b\" \"b\";")

  test("Z, P and D meet their specifications at every position on all binary words <= 8 (and abc words <= 6)") {
    var evals = 0
    var comps = 0
    for (src <- grammars) {
      val g = Grammar.parse(src)
      val t = PegTruncate.transform(g)
      val alphabet = if (src.contains("\"c\"")) { "abc" } else { "ab" }
      val maxLen = if (alphabet == "abc") { 6 } else { 8 }
      for (w <- Exhaust.words(maxLen, alphabet); m = t.matcher(w); (n, _) <- g.rules; pos <- 0 to w.length) {
        val e = m.rule(n, pos)
        evals += 1
        val (z, p, d) = (m.rule(s"Z_$n", pos), m.rule(s"P_$n", pos), m.rule(s"D_$n", pos))
        e match {
          case Some(q) if q == pos => assertEquals(z, Some(pos), s"Z: $src '$w' $n@$pos"); assertEquals(p, None, s"P: $src '$w' $n@$pos"); assertEquals(d, None, s"D: $src '$w' $n@$pos")
          case Some(q) => assertEquals(z, None, s"Z: $src '$w' $n@$pos"); assertEquals(p, Some(q), s"P: $src '$w' $n@$pos"); assertEquals(d, Some(q - 1), s"D: $src '$w' $n@$pos")
          case None => assertEquals(z, None, s"Z: $src '$w' $n@$pos"); assertEquals(p, None, s"P: $src '$w' $n@$pos"); assertEquals(d, None, s"D: $src '$w' $n@$pos")
        }
        comps += 3
      }
      // the original rules are unchanged
      for ((n, e) <- g.rules) { assertEquals(t.table(n), e) }
    }
    println(s"PegTruncate: $evals original evaluations, $comps Z/P/D comparisons on ${grammars.size} grammars")
    assert(evals > 20000)
  }

  test("the compiled KMP2 grammar (n = 1) transforms and D of its start rule ends one before S on all words <= 5") {
    val g = PegOptimize.compact(Lps.compile(LpsKmp2.machine(1))).grammar
    val t = PegTruncate.transform(g)
    assert(t.rules.size >= 3 * g.rules.size)
    for (w <- Exhaust.words(5, "ab#")) {
      val m = t.matcher(w)
      (m.rule(g.start, 0), m.rule(s"D_${g.start}", 0)) match {
        case (Some(q), d) if q > 0 => assertEquals(d, Some(q - 1), s"'$w'")
        case (Some(_), d) => assertEquals(d, None, s"'$w'")
        case (None, d) => assertEquals(d, None, s"'$w'")
      }
    }
  }
}
