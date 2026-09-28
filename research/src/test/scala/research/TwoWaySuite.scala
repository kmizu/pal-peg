package research

/** STRATEGY.md §3m: the table-free real-time matcher (`TwoWay`) against the naive occurrences, on every binary pattern up to a
  * length and on longer structured ones; no backward check is ever late and the text never moves back. */
class TwoWaySuite extends munit.FunSuite {
  import TwoWay.*

  private def occurrences(p: String, t: String): Vector[Int] =
    (p.length to t.length).filter(i => t.substring(i - p.length, i) == p).map(_ - 1).toVector
  private def run(p: String, t: String): (Vector[Int], Matcher) = {
    val m = new Matcher(p, plan(p, new Cost))
    (t.indices.filter(tau => m.step(t, tau)).toVector, m)
  }
  private def localPeriod(x: String, l: Int): Int =
    (1 to x.length).find(r => (math.max(0, l - r) until l).forall(i => i + r >= x.length || i + r < l || x(i) == x(i + r))).get
  private def period(x: String): Int = (1 to x.length).find(p => (0 until x.length - p).forall(i => x(i) == x(i + p))).get

  test("every binary pattern up to length 11, random texts: the naive occurrences, never late") {
    val rnd = new scala.util.Random(11)
    var doubles = 0
    for (n <- 1 to 11; bits <- 0 until (1 << n)) {
      val p = (0 until n).map(i => if (((bits >> i) & 1) == 1) { 'b' } else { 'a' }).mkString
      val pl = plan(p, new Cost)
      if (pl.double) {
        doubles += 1
        val xp = p.substring(0, pl.xp)
        assert(pl.lp <= pl.xp - pl.lp && pl.xp >= pl.l, s"$p $pl")
        assertEquals(localPeriod(xp, pl.lp), period(xp), s"X' not critical: $p $pl")
        assertEquals(pl.mu, period(xp), s"$p $pl")
      } else { assert(pl.l <= p.length - pl.l, s"$p $pl") }
      for (_ <- 0 until 2) {
        val noise = Iterator.fill(rnd.nextInt(24))(if (rnd.nextBoolean()) { 'a' } else { 'b' }).mkString
        val t = noise + p + p.take(rnd.nextInt(n + 1)) + p + noise.take(5) + p
        val (got, m) = run(p, t)
        assertEquals(got, occurrences(p, t), s"p=$p t=$t $pl")
        assertEquals(m.late, 0, s"late: p=$p t=$t")
      }
    }
    assert(doubles > 500, s"$doubles two-instance patterns")
  }

  test("the preprocessing is linear: at most 16 steps a pattern letter, with at most one change of factorization") {
    def fib(n: Int): String = { var a = "a"; var b = "ab"; while (b.length < n) { val c = b + a; a = b; b = c }; b.take(n) }
    val rnd = new scala.util.Random(8)
    val pats = Seq("a" * 2999 + "b", fib(3000), fib(3000).reverse, "ab" * 1500) ++
      Seq(0.5, 0.7, 0.9, 0.97).flatMap(bias => Seq.fill(20)(Iterator.fill(2000 + rnd.nextInt(1000))(if (rnd.nextDouble() < bias) { 'a' } else { 'b' }).mkString))
    for (p <- pats) {
      val c = new Cost
      val pl = plan(p, c)
      assert(c.steps <= 16L * p.length, s"${c.steps} steps for length ${p.length}")
      assert(pl.switches <= 1, s"${pl.switches} changes")
    }
  }

  test("on the banks of the overlap recognizer: right answers, never late, 33 steps a young tick are enough (beta 13/32, W0 128)") {
    val sc = OverlapPal.Sched(13, 32)
    val words = OverlapProfile.switchWords(300, sc) ++ OverlapProfile.adversarialWords(300, sc).take(60) ++ OverlapProfile.everyLengthWords(300)
    val st = TwoWayProbe.run(words, 128, sc, 33)
    assertEquals((st.wrong, st.late, st.tooSlow), (0, 0, 0), st.report)
  }

  test("long structured patterns against texts that keep almost matching") {
    def fib(n: Int): String = { var a = "a"; var b = "ab"; while (b.length < n) { val c = b + a; a = b; b = c }; b.take(n) }
    val rnd = new scala.util.Random(5)
    val pats = Seq("a" * 199 + "b", "ab" * 100, "aab" * 67, fib(300), fib(301).reverse, "a" * 90 + "b" + "a" * 90,
      Iterator.fill(250)(if (rnd.nextDouble() < 0.9) { 'a' } else { 'b' }).mkString)
    for (p <- pats; t <- Seq(p * 3, p.dropRight(1) * 3 + p, fib(900), "a" * 600, (p.take(p.length / 2) + "b") * 5 + p)) {
      val (got, m) = run(p, t)
      assertEquals(got, occurrences(p, t), s"p=${p.take(40)}… t=${t.take(40)}…")
      assertEquals(m.late, 0)
    }
  }
}
