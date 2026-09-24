package pal

/** The derived batch rates bound all four flag-job intervals. */
class GsBatchClockSuite extends munit.FunSuite {
  import GsBatchClock.DEFAULT_BATCH
  import GsDualFlags.compileDualFlags

  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")

  def isPalindrome(word: String): Boolean = word == word.reverse

  test("all four job intervals") {
    val p = compileDualFlags(unit = false)
    val rng = new Mt19937(1406)
    val words = Words.below("ab", 10) ++
      (for (n <- Vector(32, 64, 128, 256, 512); _ <- 0 until 8) yield rng.word("ab", n)) ++
      Vector("a" * 2048, "ab" * 2048, ("a" * 8 + "b") * 80 + "b")
    words.foreach { word =>
      (1 to 4).foreach { job =>
        val half = math.max(1, word.length / job)
        val prefix = word.take(job * half)
        if (prefix.length == job * half) {
          val lower = (job - 1) * half
          val vm = new DualFlagVM(prefix, lower, prefix.length, Some(p))
          val expected = (prefix.length - 1 to lower by -1).map(n => isPalindrome(prefix.take(n))).toVector
          assertEquals(vm.runFlags(), expected, (word.take(30), word.length, job))
          assert(vm.steps + 1 <= DEFAULT_BATCH.flags * half)
        }
      }
    }
  }
}
