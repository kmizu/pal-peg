package research

/** STRATEGY.md §3q: an old role that may fall behind. The old role answers the newest letter; if it has not finished the letters
  * before it, it can still answer "no" when no occurrence can end there: with j the matched length after the last letter it has
  * finished and d the letters since, an occurrence ending at the newest letter would start at least m - j - d letters later than
  * the current partial match, which KMP's maximality forbids unless d >= m - j. In the machine this is the pointer P[j + d]
  * (the current state's letter cell, one step on per arriving letter): non-null means "no".
  *
  * This probe measures what the §3f machine's old role would need with that slack: the old role's steps per letter are read
  * from the machine run with an unbounded old budget, then replayed with k steps a tick; a tick where the old role is behind and
  * P[j_c + A] is null (j_c the state when it last caught up, A the letters since) is a violation. */
object OldLag {
  final case class Result(report: String, violations: Map[Int, Int], behindTrue: Map[Int, Int])

  /** the old role's work per tick of `w` (reading order), from a run with `koBig` old slots: OU slots that changed an o_ register or bit */
  def oldCosts(m: Lps.Machine, w: String): Vector[Int] = {
    val costs = scala.collection.mutable.ArrayBuffer.fill(w.length + 1)(0)
    var prev: Lpm.State = Lpm.State(Map.empty, m.initTrue)
    Lps.traceSlots(m, w) { (tick, slot, st) =>
      if (slot == "OU") {
        val changedReg = st.regs.exists { case (r, c) => r.startsWith("o_") && !(prev.regs.getOrElse(r, null) eq c) }
        val changedBit = (st.bits ++ prev.bits).exists(b => b.startsWith("o_") && st.bits(b) != prev.bits(b))
        if (changedReg || changedBit) { costs(tick) += 1 }
      }
      prev = st
    }
    costs.toVector
  }

  private def kmpStates(p: String, t: String): Vector[Int] = {
    val m = p.length; val f = Array.fill(m + 1)(0); var k = 0
    for (i <- 1 until m) { while (k > 0 && p(i) != p(k)) { k = f(k) }; if (p(i) == p(k)) { k += 1 }; f(i + 1) = k }
    var j = 0
    t.map { c => if (j == m) { j = f(m) }; while (j > 0 && c != p(j)) { j = f(j) }; if (c == p(j)) { j += 1 }; j }.toVector
  }

  def run(words: Vector[String], ky: Int, koBig: Int, minAnchor: Int, opts: RolePal.Options, budgets: Seq[Int]): Result = {
    val m = RolePal.machine(ky, koBig, minAnchor, opts.copy(fuseIn = false))
    val viol = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
    val violExact = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
    /** ticks where the old role is behind and the answer is "yes": the machine answers "no" there (a wrong answer) */
    val behindTrue = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
    val worst = scala.collection.mutable.ArrayBuffer[String]()
    val maxLag = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
    val lagTicks = scala.collection.mutable.Map[Int, Long]().withDefaultValue(0L)
    var wrongLagAnswers = 0L; var ticks = 0L; var maxCost = 0
    val costHist = scala.collection.mutable.Map[Int, Long]().withDefaultValue(0L)
    for (w <- words) {
      val costs = oldCosts(m, w)
      val as = OverlapPal.anchors(minAnchor, w.length + 1, opts.sched)
      for (i <- as.indices if i + 1 < as.length && as(i + 1) <= w.length) {
        val h = as(i); val n0 = as(i + 1); val n1 = if (i + 2 < as.length) { as(i + 2) } else { w.length + 1 }
        val t0 = n0 - h
        val p = w.take(h).reverse
        val js = kmpStates(p, w.substring(t0, math.min(n1, w.length + 1) - 1))
        def jAt(n: Int): Int = js(n - 1 - t0)            // the state after letter n - 1 (tick n)
        for (n <- n0 until math.min(n1, w.length + 1)) { ticks += 1; maxCost = math.max(maxCost, costs(n)); costHist(costs(n)) += 1 }
        for (k <- budgets) {
          var queue = Vector.empty[Int]                       // remaining steps of the unfinished letters, oldest first
          // the young role hands over caught up: the state after the letter of tick n0 - 1
          var jc = js(n0 - 2 - t0); var a = 0
          for (n <- n0 until math.min(n1, w.length + 1)) {
            queue = queue :+ (costs(n) + (if (queue.nonEmpty) { 1 } else { 0 }))   // a letter that arrives behind others is reversed once
            var left = k
            while (left > 0 && queue.nonEmpty) { val d = math.min(left, queue.head); left -= d; if (d == queue.head) { queue = queue.tail } else { queue = (queue.head - d) +: queue.tail } }
            if (queue.isEmpty) { a = 0; jc = jAt(n) }
            else {
              a += 1
              lagTicks(k) += 1; maxLag(k) = math.max(maxLag(k), queue.size)
              if (jc + a >= h) { viol(k) += 1 }
              else if (LpsPal.isPal(w.take(n))) { wrongLagAnswers += 1 }
              if (LpsPal.isPal(w.take(n))) { behindTrue(k) += 1; if (worst.size < 5) { worst += s"k=$k len ${w.length} n=$n: $w" } }
              // the exact slack: the state after the last finished letter, plus the letters behind
              val done = n - queue.size                        // tick of the last finished letter
              val jLast = if (done >= n0) { jAt(done) } else { js(n0 - 2 - t0) }
              if (jLast + queue.size >= h) { violExact(k) += 1 }
            }
          }
        }
      }
    }
    val rows = budgets.map(k => f"  old $k steps a tick: behind on a yes tick ${behindTrue(k)}; slack violations ${viol(k)} (exact slack: ${violExact(k)}), ticks behind ${lagTicks(k)}, most letters behind ${maxLag(k)}")
    val report = s"old-lag ky=$ky W0=$minAnchor${opts.sched.label}: ${words.length} words, $ticks answering ticks, old steps a letter: max $maxCost, " +
      costHist.toVector.sortBy(_._1).map { case (c, x) => s"$c:$x" }.mkString(" ") + s"; lagged answers that were wrong: $wrongLagAnswers\n" + rows.mkString("\n") + "\n" + worst.map("  " + _.take(160)).mkString("\n") + "\n"
    Result(report, viol.toMap, behindTrue.toMap)
  }

