package research

/** STRATEGY.md §3p: the Lpm machine of the table-free matcher (`TwoWayPal`) against the oracle, run by the Lps interpreter (which
  * also checks every register's and field's kinds after every slot). */
class TwoWayPalSuite extends munit.FunSuite {
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(20, "min")
  private val sc = OverlapPal.Sched(13, 32)

  // all 1,662 switch and every-length words up to 200 agree (`two-way-pal-check 27 1 64 200 100000 beta=13/32`, 263 s); the suite
  // takes every eighth of them and the adversarial words
  test("young 27, old 1, W0 64, beta 13/32: the oracle on switch, adversarial and every-length words up to 200") {
    val m = TwoWayPal.machine(27, 1, 64, sc)
    val all = OverlapProfile.switchWords(200, sc) ++ OverlapProfile.everyLengthWords(200)
    val words = all.indices.filter(_ % 8 == 0).map(all).toVector ++ OverlapProfile.adversarialWords(200, sc).take(20)
    for (w <- words) {
      assertEquals(Lps.run(m, w).outputs, OverlapPal.expectedOutputs(64, w, sc), s"w=$w")
    }
  }
}
