package research

/** Task 1 of the pools plan: the role machine (`RolePal`) — young and old register sets with a handover at every birth. */
class RolePalSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")
  private val G = OverlapPal.GapCore

  test("the default gap core is unchanged by the match-only flag: the exported 10/13 grammar has the committed sha256") {
    val m = OverlapPal.machine(10, 13, 16, G)
    val res = LpsCode.compileMeta(m, LpsCode.Options(fallbackBelow = Some(OverlapPal.firstAnswer(16)), minBirth = 16))
    val text = PegOptimize.compact(res.grammar).grammar.render
    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString
    assertEquals(sha, "9ff38bc1da7d831f6d8fa1300270d0937aa52e2302007183adca296fd1cfec97")
  }

  test("interpreter = the expected outputs on all words <= 10 at 16/8 (minAnchor 2)") {
    val m = RolePal.machine(16, 8, 2)
    for (w <- Exhaust.words(10, "ab") if w.nonEmpty) { assertEquals(Lps.run(m, w).outputs, OverlapPal.expectedOutputs(2, w), s"'$w'") }
  }

  test("the role machine is the old-limit model: the same outputs tick by tick, and the role profile is clean (strict, handover copies)") {
    val (ky, ko) = (14, 4)
    val role = RolePal.machine(ky, ko, 16)
    val model = OverlapPal.machine(ky, ky, 16, G, ko)
    val words = OverlapProfile.adversarialWords(160) ++ OverlapProfile.switchWords(160) ++ LpsProfile.structuredWords(160) ++ OverlapProfile.randomWords(60, 129)
    for (w <- words) {
      assertEquals(Lps.run(role, w).outputs, Lps.run(model, w).outputs, s"'${w.take(40)}' (${w.length})")
      val pr = OverlapProfile.profile(role, w, 16, strict = true, layout = OverlapProfile.Roles)
      assert(pr.violations.isEmpty, s"'${w.take(40)}' (${w.length}): ${pr.violations.take(3).mkString("; ")}")
    }
  }

  test("PEG = interpreter through the code backend at 1/1 with minAnchor 4 (fallback below 6) on all words <= 10; rules and reachability") {
    val m = RolePal.machine(1, 1, 4)
    val opts = LpsCode.Options(fallbackBelow = Some(6), minBirth = 4)
    val res = LpsCode.compileMeta(m, opts)
    assert(res.coloring.count <= 4, s"colours ${res.coloring.count}")
    assertEquals(LpsCode.checkSim(m, 10, opts), None)
    for (w <- Exhaust.words(6, "ab") if w.nonEmpty) { assertEquals(LpsCode.checkRules(m, w, opts), None, s"'$w'") }
    for (w <- Exhaust.words(7, "ab") if w.nonEmpty) { assertEquals(LpsWarmup.checkReachable(m, w, 4), None, s"'$w'") }
  }

  test("the warm-up boundary at minAnchor 16: false from 3 up to 23, the oracle from 24 on (first handover at 16, first answer at 24)") {
    val m = RolePal.machine(14, 4, 16)
    def tm(k: Int): String = (0 until k).map(i => if (Integer.bitCount(i) % 2 == 0) 'a' else 'b').mkString
    def pal(k: Int): String = { val h = tm(k / 2); h + (if (k % 2 == 1) "a" else "") + h.reverse }
    for (k <- Vector(15, 16, 17, 23, 24, 25, 31, 32, 33); w <- Vector(pal(k), "a" * k, "b" + "a" * (k - 2) + "b", pal(k).updated(k / 3, 'b'))) {
      assertEquals(Lps.run(m, w).outputs, OverlapPal.expectedOutputs(16, w), s"'$w'")
    }
  }

  private val lean = RolePal.Options(youngReport = false, fuseIn = true, compact = true)
  private val leanest = RolePal.Options(youngReport = false, fuseIn = true, compact = true, sharedRK = true, rearInP3 = true, endAsEntry = true, endOnce = true)

  test("the size options keep the behaviour: the oracle's outputs and a clean role profile; without `compact` also = the old-limit model tick by tick") {
    val opts = Vector(RolePal.Options(youngReport = false), RolePal.Options(mergeTmp = true), RolePal.Options(fuseIn = true), RolePal.Options(compact = true), lean,
      RolePal.Options(mergeTmp = true, sharedRK = true), RolePal.Options(endAsEntry = true), RolePal.Options(sharedRK = true, rearInP3 = true),
      RolePal.Options(compact = true, endOnce = true), leanest)
    val model = OverlapPal.machine(15, 15, 16, G, 4)
    val words = OverlapProfile.adversarialWords(100) ++ LpsProfile.structuredWords(130) ++ OverlapProfile.randomWords(40, 129)
    for (o <- opts) {
      val role = RolePal.machine(15, 4, 16, o)
      for (w <- words) {
        val out = Lps.run(role, w).outputs
        assertEquals(out, OverlapPal.expectedOutputs(16, w), s"$o '${w.take(40)}' (${w.length})")
        if (!o.compact) { assertEquals(out, Lps.run(model, w).outputs, s"$o '${w.take(40)}' (${w.length})") }
        val pr = OverlapProfile.profile(role, w, 16, strict = true, layout = OverlapProfile.Roles, fusedIn = o.fuseIn)
        assert(pr.violations.isEmpty, s"$o '${w.take(40)}' (${w.length}): ${pr.violations.take(3).mkString("; ")}")
      }
    }
  }

  test("the size options through the code backend at minAnchor 8 (fallback below 12): PEG = interpreter on all words <= 13 (1/1) and <= 12 (2/2); rules <= 7; reachability <= 11") {
    // merging rv/ks unites the RNODE and KNODE site domains and fusing puts four FIFO sites into one layer: six colours at
    // 1/1, so these machines need W0 = 8
    for (o <- Vector(RolePal.Options(youngReport = false, mergeTmp = true), RolePal.Options(compact = true), lean, leanest); (ky, ko, maxLen) <- Vector((1, 1, 13), (2, 2, 12))) {
      val m = RolePal.machine(ky, ko, 8, o)
      val opts = LpsCode.Options(fallbackBelow = Some(OverlapPal.firstAnswer(8)), minBirth = 8)
      assertEquals(LpsCode.checkSim(m, maxLen, opts), None, s"$o $ky/$ko")
      for (w <- Exhaust.words(7, "ab") if w.nonEmpty) { assertEquals(LpsCode.checkRules(m, w, opts), None, s"$o $ky/$ko '$w'") }
      for (w <- Exhaust.words(11, "ab") if w.nonEmpty) { assertEquals(LpsWarmup.checkReachable(m, w, 8), None, s"$o $ky/$ko '$w'") }
    }
  }
}
