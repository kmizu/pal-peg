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

  private def tmPal(n: Int): String = { val h = (0 until n / 2).map(i => if (Integer.bitCount(i) % 2 == 0) 'a' else 'b').mkString; h + (if (n % 2 == 1) "a" else "") + h.reverse }

  test("the working rate 10/13 with minAnchor 16: no violation on the adversarial, switch and structured words up to 200") {
    val m = OverlapPal.machine(10, 13, 16, G)
    for (w <- OverlapProfile.adversarialWords(200) ++ OverlapProfile.switchWords(200) ++ LpsProfile.structuredWords(200)) {
      val pr = OverlapProfile.profile(m, w, 16)
      assert(pr.violations.isEmpty, s"'${w.take(40)}' (${w.length}): ${pr.violations.take(3).mkString("; ")}")
    }
  }

  test("10/13 with minAnchor 16: random palindromes and near-palindromes up to 129 letters (false below 24, the oracle from there)") {
    val m = OverlapPal.machine(10, 13, 16, G)
    val rnd = new scala.util.Random(13)
    for (_ <- 0 until 60) {
      val half = Vector.fill(1 + rnd.nextInt(64))(if (rnd.nextBoolean()) 'a' else 'b').mkString
      val w = rnd.nextInt(3) match { case 0 => half + half.reverse; case 1 => half + half.reverse.drop(1) + "b"; case _ => half + "a" + half.reverse }
      assertEquals(Lps.run(m, w).outputs, OverlapPal.expectedOutputs(16, w), s"'$w'")
    }
  }

  test("the measured rate boundary: bank A at 9 misses the Thue–Morse palindrome of 193 letters (h = 128), bank B at 12 the one of 65 (h = 48)") {
    assert(OverlapProfile.profile(OverlapPal.machine(9, 13, 16, G), tmPal(193), 16).violations.exists(_.contains("bank 0")), "9/13 should be late at n = 193")
    assert(OverlapProfile.profile(OverlapPal.machine(10, 12, 16, G), tmPal(65), 16).violations.exists(_.contains("bank 1")), "10/12 should be late at n = 65")
    assert(OverlapProfile.profile(OverlapPal.machine(10, 13, 16, G), tmPal(193), 16).violations.isEmpty)
    assert(OverlapProfile.profile(OverlapPal.machine(10, 13, 16, G), tmPal(65), 16).violations.isEmpty)
  }

  test("the start-up family alone does not set the rate: 8/11 passes b a^46 b (n = 48) but misses the Thue–Morse palindrome of 48 letters (h = 32)") {
    val m = OverlapPal.machine(8, 11, 16, G)
    assert(OverlapProfile.profile(m, "b" + "a" * 46 + "b", 16).violations.isEmpty)
    assert(OverlapProfile.profile(m, tmPal(48), 16).violations.exists(_.contains("bank 0")), "8/11 should be late on the Thue–Morse palindrome of 48 letters")
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
