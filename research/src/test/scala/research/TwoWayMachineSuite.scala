package research

/** STRATEGY.md §3p: the step machine of the table-free matcher against the reference plan (`TwoWay.plan`) and the palindrome
  * oracle on the banks of the overlap recognizer. */
class TwoWayMachineSuite extends munit.FunSuite {
  import TwoWayMachine.*

  /** the pattern index of a cell of `planOf(p)` (the word is p reversed): n for null */
  private def ix(n: Int, c: TwoWayPtr.Cell): Int = if (c == null) { n } else { n - 1 - c.pos }

  private def checkPlan(p: String): Unit = {
    val n = p.length
    val s = planOf(p)
    val pl = TwoWay.plan(p, new TwoWay.Cost)
    assertEquals(s.bit("dbl"), pl.double, s"double p=$p $pl")
    if (!pl.double) {
      assertEquals(ix(n, s.reg("ul")), pl.l, s"l p=$p $pl")
      assertEquals(s.bit("umem"), pl.memory, s"memory p=$p $pl")
      if (pl.memory) { assertEquals(ix(n, s.reg("umf")), n - pl.shift, s"memory cell p=$p $pl") }
    } else {
      val (la, lp, xp, mu) = (pl.l, pl.lp, pl.xp, pl.mu)
      assertEquals(ix(n, s.reg("wl")), la, s"l_A p=$p $pl")
      assertEquals(ix(n, s.reg("ul")), lp, s"l' p=$p $pl")
      assertEquals(ix(n, s.reg("umf")), xp - mu, s"x'-mu p=$p $pl")
      assertEquals(ix(n, s.reg("ume")), n - mu, s"n-mu p=$p $pl")
      assertEquals(ix(n, s.reg("ule")), lp + n - xp, s"end countdown p=$p $pl")
      assertEquals(ix(n, s.reg("D2")), la - 1 + n - xp, s"coupling p=$p $pl")
      assertEquals(ix(n, s.reg("cd")), n - xp, s"first countdown p=$p $pl")
    }
  }

  test("the plan of every binary pattern up to length 12 is the reference plan") {
    for (n <- 2 to 12; bits <- 0 until (1 << n)) {
      checkPlan((0 until n).map(i => if (((bits >> i) & 1) == 1) { 'b' } else { 'a' }).mkString)
    }
  }

  test("the plan of random and structured longer patterns is the reference plan") {
    def fib(n: Int): String = { var a = "a"; var b = "ab"; while (b.length < n) { val c = b + a; a = b; b = c }; b.take(n) }
    val rnd = new scala.util.Random(21)
    val pats = Seq(fib(200), fib(301).reverse, "ab" * 60 + "b", "a" * 80 + "b" + "a" * 40) ++
      Seq(0.5, 0.8, 0.95).flatMap(bias => Seq.fill(300)(Iterator.fill(13 + rnd.nextInt(120))(if (rnd.nextDouble() < bias) { 'a' } else { 'b' }).mkString))
    pats.foreach(checkPlan)
  }

  test("on the banks of the overlap recognizer: right answers (young 64 steps a tick, old 1)") {
    val sc = OverlapPal.Sched(13, 32)
    val words = OverlapProfile.switchWords(200, sc) ++ OverlapProfile.adversarialWords(200, sc).take(40) ++ OverlapProfile.everyLengthWords(200)
    val st = new Stats
    var banks = 0
    for (w <- words) {
      val as = OverlapPal.anchors(32, w.length + 1, sc)
      for (i <- as.indices if i + 1 < as.length && as(i + 1) <= w.length) {
        val n1 = if (i + 2 < as.length) { as(i + 2) } else { w.length + 1 }
        val r = runBank(w, as(i), as(i + 1), n1, 64, 1, st)
        banks += 1
        assertEquals((r.wrong, r.late), (0, false), s"w=$w h=${as(i)} n0=${as(i + 1)}")
      }
    }
    assert(banks > 500, s"$banks banks")
  }
}
