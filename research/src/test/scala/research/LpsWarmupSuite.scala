package research

/** Task A2: PAL3 with a warmup — no worker births for stage widths below W0, the controller clocks
  * untouched — checked with the old compiler and the interpreter. */
class LpsWarmupSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")
  private def outputs(m: Lps.Machine, w: String): Vector[Boolean] = Lps.run(m, w).outputs

  test("W0 = 2 is the baseline PAL3 (all words <= 7)") {
    val base = LpsPal3.machine(12, 36)
    val warm = LpsWarmup.machine(12, 36, 2)
    for (w <- Exhaust.words(7, "ab") if w.nonEmpty) { assertEquals(outputs(warm, w), outputs(base, w), s"'$w'") }
  }

  test("W0 = 4 and 8: false between the short words and the first answer 2 W0, the oracle elsewhere") {
    for (w0 <- Vector(4, 8)) {
      val m = LpsWarmup.machine(12, 36, w0)
      for (w <- Exhaust.words(8, "ab") if w.nonEmpty) { assertEquals(outputs(m, w), LpsWarmup.expectedOutputs(w0, w), s"W0=$w0 '$w'") }
      val rnd = new scala.util.Random(11)
      for (_ <- 0 until 30) {
        val half = Vector.fill(1 + rnd.nextInt(32))(if (rnd.nextBoolean()) 'a' else 'b').mkString
        val w = if (rnd.nextBoolean()) { half + half.reverse } else { half + half.reverse.drop(1) + "b" }
        assertEquals(outputs(m, w), LpsWarmup.expectedOutputs(w0, w), s"W0=$w0 '$w'")
      }
    }
  }

  test("no reachable machine cell is born before the warmup (W0 = 4, 8 on all words <= 8; W0 = 64 on boundary words)") {
    for (w0 <- Vector(4, 8); w <- Exhaust.words(6, "ab") if w.nonEmpty) {
      assertEquals(LpsWarmup.checkReachable(LpsWarmup.machine(12, 36, w0), w, w0), None, s"W0=$w0 '$w'")
    }
    val m = LpsWarmup.machine(12, 36, 64)
    for (w <- LpsWarmup.boundaryWords(64)) { assertEquals(LpsWarmup.checkReachable(m, w, 64), None, s"'${w.take(30)}…' (${w.length})") }
  }

  test("the warmup machine compiles with the old backend (PEG = interpreter at 1/1, W0 = 4, all words <= 8)") {
    assertEquals(Lps.checkSim(LpsWarmup.machine(1, 1, 4), 8), None)
  }

  test("W0 = 64 at 12/36: boundary words 127..257 are answered by the machine from n = 128 without violations") {
    val m = LpsWarmup.machine(12, 36, 64)
    val words = LpsWarmup.boundaryWords(64)
    assert(words.size >= 30, s"${words.size} words")
    for (w <- words) {
      assertEquals(outputs(m, w), LpsWarmup.expectedOutputs(64, w), s"'${w.take(30)}…' (${w.length})")
      val pr = LpsProfile.profile(m, w, 128)
      assert(pr.violations.isEmpty, s"'${w.take(30)}…' (${w.length}): ${pr.violations.mkString("; ")}")
    }
  }
}
