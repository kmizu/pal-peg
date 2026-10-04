package research

/** STRATEGY.md §3q: the palindromes that stress the roles. The §3f machine (young 11, old 4) answers one of them wrong; the
  * machine chosen against the worst case (young 16, old 3, merge3) answers all 58 counterexamples of `out/stress-counterexamples.txt`
  * (the stress palindromes, lengths 179–400, that the §3f machine gets wrong) right, at every tick. */
class StressRatesSuite extends munit.FunSuite {
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")
  private val sc = OverlapPal.Sched(13, 32)
  private val base = RolePal.Options(youngReport = false, mergeTmp = true, fuseIn = true, compact = true, endAsEntry = true, endOnce = true,
    sched = sc, unfold = true, lgap = true, merge2 = true, m2off = Set("G", "R"))
  private def words: Vector[String] = {
    val src = scala.io.Source.fromFile("out/stress-counterexamples.txt")
    try { src.getLines().filter(_.nonEmpty).toVector } finally { src.close() }
  }

  test("the stress palindromes are reproducible (OldLag.lagWords, seed 1)") {
    val ws = OldLag.lagWords(3000, 179, 400, 1L)
    assertEquals(ws.size, 3000)
    assert(words.forall(ws.contains), "every counterexample is one of the 3,000 stress palindromes of seed 1")
  }

  test("the §3f machine (11/4) answers the palindrome b a^74 b a^53 b a^6 b a^53 b a^74 b wrong") {
    val w = "b" + "a" * 74 + "b" + "a" * 53 + "b" + "a" * 6 + "b" + "a" * 53 + "b" + "a" * 74 + "b"
    assert(LpsPal.isPal(w))
    val out = Lps.run(RolePal.machine(11, 4, 128, base), w).outputs
    assertEquals(out.last, false)
    assertEquals(OverlapPal.expectedOutputs(128, w, sc).last, true)
  }

  test("the machine chosen against the worst case (16/3 + merge3) answers all 58 counterexamples right") {
    val m = RolePal.machine(16, 3, 128, base.copy(merge3 = true))
    for (w <- words) { assertEquals(Lps.run(m, w).outputs, OverlapPal.expectedOutputs(128, w, sc), s"w=$w") }
  }

  test("the margin is one young step: 15/3 + merge3 answers a seed-7 stress palindrome of length 461 wrong, 16/3 + merge3 does not") {
    val ws = OldLag.lagWords(3000, 179, 600, 7L).filter(_.length == 461)
    val m15 = RolePal.machine(15, 3, 128, base.copy(merge3 = true)); val m16 = RolePal.machine(16, 3, 128, base.copy(merge3 = true))
    assert(ws.exists(w => Lps.run(m15, w).outputs != OverlapPal.expectedOutputs(128, w, sc)), "15/3 is wrong on one of them")
    for (w <- ws) { assertEquals(Lps.run(m16, w).outputs, OverlapPal.expectedOutputs(128, w, sc), s"w=$w") }
  }
}
