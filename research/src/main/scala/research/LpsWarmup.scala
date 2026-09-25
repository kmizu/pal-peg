package research

/** Task A2: PAL3 with a warmup (plan §3.4). No stage of width below `w0` is born; the controller clocks
  * (`pw`, `nb`, `slot`, `sm`, `first`) run from the start; the first machine answer is at n = 2 w0, the
  * lengths 4 <= n < 2 w0 answer false here and are handled by the grammar's bounded fallback. */
object LpsWarmup {
  import Lpm.RCell

  /** wb_1 .. wb_{log2 w0 - 1}: the gate is the last one (a birth at tick 2^j is enabled iff wb_{j-1} is set) */
  def warmBits(w0: Int): Vector[String] = {
    val j0 = Integer.numberOfTrailingZeros(w0)
    (1 until j0).map(i => s"wb_$i").toVector
  }

  def machine(mSteps: Int, fSteps: Int, w0: Int): Lps.Machine = LpsPal3.machine(mSteps, fSteps, w0)

  def firstAnswer(w0: Int): Int = 2 * w0

  /** the outputs the warmup machine must give: the oracle for t < 4 and t >= 2 w0, false in between */
  def expectedOutputs(w0: Int, w: String): Vector[Boolean] = {
    val rd = w.reverse
    (1 to w.length).map(t => if (t >= 4 && t < firstAnswer(w0)) { false } else { LpsPal.isPal(rd.take(t)) }).toVector
  }

  /** the first reachable machine cell (from any register, through fields) born before `minTick`, after any slot */
  def checkReachable(m: Lps.Machine, w: String, minTick: Int): Option[String] = {
    var bad: Option[String] = None
    val seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[RCell, java.lang.Boolean]())
    Lps.traceSlots(m, w) { (tick, slot, st) =>
      if (bad.isEmpty) {
        val stack = scala.collection.mutable.Stack.empty[(String, RCell)]
        for ((r, _) <- m.regs; c = st.regs.getOrElse(r, null) if c != null) { stack.push((r, c)) }
        while (bad.isEmpty && stack.nonEmpty) {
          val (via, c) = stack.pop()
          if (c.tag > m.alphabet.length && seen.add(c)) {
            if (c.tick < minTick) { bad = Some(s"tick $tick slot $slot: register $via reaches a cell of tag ${c.tag} born at tick ${c.tick} (site ${c.layer}/${c.site}) < $minTick") }
            for (f <- 1 to 4; x = c.fld(f) if x != null) { stack.push((via, x)) }
          }
        }
      }
    }
    bad
  }

  /** words around the warmup boundary for W0 = w0: lengths 2W0 ± 1, 3W0 ± 1, 4W0 ± 1 and 2W0 - 1 … */
  def boundaryWords(w0: Int): Vector[String] = {
    def thueMorse(n: Int): String = (0 until n).map(i => if (Integer.bitCount(i) % 2 == 0) 'a' else 'b').mkString
    val lens = Vector(2 * w0 - 1, 2 * w0, 2 * w0 + 1, 3 * w0 - 1, 3 * w0, 4 * w0 - 1, 4 * w0, 4 * w0 + 1)
    val ws = Vector.newBuilder[String]
    for (n <- lens) {
      val half = thueMorse(n / 2)
      val pal = half + (if (n % 2 == 1) "a" else "") + half.reverse
      ws += pal
      val i = n / 3
      ws += pal.updated(i, if (pal(i) == 'a') 'b' else 'a')
      ws += "a" * n
      ws += (0 until n).map(i => if (i % 2 == 0) 'a' else 'b').mkString
      val inner = thueMorse(n / 4)
      val nested = inner + inner.reverse
      ws += (nested + nested.reverse).take(n)
    }
    ws.result().distinct
  }
}
