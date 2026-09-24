package pal

/** The compiled palindrome-prefix flag worker on the view `u # reverse(u)`. */
class GsFlagHeadsSuite extends munit.FunSuite {
  import GsFlagHeads.compileFlags

  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")

  lazy val program: Program = compileFlags()

  def isPalindrome(word: String): Boolean = word == word.reverse

  test("all prefix flags and intervals") {
    val rng = new Mt19937(911)
    val words = Words.below("ab", 9) ++
      (0 until 30).map(_ => rng.word("ab", 120)) ++
      Vector("a" * 257, "ab" * 257, ("a" * 8 + "b") * 8 + "a" * 8)
    words.foreach { word =>
      Vector((0, word.length + 1), (0, word.length), (word.length / 2, word.length)).foreach { case (lower, upper) =>
        val vm = new FlagVM(word, lower, upper, Some(program))
        val expected = (upper - 1 to lower by -1).map(n => isPalindrome(word.take(n))).toVector
        assertEquals(vm.runFlags(), expected, (word.take(30), word.length, lower, upper))
      }
    }
  }
}
