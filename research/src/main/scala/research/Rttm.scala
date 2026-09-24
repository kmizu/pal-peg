package research

/** Real-time multitape machines: exactly one transition per input symbol.
  *
  * A step reads the input symbol and the symbol under each head, writes one symbol per tape,
  * moves each head by -1, 0 or +1 and changes state. Transitions are program lines with wildcards
  * (`None`), tried in order; the first matching line fires. A write of `None` keeps the symbol under
  * the head. The word is accepted when, after the last symbol, `(state, head symbols)` matches an
  * accepting pattern.
  */
object Rttm {
  val Blank: Char = '_'

  final case class Rule(state: String, input: Option[Char], heads: Vector[Option[Char]],
                        newState: String, writes: Vector[Option[Char]], moves: Vector[Int])

  final case class Machine(tapes: Int, start: String, accepting: Vector[(String, Vector[Option[Char]])],
                           inputAlphabet: String, tapeAlphabet: String, rules: Vector[Rule]) {
    require(rules.forall(r => r.heads.size == tapes && r.writes.size == tapes && r.moves.size == tapes))
    require(rules.forall(_.moves.forall(m => m >= -1 && m <= 1)))
    require(tapeAlphabet.contains(Blank))

    def states: Vector[String] = (start +: rules.flatMap(r => Vector(r.state, r.newState))).distinct

    def step(state: String, input: Char, heads: Vector[Char]): Option[Rule] = rules.find { r =>
      r.state == state && r.input.forall(_ == input) && r.heads.zip(heads).forall { case (h, s) => h.forall(_ == s) }
    }

    def acceptsConfig(state: String, heads: Vector[Char]): Boolean = accepting.exists { case (s, pat) =>
      s == state && pat.zip(heads).forall { case (p, x) => p.forall(_ == x) }
    }

    /** Run on `word` (left to right); `None` when no rule applies at some step. */
    def run(word: String): Option[Boolean] = {
      var state = start
      val left = Array.fill(tapes)(List.empty[Char])
      val head = Array.fill(tapes)(Blank)
      val right = Array.fill(tapes)(List.empty[Char])
      var stuck = false
      var idx = 0
      while (!stuck && idx < word.length) {
        step(state, word.charAt(idx), head.toVector) match {
          case None => stuck = true
          case Some(r) =>
            for (i <- 0 until tapes) {
              val sym = r.writes(i).getOrElse(head(i))
              r.moves(i) match {
                case 0 => head(i) = sym
                case 1 =>
                  left(i) = sym :: left(i)
                  right(i) match {
                    case x :: rest => head(i) = x; right(i) = rest
                    case Nil => head(i) = Blank
                  }
                case _ =>
                  right(i) = sym :: right(i)
                  left(i) match {
                    case x :: rest => head(i) = x; left(i) = rest
                    case Nil => head(i) = Blank
                  }
              }
            }
            state = r.newState
        }
        idx += 1
      }
      if (stuck) { None } else { Some(acceptsConfig(state, head.toVector)) }
    }
  }

  /** Builder with a compact rule notation: `_` in a pattern is a wildcard, `=` keeps the head symbol. */
  final class Builder(val tapes: Int, val start: String, val inputAlphabet: String, val tapeAlphabet: String) {
    private val rules = Vector.newBuilder[Rule]
    private val accepting = Vector.newBuilder[(String, Vector[Option[Char]])]

    private def pattern(s: String): Vector[Option[Char]] = {
      require(s.length == tapes, s"pattern '$s' must have $tapes symbols")
      s.toVector.map(c => if (c == '*') { None } else { Some(c) })
    }

    /** `rule("P", "a", "*", "P", "a", "+")`: state, input (`*` any), heads, new state, writes (`=` keep), moves (`-0+`). */
    def rule(state: String, input: String, heads: String, newState: String, writes: String, moves: String): Builder = {
      require(input.length == 1 && writes.length == tapes && moves.length == tapes)
      val ws = writes.toVector.map(c => if (c == '=') { None } else { Some(c) })
      val ms = moves.toVector.map { case '-' => -1; case '0' => 0; case '+' => 1; case c => throw new IllegalArgumentException(s"move $c") }
      rules += Rule(state, if (input == "*") { None } else { Some(input.head) }, pattern(heads), newState, ws, ms)
      this
    }

    def accept(state: String, heads: String): Builder = { accepting += ((state, pattern(heads))); this }

    def build(): Machine = Machine(tapes, start, accepting.result(), inputAlphabet, tapeAlphabet, rules.result())
  }

  // ---- examples ----------------------------------------------------------

  /** a^n b^n: push on a's (bottom marked y), pop on b's, accept on the marker. */
  def anbn: Machine = new Builder(1, "E", "ab", "_xy")
    .rule("E", "a", "*", "A", "y", "+")
    .rule("E", "b", "*", "R", "=", "0")
    .rule("A", "a", "*", "A", "x", "+")
    .rule("A", "b", "*", "B", "_", "-")
    .rule("B", "b", "x", "B", "_", "-")
    .rule("B", "*", "*", "R", "=", "0")
    .rule("R", "*", "*", "R", "=", "0")
    .accept("E", "_").accept("B", "y")
    .build()

  /** { u # u^R } with one stack tape; accept in Q with an empty stack. */
  def markedPal: Machine = new Builder(1, "P", "ab#", "_ab")
    .rule("P", "a", "*", "P", "a", "+")
    .rule("P", "b", "*", "P", "b", "+")
    .rule("P", "#", "*", "Q", "=", "-")
    .rule("Q", "a", "a", "Q", "_", "-")
    .rule("Q", "b", "b", "Q", "_", "-")
    .rule("Q", "*", "*", "R", "=", "0")
    .rule("R", "*", "*", "R", "=", "0")
    .accept("Q", "_")
    .build()
}
