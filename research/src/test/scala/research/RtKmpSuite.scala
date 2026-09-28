package research

/** STRATEGY.md §3l: the de-amortised matcher (`RtKmp`) against the naive longest-prefix states, built first and pipelined. */
class RtKmpSuite extends munit.FunSuite {
  import RtKmp.*

  /** runs the bank on `t`; `perTick` builder steps then as many matcher letters as the frontier allows, per tick */
  private def run(p: String, t: String, pipelined: Boolean, perTick: Int = 3): Vector[Int] = {
    val b = new Bank(p, pipelined)
    if (!pipelined) { while (!b.built) { b.buildStep() } }
    val states = Vector.newBuilder[Int]
    var i = 0
    var guard = 0
    while (i < t.length) {
      for (_ <- 0 until perTick if !b.built) { b.buildStep() }
      var n = 0
      while (i < t.length && n < perTick && b.ready(t(i))) { b.matchStep(t(i)); states += b.state; i += 1; n += 1 }
      guard += 1
      assert(guard < 10 * (t.length + p.length) + 10, "no progress")
    }
    states.result()
  }

  private def words(rnd: scala.util.Random, n: Int, bias: Double): String = Iterator.fill(n)(if (rnd.nextDouble() < bias) { 'a' } else { 'b' }).mkString

  test("random patterns and texts, built first and pipelined, agree with the naive states") {
    val rnd = new scala.util.Random(7)
    for (trial <- 0 until 400) {
      val bias = Seq(0.5, 0.8, 0.95)(trial % 3)
      val p = words(rnd, 1 + rnd.nextInt(30), bias)
      val t = words(rnd, rnd.nextInt(120), bias)
      val want = naiveStates(p, t)
      assertEquals(run(p, t, pipelined = false), want, s"p=$p t=$t")
      assertEquals(run(p, t, pipelined = true, perTick = 1 + trial % 3), want, s"pipelined p=$p t=$t")
    }
  }

  /** the most cells one builder letter and one matcher letter store, pipelined three and three a tick */
  private def worst(p: String, t: String): (Int, Int) = {
    val b = new Bank(p, pipelined = true)
    var wb = 0; var wm = 0; var i = 0
    while (i < t.length) {
      for (_ <- 0 until 3 if !b.built) { wb = wb max b.buildStep().cells.values.sum }
      var n = 0
      while (i < t.length && n < 3 && b.ready(t(i))) { wm = wm max b.matchStep(t(i))._2.cells.values.sum; i += 1; n += 1 }
    }
    (wb, wm)
  }

  test("the cells of one letter stay bounded as the pattern grows (no amortisation)") {
    def fib(n: Int): String = { var a = "a"; var b = "ab"; while (b.length < n) { val c = b + a; a = b; b = c }; b.take(n) }
    val rnd = new scala.util.Random(3)
    def fams(n: Int): Seq[String] = Seq("a" * (n - 1) + "b", "ab" * (n / 2), fib(n), fib(n).reverse, words(rnd, n, 0.9))
    def over(n: Int): (Int, Int) = fams(n).map { p => worst(p, p.dropRight(1) + p + p.take(n / 2) + "b" + p) }.foldLeft((0, 0)) { case ((a, b), (c, d)) => (a max c, b max d) }
    val (b64, m64) = over(64)
    val (b2k, m2k) = over(2048)
    assert(b2k <= 80 && m2k <= 60, s"builder $b2k, matcher $m2k at 2048")
    assert(b2k <= b64 + 12 && m2k <= m64 + 12, s"64: $b64/$m64, 2048: $b2k/$m2k")
  }

  test("periodic and Fibonacci patterns against texts that keep almost matching") {
    def fib(n: Int): String = { var a = "a"; var b = "ab"; while (b.length < n) { val c = b + a; a = b; b = c }; b.take(n) }
    val pats = Seq("a" * 40 + "b", "ab" * 20, "aab" * 13, fib(60), fib(61).reverse, "a" * 17 + "b" + "a" * 17)
    for (p <- pats) {
      for (t <- Seq(p * 3, p.dropRight(1) * 4 + p, fib(200), "a" * 200, (p.take(p.length / 2) + "b") * 6)) {
        val want = naiveStates(p, t)
        assertEquals(run(p, t, pipelined = false), want, s"p=$p t=$t")
        assertEquals(run(p, t, pipelined = true), want, s"pipelined p=$p t=$t")
      }
    }
  }
}
