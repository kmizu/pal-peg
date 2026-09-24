package research

/** Task B2: the overlap recognizer in the interpreter (births, replay lengths, queue order, oracle) and
  * through the old backend (PEG = interpreter). Rates 64/96 are a high literal rate for the semantics; the
  * working rate is found by `overlap-profile` and recorded separately. */
class OverlapPalSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")

  test("controller at 1/1: births are the anchors, clk selects the family, the replay countdown has depth k (W0 = 2, 4, 8, 64)") {
    for (w0 <- Vector(2, 4, 8, 64)) {
      val m = OverlapPal.machine(1, 1, w0)
      for (w <- Vector("a" * 600, (0 until 700).map(i => if (Integer.bitCount(i) % 2 == 0) 'a' else 'b').mkString)) {
        val pr = OverlapProfile.profile(m, w, w0)
        val births = pr.violations.filter(x => x.contains("anchor") || x.contains("replay countdown") || x.contains("clk selects"))
        assertEquals(births, Vector.empty[String], s"W0=$w0")
        assertEquals(pr.lives.map(_.h), OverlapPal.anchors(w0, w.length).filter(h => OverlapPal.anchors(w0, 3 * w.length).indexOf(h) + 1 < OverlapPal.anchors(w0, 3 * w.length).size), s"W0=$w0 births")
      }
    }
  }

  test("interpreter vs oracle at 64/96: all words <= 10, and the profile (queue order, report bits, output) is clean on the switch words <= 129") {
    val m = OverlapPal.machine(64, 96, 2)
    for (w <- Exhaust.words(10, "ab") if w.nonEmpty) { assertEquals(Lps.run(m, w).outputs, OverlapPal.expectedOutputs(2, w), s"'$w'") }
    for (w <- OverlapProfile.switchWords(129)) {
      val pr = OverlapProfile.profile(m, w, 2)
      assert(pr.violations.isEmpty, s"'${w.take(40)}' (${w.length}): ${pr.violations.take(3).mkString("; ")}")
    }
  }

  test("structured words <= 129 at 64/96: no violation") {
    val m = OverlapPal.machine(64, 96, 2)
    for (w <- LpsProfile.structuredWords(129)) {
      val pr = OverlapProfile.profile(m, w, 2)
      assert(pr.violations.isEmpty, s"'${w.take(40)}' (${w.length}): ${pr.violations.take(3).mkString("; ")}")
    }
  }

  test("PEG = interpreter with the old backend at 1/1 (all words <= 6) and at 2/3 (all words <= 6)") {
    assertEquals(Lps.checkSim(OverlapPal.machine(1, 1, 2), 6), None)
    assertEquals(Lps.checkSim(OverlapPal.machine(2, 3, 2), 6), None)
  }

  test("minAnchor = 4 and 8: false below 3 W0/2, the oracle from there (words <= 10 at 64/96)") {
    for (w0 <- Vector(4, 8)) {
      val m = OverlapPal.machine(64, 96, w0)
      for (w <- Exhaust.words(10, "ab") if w.nonEmpty) { assertEquals(Lps.run(m, w).outputs, OverlapPal.expectedOutputs(w0, w), s"W0=$w0 '$w'") }
    }
  }
}
