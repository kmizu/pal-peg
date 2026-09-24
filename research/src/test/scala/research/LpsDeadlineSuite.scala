package research

/** Task 4: every flags job finishes before its result is read, requests are never overwritten, and the
  * answering matcher reports full matches at the right tick, on the structured words (up to 129 letters). */
class LpsDeadlineSuite extends munit.FunSuite {
  test("PAL3 at 12/36 meets every deadline on the structured words") {
    val m = LpsPal3.machine(12, 36)
    for (w <- LpsProfile.structuredWords(129)) {
      val pr = LpsProfile.profile(m, w)
      assert(pr.violations.isEmpty, s"'$w': ${pr.violations.mkString("; ")}")
    }
  }
  test("PAL3 at 12/36 is correct on the flags-interval words (L below, at, above lo; chain node; mid-gap)") {
    val m = LpsPal3.machine(12, 36)
    val words = LpsProfile.flagsIntervalWords(140)
    assert(words.size >= 60, s"only ${words.size} words")
    for (w <- words) {
      val pr = LpsProfile.profile(m, w)
      assert(pr.violations.isEmpty, s"'$w': ${pr.violations.mkString("; ")}")
    }
  }
  test("PAL3 at 12/24 is caught by the deadline check and by the oracle check on a short word") {
    val pr = LpsProfile.profile(LpsPal3.machine(12, 24), "aaaaaaa")
    assert(pr.violations.exists(_.contains("deadline")), pr.violations.mkString("; "))
    assert(pr.violations.exists(_.contains("palindrome")), pr.violations.mkString("; "))
  }
}
