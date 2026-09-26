package research

/** Task A1: the EOF-anchored site code (Tail_i / Code_i / Mask_k helpers), the site colouring, and the
  * pointer algebra probe of the plan's appendix A ported to MUnit. Plain PEG only. */
class SiteCodeSuite extends munit.FunSuite {
  import Expr.*
  private val Eps: Expr = Seq(Vector())
  private val Fail: Expr = Not(Eps)
  private def ite(c: Expr, a: Expr, b: Expr): Expr = Choice(Vector(Seq(Vector(And(c), a)), Seq(Vector(Not(c), b))))
  private def seq(es: Expr*): Expr = Seq(es.toVector)
  private def alt(es: Expr*): Expr = Choice(es.toVector)

  test("Tail_i succeeds iff the remaining length is i; Code_i ends at n-i iff n-p >= i; Mask_k reads bit k") {
    val g = new Grammar(("S", Eps) +: SiteCode.helpers(4))
    var checks = 0
    for (w <- Exhaust.words(9, "ab"); p <- 0 to w.length) {
      val n = w.length
      for (i <- 0 until 4) {
        assertEquals(g.extent(w, s"Tail_$i", p), if (n - p == i) { Some(n) } else { None }, s"Tail_$i '$w' $p")
        assertEquals(g.extent(w, s"Code_$i", p), if (n - p >= i) { Some(n - i) } else { None }, s"Code_$i '$w' $p")
        checks += 2
      }
      for (k <- 0 until 2) {
        assertEquals(g.extent(w, s"Mask_$k", p).isDefined, n - p < 4 && (((n - p) >> k) & 1) == 1, s"Mask_$k '$w' $p")
        checks += 1
      }
    }
    assert(checks > 10000)
  }

  test("appendix A: (address, code) pointers through nulls, same-position sites, six dereferences and a cross-tick copy") {
    val N = 4
    val Depth = 6
    val rules = Vector.newBuilder[(String, Expr)]
    rules += (("S", Eps))
    rules ++= SiteCode.helpers(N)
    val safe = And(seq(Vector.fill(N)(AnyChar)*))
    val atA = And(Lit("a"))
    def code(i: Int) = Ref(s"Code_$i")
    rules += (("F0_addr", ite(atA, Fail, And(AnyChar))))
    rules += (("F0_code", ite(atA, Fail, code(1))))
    rules += (("F1_addr", ite(atA, seq(AnyChar, safe), seq(AnyChar, AnyChar, safe))))
    rules += (("F1_code", ite(atA, seq(AnyChar, safe, code(2)), seq(AnyChar, AnyChar, safe, code(0)))))
    rules += (("F2_addr", And(AnyChar)))
    rules += (("F2_code", code(3)))
    rules += (("F3_addr", Fail))
    rules += (("F3_code", Fail))
    rules += (("P0_addr", safe))
    rules += (("P0_code", seq(safe, ite(atA, code(0), code(1)))))
    for (d <- 1 to Depth; comp <- Vector("addr", "code")) {
      val src = s"P${d - 1}"
      val arms = (0 until N).map(i => seq(And(seq(Ref(src + "_code"), Ref(s"Tail_$i"))), Ref(src + "_addr"), Ref(s"F${i}_$comp")))
      rules += ((s"P${d}_$comp", alt(arms*)))
    }
    rules += (("Copy_addr", seq(AnyChar, Ref("P0_addr"))))
    rules += (("Copy_code", seq(AnyChar, Ref("P0_code"))))
    val g = new Grammar(rules.result())
    def expectedField(w: String, x: Option[(Int, Int)]): Option[(Int, Int)] = x.flatMap { case (q, i) =>
      i match {
        case 0 => if (w(q) == 'a') { None } else { Some((q, 1)) }
        case 1 =>
          val r = if (w(q) == 'a') { (q + 1, 2) } else { (q + 2, 0) }
          if (w.length - r._1 >= N) { Some(r) } else { None }
        case 2 => Some((q, 3))
        case _ => None
      }
    }
    var checks = 0
    for (w <- Exhaust.words(9, "ab"); p <- 0 to w.length if w.length - p >= N) {
      val n = w.length
      val mt = g.matcher(w)
      var x: Option[(Int, Int)] = Some((p, if (w(p) == 'a') { 0 } else { 1 }))
      for (d <- 0 to Depth) {
        assertEquals(mt.rule(s"P${d}_addr", p), x.map(_._1), s"'$w' p=$p depth=$d addr")
        assertEquals(mt.rule(s"P${d}_code", p), x.map(n - _._2), s"'$w' p=$p depth=$d code")
        for (i <- 0 until N) {
          val isSite = mt.eval(And(seq(Ref(s"P${d}_code"), Ref(s"Tail_$i"))), p).isDefined
          assertEquals(isSite, x.exists(_._2 == i), s"'$w' p=$p depth=$d site $i")
        }
        for (k <- 0 until 2) {
          val bit = mt.eval(And(seq(Ref(s"P${d}_code"), Ref(s"Mask_$k"))), p).isDefined
          assertEquals(bit, x.exists(y => ((y._2 >> k) & 1) == 1), s"'$w' p=$p depth=$d bit $k")
        }
        checks += 2 + N + 2
        x = expectedField(w, x)
      }
      if (n - p >= N + 1) {
        val site = if (w(p + 1) == 'a') { 0 } else { 1 }
        assertEquals(mt.rule("Copy_addr", p), Some(p + 1))
        assertEquals(mt.rule("Copy_code", p), Some(n - site))
        checks += 2
      }
    }
    assert(checks > 100000, s"$checks")
  }

