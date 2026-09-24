package pal

/** The compiled two-view flag worker and its single-length clock bound. */
class GsDualFlagsSuite extends munit.FunSuite {
  import GsDualFlags.compileDualFlags
  import GsLocalClock.DEFAULT_DUAL

  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")

  def isPalindrome(word: String): Boolean = word == word.reverse

  test("palindrome prefix intervals and single length clock") {
    val program = compileDualFlags()
    val rng = new Mt19937(921)
    val words = Words.below("ab", 10) ++
      (0 until 30).map(_ => rng.word("ab", 512)) ++
      Vector("a" * 2048, "ab" * 2048, ("a" * 8 + "b") * 8 + "b")
    words.foreach { word =>
      Vector(0, word.length / 2, 3 * word.length / 4).foreach { lower =>
        val observer = new DualFlagVM(word, lower, word.length, Some(program))
        val expected = (word.length - 1 to lower by -1).map(n => isPalindrome(word.take(n))).toVector
        assertEquals(observer.runFlags(), expected, (word.take(30), word.length, lower))
        assert(observer.steps <= DEFAULT_DUAL.flagView * math.max(1, word.length) + 1)
      }
    }
  }
}
