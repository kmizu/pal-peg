package research

/** Exhaustive comparisons of grammars and machines against the palindrome oracle. */
object Exhaust {
  val alphabet: String = "ab"

  def isPal(w: String): Boolean = w == w.reverse

  /** All words over `alphabet` of length 0 to `maxLen`, shortest first. */
  def words(maxLen: Int, alphabet: String = alphabet): Iterator[String] = {
    Iterator.range(0, maxLen + 1).flatMap { n =>
      Iterator.range(0, math.pow(alphabet.length, n).toInt).map { code =>
        val sb = new StringBuilder
        var c = code
        for (_ <- 0 until n) {
          sb += alphabet.charAt(c % alphabet.length)
          c /= alphabet.length
        }
        sb.toString
      }
    }
  }

  /** Ends e > j such that w[j, e) is a palindrome, decreasing. */
  def palPrefixEnds(w: String, j: Int): Vector[Int] =
    (w.length to (j + 1) by -1).filter(e => isPal(w.substring(j, e))).toVector

  /** First word (shortest first) where the grammar and the oracle disagree, with a message. */
  def checkLanguage(g: Grammar, maxLen: Int, oracle: String => Boolean = isPal,
                    start: Option[String] = None, alphabet: String = alphabet): Option[String] = {
    var tested = 0
    var result: Option[String] = None
    val it = words(maxLen, alphabet)
    while (result.isEmpty && it.hasNext) {
      val w = it.next()
      tested += 1
      val got = try { Some(g.accepts(w, start.getOrElse(g.start))) } catch {
        case e: NonTerminating => println(s"non-terminating on '$w': ${e.getMessage}"); None
      }
      got match {
        case None => result = Some(w)
        case Some(b) if b != oracle(w) =>
          println(s"counterexample '$w': grammar=$b oracle=${oracle(w)} (after $tested words)")
          result = Some(w)
        case _ => ()
      }
    }
    if (result.isEmpty) { println(s"ok: agrees with the oracle on all $tested words of length <= $maxLen") }
    result
  }

  /** Compare the extent of `name` at every position with `expected(w, j)`. */
  def checkExtent(g: Grammar, name: String, maxLen: Int, expected: (String, Int) => Option[Int],
                  alphabet: String = alphabet): Option[(String, Int)] = {
    var result: Option[(String, Int)] = None
    val it = words(maxLen, alphabet)
    while (result.isEmpty && it.hasNext) {
      val w = it.next()
      val m = g.matcher(w)
      var j = 0
      while (result.isEmpty && j <= w.length) {
        val got = try { Some(m.rule(name, j)) } catch {
          case e: NonTerminating => println(s"non-terminating $name on '$w' at $j: ${e.getMessage}"); None
        }
        got match {
          case None => result = Some((w, j))
          case Some(x) if x != expected(w, j) =>
            println(s"$name differs on '$w' at $j: got $x want ${expected(w, j)}")
            result = Some((w, j))
          case _ => ()
        }
        j += 1
      }
    }
    if (result.isEmpty) { println(s"ok: $name matches on all words of length <= $maxLen") }
    result
  }

  /** Random words of one length, half of them palindromes. */
  def randomCheck(g: Grammar, count: Int, length: Int, seed: Long = 1L, oracle: String => Boolean = isPal): Option[String] = {
    val rng = new scala.util.Random(seed)
    def randomWord(n: Int): String = Vector.fill(n)(alphabet.charAt(rng.nextInt(alphabet.length))).mkString
    val bad = Iterator.range(0, count).map { _ =>
      if (rng.nextBoolean()) {
        val half = randomWord(length / 2)
        half + (if (length % 2 == 1) { randomWord(1) } else { "" }) + half.reverse
      } else {
        randomWord(length)
      }
    }.find(w => g.accepts(w) != oracle(w))
    bad match {
      case Some(w) => println(s"random counterexample '$w'")
      case None => println(s"ok: $count random words of length $length")
    }
    bad
  }
}
