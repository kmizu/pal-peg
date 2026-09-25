package research

/** The pools plan (`POOLS.md`): Task 0's step-demand counters of `OverlapProfile`, then the role machine. */
class PoolsSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")
  private val G = OverlapPal.GapCore

  test("demand counters: bounded by the rates, positive on both roles, and a generous rate never saturates the old bank") {
    val m = OverlapPal.machine(10, 13, 16, G)
    val d = OverlapProfile.adversarialWords(120).map(w => OverlapProfile.profile(m, w, 16).demand).reduce(_.merge(_))
    assert(d.youngMax(0) >= 1 && d.youngMax(0) <= 10, d.summary)
    assert(d.youngMax(1) >= 1 && d.youngMax(1) <= 13, d.summary)
    assert(d.oldMax >= 1 && d.oldMax <= 13, d.summary)
    assert(d.oldHist.values.sum > 0 && d.youngHist.values.sum > 0, d.summary)
    val big = OverlapPal.machine(40, 40, 16, G)
    val d2 = LpsProfile.structuredWords(100).map(w => OverlapProfile.profile(big, w, 16).demand).reduce(_.merge(_))
    assert(d2.oldMax < 40, d2.summary)   // the young bank builds from a source that is complete at birth: it uses every step it gets
  }

  /** the counterexample to the 10/13 grammar of UNDER1000.md: a random palindrome of 128 letters (OverlapProfile.randomWords
    * 200 513, seed 21) on which bank B (h = 96) is late at its first answering tick; both merged PEGs reject it */
  val counterexample128: String = "aaabaaabbabbaababaaaaabbbbaabaabbbabbaaabaababbababaabaababababaababababaabaabababbabaabaaabbabbbaabaabbbbaaaaababaabbabbaaabaaa"

  test("the 10/13 counterexample: bank B late at n = 128 at 10/13; 10/14 and the pool budget young 14 / old 4 answer it") {
    assert(LpsPal.isPal(counterexample128) && counterexample128.length == 128)
    assert(OverlapProfile.profile(OverlapPal.machine(10, 13, 16, G), counterexample128, 16).violations.exists(_.contains("bank 1")))
    assertEquals(OverlapProfile.profile(OverlapPal.machine(10, 14, 16, G), counterexample128, 16).violations, Vector.empty[String])
    assertEquals(OverlapProfile.profile(OverlapPal.machine(14, 14, 16, G, 4), counterexample128, 16, strict = true).violations, Vector.empty[String])
  }

  test("the old-limit model with no limit is the machine of UNDER1000.md (same schedule and slots)") {
    val a = OverlapPal.machine(10, 14, 16, G)
    val b = OverlapPal.machine(10, 14, 16, G, Int.MaxValue)
    assertEquals(a.schedule, b.schedule)
    assertEquals(a.slots.keySet, b.slots.keySet)
  }
}
