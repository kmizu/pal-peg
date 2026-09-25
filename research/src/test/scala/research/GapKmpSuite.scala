package research

/** The gap matcher (`GapKmp`) inside the overlap recognizer: interpreter vs oracle, the bank-level profile
  * (report bit at every answering tick, queue order, replay), and PEG = interpreter through both backends. */
class GapKmpSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")
  private val G = OverlapPal.GapCore

  test("gap core at 64/96: interpreter vs oracle on all words <= 10") {
    val m = OverlapPal.machine(64, 96, 2, G)
    for (w <- Exhaust.words(10, "ab") if w.nonEmpty) { assertEquals(Lps.run(m, w).outputs, OverlapPal.expectedOutputs(2, w), s"'$w'") }
  }

  test("gap core at 64/96: the profile is clean on the switch words and the structured words <= 129") {
    val m = OverlapPal.machine(64, 96, 2, G)
    for (w <- OverlapProfile.switchWords(129) ++ LpsProfile.structuredWords(129)) {
      val pr = OverlapProfile.profile(m, w, 2)
      assert(pr.violations.isEmpty, s"'${w.take(40)}' (${w.length}): ${pr.violations.take(3).mkString("; ")}")
    }
  }

  test("gap core: PEG = interpreter with the old backend at 1/1 and 2/3 (all words <= 6)") {
    assertEquals(Lps.checkSim(OverlapPal.machine(1, 1, 2, G), 6), None)
    assertEquals(Lps.checkSim(OverlapPal.machine(2, 3, 2, G), 6), None)
  }

  test("gap core with minAnchor 4 at 1/1 through the code backend (fallback below 6): PEG = interpreter on all words <= 10") {
    val m = OverlapPal.machine(1, 1, 4, G)
    val opts = LpsCode.Options(fallbackBelow = Some(6), minBirth = 4)
    val res = LpsCode.compileMeta(m, opts)
    assert(res.coloring.count <= 4, s"colours ${res.coloring.count}")
    assertEquals(LpsCode.checkSim(m, 10, opts), None)
    for (w <- Exhaust.words(6, "ab") if w.nonEmpty) { assertEquals(LpsCode.checkRules(m, w, opts), None, s"'$w'") }
    for (w <- Exhaust.words(7, "ab") if w.nonEmpty) { assertEquals(LpsWarmup.checkReachable(m, w, 4), None, s"'$w'") }
  }
}
