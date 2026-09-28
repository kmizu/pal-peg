package research

/** STRATEGY.md §3l: the young role's matcher without amortisation, as a reference model. The KMP automaton of the pattern is
  * built by running it on the pattern itself (the builder: state f(k) and the forward sequence of entries after it), and the
  * text is matched by the same one transition per letter; the forward sequences are Kaplan–Tarjan real-time catenable steques
  * (`RtSteque`), so a builder letter and a matcher letter each take a bounded number of steque operations.
  *
  * The entries are those of `GapKmp`: entry k holds the letter P[k] and its gap G(k) = [other(k) .. k-1], the entries a mismatch
  * at state k re-reads before k, where other(k) = δ(f(k), ¬P[k]) on the binary alphabet. The builder at state g = f(k) with
  * live sequence B = [g+1 .. k-1] stores G(k) = B when P[g] ≠ P[k] (other(k) = g + 1) and G(k) = G(g) ++ [g] ++ B otherwise
  * (other(k) = other(g)); then E_k joins the open sequences, and the builder advances (pop) or jumps to other(g) (catenate, then
  * pop). A matcher letter is a pop on a match and a catenate G(j) ++ [j] ++ M followed by a pop on a mismatch; after a full
  * match the matcher takes the builder's final state (f(m) and its sequence), since δ(m, c) = δ(f(m), c).
  */
object RtKmp {
  import RtSteque.*

  final class Entry(val k: Int, val letter: Char, val gap: Node)

  /** one letter's work, for the probes: the cells it stored by kind and the steque operations it made */
  final case class Work(cells: Map[String, Int], ops: Int)

  /** the builder and the matcher of one pattern; `pipelined`: the matcher runs while the entries are built (every new entry is
    * injected into the matcher's sequence too), otherwise it starts from the finished automaton */
  final class Bank(p: String, pipelined: Boolean) {
    require(p.nonEmpty && p.forall(c => c == 'a' || c == 'b'), "a binary pattern")
    val m: Int = p.length
    private given meter: Meter = new Meter
    private var ops = 0
    val entries: scala.collection.mutable.ArrayBuffer[Entry] = scala.collection.mutable.ArrayBuffer(alloc(new Entry(0, p(0), null)))
    private def alloc(e: Entry): Entry = { meter.alloc("entry"); e }
    // the builder: state g = f(k), live = [g+1 .. k-1]
    private var bCur: Entry = entries(0)
    private var bLive: Node = null
    // the matcher: state j (cur = E_j), live = [j+1 .. frontier]; `full` after a full match (the state is m)
    private var mCur: Entry = entries(0)
    private var mLive: Node = null
    var full: Boolean = false
    def built: Boolean = entries.length == m
    private def op[T](x: => T): T = { ops += 1; x }
    private def work[T](f: => T): (T, Work) = {
      meter.reset(); ops = 0
      val r = f
      (r, Work(meter.snapshot, ops))
    }

    /** builds entry k = entries.length */
    def buildStep(): Work = work {
      val k = entries.length
      require(k < m, "the build is finished")
      val c = p(k)
      val g = bCur
      val gap = if (g.letter != c) { bLive } else { op(concat(g.gap, op(push(bLive, g)))) }
      val e = alloc(new Entry(k, c, gap))
      entries += e
      val live1 = op(inject(bLive, e))
      val seq = if (g.letter == c) { live1 } else { op(concat(g.gap, op(push(live1, g)))) }
      val (x, rest) = op(pop(seq))
      bCur = x.asInstanceOf[Entry]; bLive = rest
      if (!full) { mLive = op(inject(mLive, e)) }   // the matcher's sequence is open too (it starts at state 0)
    }._2

    /** the matcher's state (the length of the longest prefix of the pattern that ends here) */
    def state: Int = if (full) { m } else { mCur.k }
    /** whether the matcher can take a letter without waiting for the build */
    def ready(c: Char): Boolean = built || full || mCur.letter != c || mLive != null
    /** one text letter; returns whether the pattern ends here */
    def matchStep(c: Char): (Boolean, Work) = work {
      require(pipelined || built, "a matcher that is not pipelined waits for the whole build")
      require(ready(c), "the matcher would pass the build frontier")
      if (full) {
        // the state m behaves as f(m): the builder's final state
        full = false; mCur = bCur; mLive = bLive
      }
      if (mCur.letter == c) {
        if (mCur.k == m - 1) { full = true; true }
        else { val (x, rest) = op(pop(mLive)); mCur = x.asInstanceOf[Entry]; mLive = rest; false }
      } else {
        val seq = op(concat(mCur.gap, op(push(mLive, mCur))))
        val (x, rest) = op(pop(seq))
        mCur = x.asInstanceOf[Entry]; mLive = rest; false
      }
    }
  }