  test("colouring is injective on every domain, deterministic, and handles empty and singleton domains") {
    val s = (1 to 8).map(i => (i, 0)).toVector
    val domains = Vector(Set(s(0), s(1), s(2)), Set(s(2), s(3)), Set(s(3), s(4), s(5)), Set.empty[SiteCode.Site], Set(s(6)), Set(s(0), s(5)))
    val c = SiteCode.color(domains)
    for (d <- domains) { assert(c.injectiveOn(d), s"$d") }
    assert(c.count <= 3, s"count ${c.count}")
    assertEquals(SiteCode.color(domains), c)
    assertEquals(SiteCode.color(Vector()).count, 0)
    intercept[IllegalArgumentException] { SiteCode.Coloring(Map(s(0) -> 0, s(1) -> 0), 1).requireInjective(Vector(Set(s(0), s(1)))) }
  }

  test("the bounded palindrome fallback accepts exactly the palindromes shorter than 2 W0; helpers survive render/parse") {
    val g = new Grammar(("S", Ref("Short")) +: (SiteCode.fallback(4) ++ SiteCode.helpers(3)))
    val g2 = Grammar.parse(g.render)
    for (w <- Exhaust.words(10, "ab")) {
      val want = w.length < 8 && w == w.reverse
      assertEquals(g.accepts(w), want, s"'$w'")
      assertEquals(g2.accepts(w), want, s"'$w' re-parsed")
      for (i <- 0 until 3; p <- 0 to w.length) { assertEquals(g2.extent(w, s"Code_$i", p), g.extent(w, s"Code_$i", p)) }
    }
    assertEquals(g.extent("abab", "Long", 0), None)
    assertEquals(g.extent("abababab", "Long", 0), Some(8))
    assertEquals(SiteCode.nextPow2(36), 64)
    assertEquals(SiteCode.nextPow2(2), 2)
  }

  test("codeByStar: Code_i by a progressing star has the same extent as the recursive Code_i at every position (all words <= 9, count 6)") {
    val rec = new Grammar(("S", Eps) +: SiteCode.helpers(6))
    val star = new Grammar(("S", Eps) +: SiteCode.helpers(6, codeByStar = true))
    for (w <- Exhaust.words(9, "ab"); i <- 0 until 6; p <- 0 to w.length) {
      assertEquals(star.extent(w, SiteCode.code(i), p), rec.extent(w, SiteCode.code(i), p), s"'$w' Code_$i at $p")
    }
    for ((n, e) <- star.rules if n.startsWith("Code_")) {
      assert(!Expr.render(e).contains(n + " ") && !Expr.render(e).endsWith(n), s"$n must not refer to itself")
    }
  }

  test("the mirrored fallback (STRATEGY.md §3d): Short accepts exactly the palindromes shorter than the limit, as the Pal_k family does (all words <= limit + 2)") {
    import Expr.Ref
    for (limit <- Vector(2, 3, 8, 13, 16)) {
      val gm = new Grammar(Vector("S" -> Ref("Short")) ++ SiteCode.fallbackMirror(limit))
      val gp = new Grammar(Vector("S" -> Ref("Short")) ++ SiteCode.fallbackBelow(limit))
      for (w <- Exhaust.words(limit + 2, "ab")) {
        val got = gm.matcher(w).rule("S", 0)
        assertEquals(got.contains(w.length), w.length < limit && w == w.reverse, s"limit $limit '$w'")
        assertEquals(got.isDefined, gp.matcher(w).rule("S", 0).isDefined, s"limit $limit '$w'")
        assertEquals(gm.matcher(w).rule("Long", 0).isDefined, gp.matcher(w).rule("Long", 0).isDefined, s"limit $limit '$w'")
      }
    }
  }
}

