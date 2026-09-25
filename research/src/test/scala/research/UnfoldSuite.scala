package research

/** STRATEGY.md S2': a mismatch step unfolds the gap of `cur` itself (`GapKmp.Core(unfold)`) instead of pushing a GNODE for
  * the next pop step. The live sequence is the same one step earlier, so the outputs are unchanged. */
class UnfoldSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(20, "min")
  private val G = OverlapPal.GapCore
  private val finalA = RolePal.Options(youngReport = false, fuseIn = true, compact = true, endAsEntry = true, endOnce = true)

  test("unfold at 64/96: interpreter vs oracle on all words <= 10, and a clean profile on the switch and structured words <= 129") {
    val m = OverlapPal.machine(64, 96, 2, G, unfold = true)
    for (w <- Exhaust.words(10, "ab") if w.nonEmpty) { assertEquals(Lps.run(m, w).outputs, OverlapPal.expectedOutputs(2, w), s"'$w'") }
    for (w <- OverlapProfile.switchWords(129) ++ LpsProfile.structuredWords(129)) {
      val pr = OverlapProfile.profile(m, w, 2)
      assert(pr.violations.isEmpty, s"'${w.take(40)}' (${w.length}): ${pr.violations.take(3).mkString("; ")}")
    }
  }

  test("unfold: PEG = interpreter with the old backend at 1/1 and 2/3 (all words <= 6)") {
    assertEquals(Lps.checkSim(OverlapPal.machine(1, 1, 2, G, unfold = true), 6), None)
    assertEquals(Lps.checkSim(OverlapPal.machine(2, 3, 2, G, unfold = true), 6), None)
  }

  test("unfold: no GNODE is ever pushed by a mismatch (the E2 counter is zero), the pops still happen") {
    val m = OverlapPal.machine(24, 24, 16, G, oldLimit = 6, unfold = true)
    val base = OverlapPal.machine(24, 24, 16, G, oldLimit = 6)
    var pushes = 0L
    var basePushes = 0L
    for (w <- OverlapProfile.randomWords(40, 300, 3L)) {
      pushes += OverlapProfile.profile(m, w, 16, strict = true).kinds.young.getOrElse("+gnode push", 0L)
      basePushes += OverlapProfile.profile(base, w, 16, strict = true).kinds.young.getOrElse("+gnode push", 0L)
    }
    assertEquals(pushes, 0L)
    assert(basePushes > 0, "the baseline pushes GNODEs")
  }

  test("configuration A with unfold: the oracle's outputs and a clean strict role profile (20/6, minAnchor 16)") {
    val role = RolePal.machine(20, 6, 16, finalA.copy(unfold = true))
    for (w <- OverlapProfile.adversarialWords(200) ++ OverlapProfile.switchWords(200) ++ OverlapProfile.randomWords(60, 300, 4L)) {
      assertEquals(Lps.run(role, w).outputs, OverlapPal.expectedOutputs(16, w), s"'${w.take(40)}' (${w.length})")
      val pr = OverlapProfile.profile(role, w, 16, strict = true, layout = OverlapProfile.Roles, fusedIn = true)
      assert(pr.violations.isEmpty, s"'${w.take(40)}' (${w.length}): ${pr.violations.take(3).mkString("; ")}")
    }
  }

  test("configuration A with unfold through the code backend at minAnchor 8: PEG = interpreter on all words <= 12 (1/1) and <= 11 (2/2)") {
    for ((ky, ko, maxLen) <- Vector((1, 1, 12), (2, 2, 11))) {
      val m = RolePal.machine(ky, ko, 8, finalA.copy(unfold = true))
      val opts = LpsCode.Options(fallbackBelow = Some(OverlapPal.firstAnswer(8)), minBirth = 8)
      assertEquals(LpsCode.checkSim(m, maxLen, opts), None, s"$ky/$ko")
      for (w <- Exhaust.words(7, "ab") if w.nonEmpty) { assertEquals(LpsCode.checkRules(m, w, opts), None, s"$ky/$ko '$w'") }
    }
  }
}