  /** the naive states: after each letter of `t`, the length of the longest prefix of `p` that ends there */
  def naiveStates(p: String, t: String): Vector[Int] =
    (1 to t.length).toVector.map { i => (math.min(p.length, i) to 0 by -1).find(l => t.substring(i - l, i) == p.substring(0, l)).get }
}

/** STRATEGY.md §3l: the de-amortised matcher on the banks of the overlap recognizer. For every word of the families and every
  * bank born in it (anchor a_i, first answer a_{i+1}, answers up to a_{i+2}), the pattern is the reversed prefix of length a_i
  * and the text the word from t0 = a_{i+1} - a_i. The young window (a_{i+1} - a_i ticks) builds the a_i entries and matches the
  * first a_i letters of the text, pipelined: per tick at most `bPer` builder letters and `mPer` matcher letters, a letter of the
  * text being available from its own tick on; the old window matches one letter per tick. Reported: the cells of one letter by
  * kind (builder and matcher), the cells of one tick of each window by kind, and the banks whose young window ran late. */
object RtKmpProbe {
  import RtKmp.*

  final class Stat {
    private val xs = scala.collection.mutable.Map[String, scala.collection.mutable.ArrayBuffer[Int]]()
    var n = 0
    def add(cells: Map[String, Int]): Unit = {
      n += 1
      for (k <- (cells.keySet ++ xs.keySet) + "total") {
        val b = xs.getOrElseUpdate(k, scala.collection.mutable.ArrayBuffer.fill(n - 1)(0))
        b += (if (k == "total") { cells.values.sum } else { cells.getOrElse(k, 0) })
      }
    }
    def row(k: String): String = {
      val b = xs(k)
      val s = b.sorted
      val mean = b.sum.toDouble / b.length
      f"$k%-6s mean $mean%6.2f  p95 ${s(math.min((s.length * 95) / 100, s.length - 1))}%4d  max ${s.last}%4d"
    }
    def kinds: Vector[String] = xs.keys.toVector.sorted
  }

  private def plus(a: Map[String, Int], b: Map[String, Int]): Map[String, Int] =
    (a.keySet ++ b.keySet).map(k => k -> (a.getOrElse(k, 0) + b.getOrElse(k, 0))).toMap

  def run(words: Vector[String], minAnchor: Int, sc: OverlapPal.Sched, bPer: Int, mPer: Int): String = {
    val bLetter = new Stat; val mLetter = new Stat; val yTick = new Stat; val oTick = new Stat
    var banks = 0; var late = 0; var wrong = 0
    for (w <- words) {
      val as = OverlapPal.anchors(minAnchor, w.length + 1, sc)
      for (i <- as.indices if i + 1 < as.length && as(i + 1) <= w.length) {
        banks += 1
        val h = as(i); val n0 = as(i + 1); val n1 = if (i + 2 < as.length) { as(i + 2) } else { w.length + 1 }
        val b = new Bank(w.take(h).reverse, pipelined = true)
        var x = n0 - h                              // the next text letter w(x); the text starts at t0 = n0 - h
        // young ticks tau = h .. n0 (the bank is born with the letter of tick h; the first answer at n0 needs the letter of
        // tick n0): at tick tau the letters w(0 .. tau - 1) have arrived
        for (tau <- h to n0) {
          var cells = Map[String, Int]()
          for (_ <- 0 until bPer if !b.built) { val wk = b.buildStep(); bLetter.add(wk.cells); cells = plus(cells, wk.cells) }
          var k = 0
          while (k < mPer && x < tau && b.ready(w(x))) {
            val (_, wk) = b.matchStep(w(x)); mLetter.add(wk.cells); cells = plus(cells, wk.cells); x += 1; k += 1
          }
          yTick.add(cells)
        }
        if (x < n0 || !b.built) { late += 1 }
        while (!b.built) { b.buildStep() }
        while (x < n0) { b.matchStep(w(x)); x += 1 }
        // old ticks n = n0 + 1 .. n1 - 1 (the answer at n is about w(0 .. n - 1); the letter w(n - 1) arrives at n)
        for (n <- n0 until math.min(n1, w.length + 1)) {
          if (n > n0) { val (_, wk) = b.matchStep(w(n - 1)); mLetter.add(wk.cells); oTick.add(wk.cells) }
          if ((b.state == h) != LpsPal.isPal(w.take(n))) { wrong += 1 }
        }
      }
    }
    val sb = new StringBuilder
    sb ++= s"rt-kmp W0=$minAnchor${sc.label}, per young tick $bPer builder + $mPer matcher letters: ${words.length} words, $banks banks, " +
      s"$late late young windows, $wrong wrong answers\n"
    for ((label, st) <- Seq("builder letter" -> bLetter, "matcher letter" -> mLetter, "young tick" -> yTick, "old tick" -> oTick)) {
      sb ++= s"-- $label (${st.n})\n"
      for (k <- st.kinds) { sb ++= "  " + st.row(k) + "\n" }
    }
    sb.result()
  }
}
