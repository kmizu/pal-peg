package research

import Expr.*

/** Compile a real-time multitape machine into an ordinary PEG that recognizes the *reversal* of the
  * machine's language: the PEG position `j` holds the configuration after the machine has read
  * `w[n-1], w[n-2], ..., w[j]`.
  *
  * Encoding (one scaffolding-automaton node per position, one rule per field):
  *   - `ST_q`: the state is `q` (Boolean field). At the end of the input, the start state.
  *   - `HS_i_s`: the symbol under head `i` is `s`.
  *   - `LP_i` / `RP_i`: pointers (extents) to the node holding the top cell of the left / right stack
  *     of tape `i`, or a failing extent when the stack is empty. A node allocates at most one cell per
  *     tape: `CS_i_s` (its symbol) and `CB_i` (pointer to the cell below it).
  *   - `M_r` / `R_r`: rule `r` matches / fires (first matching rule of the state wins).
  * The previous node is one character ahead (`.`), so a field `X` of the previous node is `&(. X)`.
  */
object Rttm2Peg {
  import Rttm.*

  private def sym(c: Char): String = c match {
    case '_' => "blank"
    case '#' => "hash"
    case other => other.toString
  }

  def compile(m: Machine): Grammar = {
    val rules = Vector.newBuilder[(String, Expr)]
    def add(name: String, e: Expr): Unit = rules += ((name, e))
    val prev: Expr => Expr = e => And(seq(AnyChar, e))          // field of the previous node
    val atEnd: Expr = Not(AnyChar)
    val states = m.states
    val symbols = m.tapeAlphabet.toVector
    val indexed = m.rules.zipWithIndex

    def st(q: String): Expr = Ref(s"ST_$q")
    def hs(i: Int, s: Char): Expr = Ref(s"HS_${i}_${sym(s)}")
    def lp(i: Int): Expr = Ref(s"LP_$i")
    def rp(i: Int): Expr = Ref(s"RP_$i")
    def cs(i: Int, s: Char): Expr = Ref(s"CS_${i}_${sym(s)}")
    def cb(i: Int): Expr = Ref(s"CB_$i")
    def matches(k: Int): Expr = Ref(s"M_$k")
    def fires(k: Int): Expr = Ref(s"R_$k")

    // Start rule: accepting configuration at position 0, then the whole input.
    val acc = choice(m.accepting.map { case (q, pat) =>
      seq((And(st(q)) +: pat.zipWithIndex.flatMap { case (p, i) => p.map(s => And(hs(i, s))) })*)
    }*)
    add("S", seq(acc, Star(AnyChar), atEnd))

    // Rule matching: the input symbol and the previous node's state and head symbols.
    for ((r, k) <- indexed) {
      val input: Vector[Expr] = r.input.toVector.map(c => And(Lit(c.toString)))
      val heads: Vector[Expr] = r.heads.zipWithIndex.flatMap { case (h, i) => h.map(s => prev(hs(i, s))) }
      add(s"M_$k", seq((input ++ Vector(prev(st(r.state))) ++ heads)*))
      val earlier = indexed.collect { case (r2, k2) if k2 < k && r2.state == r.state => Not(matches(k2)) }
      add(s"R_$k", seq((earlier :+ matches(k))*))
    }

    // State fields.
    for (q <- states) {
      val init = if (q == m.start) { atEnd } else { fail }
      val fired = indexed.collect { case (r, k) if r.newState == q => fires(k) }
      add(s"ST_$q", choice((init +: fired)*))
    }

    for (i <- 0 until m.tapes) {
      // Symbol read after the step: the written symbol (stay), or the top of the stack moved onto.
      def newHead(r: Rule, s: Char): Expr = r.moves(i) match {
        case 0 => r.writes(i) match {
          case Some(t) => if (t == s) { empty } else { fail }
          case None => prev(hs(i, s))
        }
        case dir =>
          val top = if (dir == 1) { rp(i) } else { lp(i) }
          val onCell = And(seq(AnyChar, top, cs(i, s)))
          if (s == Blank) { choice(Not(seq(AnyChar, top)), onCell) } else { onCell }
      }
      for (s <- symbols) {
        val init = if (s == Blank) { atEnd } else { fail }
        val cases = indexed.map { case (r, k) => seq(fires(k), newHead(r, s)) }
        add(s"HS_${i}_${sym(s)}", choice((init +: cases)*))
      }
      // The cell allocated at this node: the symbol pushed when the head moves.
      for (s <- symbols) {
        val cases = indexed.collect { case (r, k) if r.moves(i) != 0 =>
          val written: Expr = r.writes(i) match {
            case Some(t) => if (t == s) { empty } else { fail }
            case None => prev(hs(i, s))
          }
          seq(fires(k), written)
        }
        add(s"CS_${i}_${sym(s)}", if (cases.isEmpty) { fail } else { choice(cases*) })
      }
      // Below pointer of the pushed cell: the old top of the stack pushed onto.
      val belowCases = indexed.collect { case (r, k) if r.moves(i) != 0 =>
        val old = if (r.moves(i) == 1) { lp(i) } else { rp(i) }
        seq(And(fires(k)), AnyChar, old)
      }
      add(s"CB_$i", if (belowCases.isEmpty) { fail } else { choice((belowCases :+ fail)*) })
      // Stack pointers after the step.
      def stack(mine: Expr, pushDir: Int): Expr = {
        val cases = indexed.map { case (r, k) =>
          val value: Expr = r.moves(i) match {
            case 0 => seq(AnyChar, mine)                      // unchanged
            case d if d == pushDir => empty                   // this node's cell is the new top
            case _ => seq(AnyChar, mine, cb(i))               // popped: the cell below the old top
          }
          seq(And(fires(k)), choice(value, fail))
        }
        choice((cases :+ fail)*)
      }
      add(s"LP_$i", stack(lp(i), 1))
      add(s"RP_$i", stack(rp(i), -1))
    }
    new Grammar(rules.result())
  }

  /** The PEG recognizes the reversal of the machine's language; compare on reversed words. */
  def check(m: Machine, maxLen: Int): Option[String] = {
    val g = compile(m)
    val bad = Exhaust.words(maxLen, m.inputAlphabet).find { w =>
      val want = m.run(w.reverse).getOrElse(false)
      g.accepts(w) != want
    }
    bad match {
      case Some(w) => println(s"mismatch on '$w': peg=${g.accepts(w)} machine(reversed)=${m.run(w.reverse)}")
      case None => println(s"ok: PEG agrees with the machine on all words of length <= $maxLen over '${m.inputAlphabet}'")
    }
    bad
  }
}