  /** palindromes built to stress the old role right before a "yes" tick: u ++ [c] ++ reverse(u) with u from structured generators
    * (Fibonacci, Thue-Morse, periodic with defects, biased random, repeated random blocks), and powers of palindromes (many
    * palindromic prefixes) */
  def lagWords(count: Int, minLen: Int, maxLen: Int, seed: Long): Vector[String] = {
    val rnd = new scala.util.Random(seed)
    def fib(n: Int): String = { var a = "a"; var b = "ab"; while (b.length < n) { val c = b + a; a = b; b = c }; b.take(n) }
    def tm(n: Int): String = (0 until n).map(i => if (Integer.bitCount(i) % 2 == 0) { 'a' } else { 'b' }).mkString
    def flip(c: Char): Char = if (c == 'a') { 'b' } else { 'a' }
    def gen(n: Int): String = rnd.nextInt(6) match {
      case 0 => val f = fib(n + rnd.nextInt(8)); if (rnd.nextBoolean()) { f.take(n) } else { f.takeRight(n).map(flip) }
      case 1 => tm(n + rnd.nextInt(16)).takeRight(n)
      case 2 =>
        val p = 1 + rnd.nextInt(7); val base = Iterator.fill(p)(if (rnd.nextBoolean()) { 'a' } else { 'b' }).mkString
        val rate = rnd.nextDouble() * 0.05
        (0 until n).map(i => { val c = base(i % p); if (rnd.nextDouble() < rate) { flip(c) } else { c } }).mkString
      case 3 => val bias = 0.5 + rnd.nextDouble() * 0.45; Iterator.fill(n)(if (rnd.nextDouble() < bias) { 'a' } else { 'b' }).mkString
      case 4 =>
        val blocks = Vector.fill(1 + rnd.nextInt(3))(Iterator.fill(1 + rnd.nextInt(9))(if (rnd.nextBoolean()) { 'a' } else { 'b' }).mkString)
        val b = new StringBuilder; while (b.length < n) { b.append(blocks(rnd.nextInt(blocks.size))) }; b.toString.take(n)
      case _ =>
        val x0 = gen0(1 + rnd.nextInt(40)); val x = x0 + x0.reverse.drop(rnd.nextInt(2))
        val b = new StringBuilder; while (b.length < n) { b.append(x) }; b.toString.take(n)
    }
    def gen0(n: Int): String = Iterator.fill(n)(if (rnd.nextBoolean()) { 'a' } else { 'b' }).mkString
    Vector.fill(count) {
      val n = minLen + rnd.nextInt(maxLen - minLen + 1)
      val u = gen((n + 1) / 2)
      val mid = if (n % 2 == 1) { u.take(u.length - 1) } else { u }
      val w = if (rnd.nextInt(4) == 0) { u + u.reverse } else { u + mid.reverse.drop(0) }
      w.take(n)
    }
  }

  /** the young role's work per bank of `w` with an ample budget `kyBig` (no fused IN): YU/YUF slots that changed a y_ register or bit
    * between a birth and the next one, divided by the window (ticks); the largest over the banks of the word */
  def youngNeed(m: Lps.Machine, w: String, minAnchor: Int, sc: OverlapPal.Sched): Double = {
    val as = OverlapPal.anchors(minAnchor, w.length + 1, sc)
    val work = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
    var prev: Lpm.State = Lpm.State(Map.empty, m.initTrue)
    Lps.traceSlots(m, w) { (tick, slot, st) =>
      if (slot == "YU" || slot == "YUF") {
        val changed = st.regs.exists { case (r, c) => r.startsWith("y_") && !(prev.regs.getOrElse(r, null) eq c) } ||
          (st.bits ++ prev.bits).exists(b => b.startsWith("y_") && st.bits(b) != prev.bits(b))
        if (changed) { val h = as.filter(_ <= tick).lastOption.getOrElse(0); work(h) += 1 }
      }
      prev = st
    }
    as.indices.collect { case i if i + 1 < as.length && as(i + 1) <= w.length => work(as(i)).toDouble / (as(i + 1) - as(i)) }.maxOption.getOrElse(0.0)
  }
}
