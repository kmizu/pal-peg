package pal

/** The derived local-clock service rates bound the flag jobs and the matcher's
  * per-arrival work.
  */
class GsLocalClockSuite extends munit.FunSuite {
  import GsLocalClock.DEFAULT

  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")

  test("flag jobs fit the symbolic head bound") {
    val program = GsFlagHeads.compileFlags()
    val rng = new Mt19937(915)
    val words = scala.collection.mutable.ArrayBuffer.from(Words.below("ab", 9))
    words ++= (0 until 30).map(_ => rng.word("ab", 512))
    var word = "ab"
    (0 until 4).foreach { _ =>
      word = (word + "a") * 8 + "b"
      words += word
    }
    words.foreach { word =>
      val job = new FlagVM(word, 0, word.length, Some(program))
      job.run()
      assert(job.steps <= DEFAULT.flagView * (2 * word.length + 1) + 1, (word.length, word.take(40), job.steps))
    }
  }

  test("matching deadlines include preprocessing") {
    val program = GsMatchHeads.compileMatcher()
    val budget = DEFAULT.matching / 4
    val rng = new Mt19937(916)
    val patterns = scala.collection.mutable.ArrayBuffer.from((1 until 7).flatMap(Words.ofLength("ab", _)))
    patterns ++= Vector("a" * 257, "ab" * 129, ("a" * 8 + "b") * 8 + "b")
    patterns ++= (0 until 15).map(_ => rng.word("ab", 257))
    patterns.foreach { pattern =>
      val text = pattern * 3 + rng.word("ab", 50) + pattern
      val vm = new StreamingMatcher(pattern, Some(program))
      // Deliberately do not drain pattern preprocessing before the first
      // arrival. It must share the fixed per-arrival service budget.
      text.zipWithIndex.foreach { case (char, index) =>
        val end = index + 1
        val before = vm.outputs.size
        vm.append(char)
        var remaining = budget
        while (remaining > 0 && !vm.waiting) {
          vm.step()
          remaining -= 1
        }
        val expected = end >= pattern.length && text.substring(end - pattern.length, end) == pattern
        assertEquals(vm.outputs.drop(before), if (expected) Vector(end) else Vector.empty[Int],
          (pattern.length, pattern.take(30), end))
      }
    }
  }
}
