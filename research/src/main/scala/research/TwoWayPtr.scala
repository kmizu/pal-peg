package research

/** STRATEGY.md §3p: the table-free matcher of §3o at the level of the pointer machine, before it is written as an Lpm core. Only
  * what the machine has: cells of four fields (input cells: a letter and the previous input cell), null tests, letter equality,
  * tags, allocation; no comparison of two positions. One young "step" is one unit of work of the phase at hand; the phases are
  * the ones the Lpm core will have. This model runs one bank (pattern P = the reversed prefix of length h, its text) and is checked
  * against `TwoWay` (the plan) and the naive occurrences.
  */
object TwoWayPtr {
  /** a cell: input cells have tag 0/1 (the letter) and f1 = the previous input cell; `pos` is debug only (the pattern position) */
  final class Cell(val tag: Int, val f1: Cell, val f2: Cell, val f3: Cell, val pos: Int = -1) {
    override def toString: String = s"Cell($tag,$pos)"
  }
  val IQC = 3; val NPS = 4; val NPB = 5
  def pf(c: Cell): Cell = if (c == null) { null } else { c.f1 }            // one letter forward in the pattern
  def f2(c: Cell): Cell = if (c == null) { null } else { c.f2 }
  def f3(c: Cell): Cell = if (c == null) { null } else { c.f3 }
  def same(a: Cell, b: Cell): Boolean = a != null && b != null && a.tag == b.tag
  def isN(c: Cell): Boolean = c != null && (c.tag == NPS || c.tag == NPB)

  /** the phases of the young role */
  object Ph extends Enumeration {
    val BuildN, BuildNP, Pass, PerTest, UTest, UBound, Race, Search, Match, Done = Value
  }

  /** what one pass run records */
  final case class PassOut(start: Cell, ks: Cell, pp: Cell, lastChange: Cell, changedLate: Boolean, crossed: Boolean, crossStart: Cell,
                           crossFrontier: Cell, crossEarly: Boolean)

  /** the young role of one bank. `p0` is the birth cell (P[0]); the pattern has `n` letters. */
  final class Young(p0: Cell, n: Int) {
    var steps = 0
    val stepsBy: scala.collection.mutable.Map[String, Int] = scala.collection.mutable.Map().withDefaultValue(0)
    private def tick(ph: String): Unit = { steps += 1; stepsBy(ph) += 1 }

    // ---- N: the pattern backward (N(x) holds P[x], next = N(x-1)); built walking the pattern forward
    var nh: Cell = null
    def buildN(): Unit = {
      var src = p0; var x = 0
      while (src != null) { tick("N"); nh = new Cell(IQC, src, nh, null, x); src = pf(src); x += 1 }
    }
    // ---- N': the pattern forward (N'(x): f1 = P[x], f2 = N'(x+1), f3 = N(x)), tagged small (2x <= n) or big
    var nph: Cell = null
    def buildNP(): Unit = {
      var w = nh; var mid = f2(f2(nh)); var x = n - 1
      while (w != null) { tick("NP"); nph = new Cell(if (mid == null) { NPS } else { NPB }, w.f1, nph, w, x); w = w.f2; mid = f2(f2(mid)); x -= 1 }
    }
    def small(np: Cell): Boolean = np.tag == NPS
    lazy val lastP: Cell = nh.f1                                   // P[n-1]
    lazy val p1: Cell = pf(p0); lazy val p2: Cell = pf(p1)

    /** one run of the maximal-suffix loop on the prefix of length `lenNP` (an N' node: the prefix ends before it; null = all),
      * order `rev`; `lcOther` / `crOther`: the N nodes of the other run's last-change and crossing frontiers (countdowns). */
    def pass(rev: Boolean, lenNP: Cell, lcOther: Cell, crOther: Cell, otherChanged: Boolean, otherCrossed: Boolean): PassOut = {
      def less(a: Cell, b: Cell): Boolean = !same(a, b) && (if (rev) { a.tag == 1 } else { a.tag == 0 })
      var sn = f2(nph); var lg = p0; var msn = nph; var jn = nph
      var ks = lastP; var kc = lastP; var q = nh; var d = f2(nh); var e = p2; var pp = p1; var z: Cell = null
      // the prefix end: a countdown of frontier advances (L - 1 of them), null when the frontier reaches L
      var fc: Cell = if (lenNP == null) { null } else { f2(f2(lenNP.f3)) }
      val prefix = lenNP != null
      var lcd: Cell = if (otherChanged) { f2(lcOther) } else { null }
      var crd: Cell = if (otherCrossed) { f2(crOther) } else { null }
      var lastChange: Cell = null; var late = false; var crossed = false; var crossStart: Cell = null; var crossFrontier: Cell = null
      var early = false
      var crossOpen = false                                        // restarts at the crossing's frontier still refine its start
      var done = if (prefix) { fc == null } else { sn == null }
      while (!done) {
        tick("pass")
        val atF = z == null
        val a = sn.f1; val b = lg
        def advance(): Unit = {
          sn = sn.f2; d = f2(d); e = pf(e)
          if (atF) { if (fc != null) { fc = fc.f2 }; if (lcd != null) { lcd = lcd.f2 }; if (crd != null) { crd = crd.f2 }; crossOpen = false }
          else { z = pf(z) }
        }
        if (less(a, b)) {
          jn = sn; kc = d.f1; ks = d.f1; pp = e; q = nh; lg = msn.f1; advance()
          if (atF && prefix && fc == null) { done = true }
        } else if (same(a, b)) {
          if (pf(kc) != null) { kc = pf(kc); q = q.f2; lg = pf(lg) } else { jn = sn; kc = ks; q = nh; lg = msn.f1 }
          advance()
          if (atF && prefix && fc == null) { done = true }
        } else {
          require(atF, "a restart inside a rescan")
          val newStart = jn.f2
          // k = 1: the new scan position (old j + 2) is one past the frontier, so the restart itself advances the frontier
          val fresh = pf(q.f1) == null
          z = pf(pf(q.f1))
          lastChange = sn.f3; late = lcd == null
          if (!small(newStart) && !crossed) { crossed = true; crossFrontier = sn.f3; early = !otherCrossed || crd != null; crossOpen = true }
          if (crossOpen) { crossStart = newStart }
          msn = newStart; jn = newStart; ks = lastP; kc = lastP; q = nh; pp = p1; sn = f2(newStart); lg = newStart.f1; d = f2(nh); e = p2
          if (fresh) {
            if (fc != null) { fc = fc.f2 }; if (lcd != null) { lcd = lcd.f2 }; if (crd != null) { crd = crd.f2 }; crossOpen = false
            if (prefix && fc == null) { done = true }
          }
        }
        if (!prefix && sn == null) { done = true }
      }
      PassOut(msn, ks, pp, lastChange, lastChange != null && late, crossed, crossStart, crossFrontier, early)
    }

    /** the critical factorization of the prefix before `lenNP` (null = all): two runs; returns (start, ks, pp, crossing start) */
    def critical(lenNP: Cell): (PassOut, PassOut, Cell) = {
      val r1 = pass(rev = false, lenNP, null, null, otherChanged = false, otherCrossed = false)
      val r2 = pass(rev = true, lenNP, r1.lastChange, r1.crossFrontier, r1.lastChange != null, r1.crossed)
      val win = if (r2.changedLate) { r2 } else { r1 }
      val cross = if (r2.crossed && r2.crossEarly) { r2.crossStart } else if (r1.crossed) { r1.crossStart } else { null }
      (win, if (win eq r1) { r2 } else { r1 }, cross)
    }
  }
}
