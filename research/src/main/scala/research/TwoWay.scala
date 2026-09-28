package research

/** STRATEGY.md §3m: a real-time matcher without a table (reference model). Crochemore and Perrin's two-way matcher in the
  * "simple version" of Breslauer, Grossi and Mignosi (CPM 2011): for a critical factorization X = u·w with |u| < π(X), a candidate
  * start s is scanned forward on w (one comparison a letter; a mismatch after z matched letters shifts to the letter after it, a
  * full scan shifts by the period, keeping what is already known), and u is checked backward in parallel, one comparison a letter.
  * When |u| > |w| the backward check cannot finish in time, and Breslauer–Grossi–Mignosi's construction takes a second, prefix
  * pattern X' = u'·z' (critical, |u'| ≤ |z'|, X' covering u) whose occurrences a second instance finds in real time; the first
  * instance then scans w only, and X occurs where both agree. The two are coupled by fixed countdowns: B reports an occurrence of
  * X' at start s exactly when A's candidate is s and a fixed number of letters (by how A took the candidate) has passed.
  *
  * Nothing here stores a cell per text letter: the pattern (the reversed prefix of the input) is read forward along the input
  * chain, backward along a list N built once (one cell per pattern letter), the text as it arrives and backward along the input
  * chain; every reset goes to a fixed pattern position and every countdown is a walk to the end of the input chain. The
  * preprocessing (maximal suffixes, periodicity tests, one search) is counted in comparison steps.
  */
object TwoWay {
  /** preprocessing steps (letter comparisons and walks) */
  final class Cost { var steps: Long = 0L }

  /** the maximal suffix of x under the order a < b (rev: b < a), Crochemore–Perrin: (start, period of the suffix) */
  def maxSuffix(x: String, rev: Boolean, c: Cost): (Int, Int) = {
    val n = x.length
    var ms = -1; var j = 0; var k = 1; var p = 1
    while (j + k < n) {
      c.steps += 1
      val a0 = x(j + k); val b0 = x(ms + k)
      val (a, b) = if (rev) { (b0, a0) } else { (a0, b0) }
      if (a < b) { j += k; k = 1; p = j - ms }
      else if (a == b) { if (k != p) { k += 1 } else { j += p; k = 1 } }
      else { ms = j; j = ms + 1; k = 1; p = 1 }
    }
    (ms + 1, p)
  }

  /** a critical factorization (the later maximal suffix) and the period of that suffix */
  def critical(x: String, c: Cost): (Int, Int) = {
    val (s1, p1) = maxSuffix(x, rev = false, c); val (s2, p2) = maxSuffix(x, rev = true, c)
    if (s1 >= s2) { (s1, p1) } else { (s2, p2) }
  }

  /** the first full occurrence of u in t (Crochemore–Perrin, not real time), or -1; `cu`: u's critical factorization if known */
  def search(u: String, t: String, c: Cost, cu: Option[(Int, Int)] = None): Int = {
    val n = u.length; val m = t.length
    if (n == 0) { return 0 }
    val (l, p) = cu.getOrElse(critical(u, c))
    val periodic = l + p <= n && (0 until l).forall { i => c.steps += 1; u(i) == u(p + i) }
    var j = 0; var mem = 0
    while (j <= m - n) {
      var i = if (periodic) { math.max(l, mem) } else { l }
      while (i < n && { c.steps += 1; u(i) == t(i + j) }) { i += 1 }
      if (i >= n) {
        val low = if (periodic) { mem } else { 0 }
        i = l - 1
        while (i >= low && { c.steps += 1; u(i) == t(i + j) }) { i -= 1 }
        if (i < low) { return j }
        if (periodic) { j += p; mem = n - p } else { j += math.max(l, n - l) + 1 }
      } else { j += i - l + 1; mem = 0 }
    }
    -1
  }

  /** the plan of a pattern: A's factorization, shift and memory flag; for two instances, X' and its factorization and period */
  final case class Plan(l: Int, shift: Int, memory: Boolean, xp: Int = 0, lp: Int = 0, mu: Int = 0, switches: Int = 0,
                        trail: List[Int] = Nil) {
    def double: Boolean = xp > 0
  }
  /** the maximal suffix (start, period) of every prefix x[0 .. k), k = 1 .. n, under one order, from one pass of the maximal-suffix
    * loop: the values of x[0 .. k) are those of the loop when its scan first covers position k - 1 (at most 1.5n iterations) */
  def prefixMaxSuffixes(x: String, rev: Boolean, c: Cost): Array[(Int, Int)] = {
    val n = x.length
    val rec = Array.fill(n + 1)((0, 1))
    var ms = -1; var j = 0; var k = 1; var p = 1; var hw = 1
    while (j + k < n) {
      c.steps += 1
      val a0 = x(j + k); val b0 = x(ms + k)
      val (a, b) = if (rev) { (b0, a0) } else { (a0, b0) }
      if (a < b) { j += k; k = 1; p = j - ms }
      else if (a == b) { if (k != p) { k += 1 } else { j += p; k = 1 } }
      else { ms = j; j = ms + 1; k = 1; p = 1 }
      while (hw < j + k) { hw += 1; rec(hw) = (ms + 1, p) }
    }
    while (hw < n) { hw += 1; rec(hw) = (ms + 1, p) }
    rec
  }

  /** The plan. One pass per order records the critical factorization (the later maximal suffix, with its period) of every prefix
    * — in a pointer machine a list R of one cell a pattern letter, each pointing to the cell of its critical position. The first
    * factorization of instance A is that of X; while its left part is the longer, A moves to the critical factorization of that
    * left part u1 = X[0 .. l): when that left part u is longer than the rest, (u, vw) is critical for X with the local period
    * overflowing both sides (it is at least |u| > |vw|, so it is a period of X), and A takes it with no search; otherwise one
    * search for u in vw decides — no occurrence: critical and overflowing again (A takes it, and it is the single case); an
    * occurrence at q: μ(u, vw) = |u| + q fits in vw, and X' = X[0 .. max(l, |u| + μ)] is critically factorized at |u|. */
  def plan(x: String, c: Cost): Plan = {
    val n = x.length
    val lt = prefixMaxSuffixes(x, rev = false, c); val gt = prefixMaxSuffixes(x, rev = true, c)
    c.steps += n                                                   // the list R
    def crit(k: Int): (Int, Int) = if (lt(k)._1 >= gt(k)._1) { lt(k) } else { gt(k) }
    val (lc, pc) = crit(n)
    val periodic = lc + pc <= n && (0 until lc).forall { i => c.steps += 1; x(i) == x(pc + i) }
    var l = lc; var shift = if (periodic) { pc } else { math.max(lc, n - lc) + 1 }; var memory = periodic
    var switches = 0
    var trail = List[Int]()
    while (l > n - l) {
      val lp = math.max(1, crit(l)._1)
      if (lp > n - lp) { trail = l :: trail; l = lp; shift = n - lp + 1; memory = false; switches += 1 }
      else {
        val cu = crit(lp)
        val q = search(x.substring(0, lp), x.substring(lp), c, Some(cu))
        if (q < 0) { trail = l :: trail; l = lp; shift = n - lp + 1; memory = false; switches += 1 }
        else {
          val mu = lp + q
          return Plan(l, shift, memory, math.max(l, lp + mu), lp, mu, switches, (l :: trail).reverse)
        }
      }
    }
    Plan(l, shift, memory, switches = switches, trail = (l :: trail).reverse)
  }

  /** one instance: pattern y (the first `n` letters of the pattern), factorization l, shift, memory; with `backfill`, y[0 .. l)
    * is checked backward (y[l-1] first, against the text backward from s + l - 1), one comparison a letter */
  final class Inst(y: String, val n: Int, val l: Int, val shift: Int, val memory: Boolean, backfill: Boolean) {
    private val bf = backfill && l > 0
    var s = 0; var fi = l; var bi = if (bf) { l } else { 0 }; var bok = true
    /** how the current candidate was taken this letter: 0 none, 1 mismatch, 2 shift, 3 shift with memory */
    var took = 0
    var late = 0
    var work = 0
    private def fresh(start: Int): Unit = { s = start; fi = l; bi = if (bf) { l } else { 0 }; bok = true }
    /** the letter at text position tau; returns true when an occurrence of y ends here */
    def step(t: Int => Char, tau: Int): Boolean = {
      took = 0; work = 0
      var rep = false
      if (s + fi == tau) {
        work += 1
        if (y(fi) == t(tau)) {
          fi += 1
          if (fi == n) {
            if (bi > 0 && bok) { late += 1 }
            rep = bi == 0 && bok
            s += shift
            if (memory && n - shift >= l) { fi = n - shift; bi = 0; bok = true; took = 3 }
            else { fresh(s); took = 2 }
            require(s + fi > tau, "the text never moves back")
          }
        } else { fresh(tau - l + 1); took = 1 }
      }
      // the backward check: y[bi - 1] against t(s + bi - 1), once that letter has arrived
      if (bi > 0 && bok && s + bi - 1 <= tau) {
        work += 1
        if (y(bi - 1) != t(s + bi - 1)) { bok = false }
        bi -= 1
      }
      rep
    }
  }

  /** the real-time matcher of a pattern: one instance, or two coupled by fixed countdowns */
  final class Matcher(val x: String, val plan: Plan) {
    val n: Int = x.length
    val a = new Inst(x, n, plan.l, plan.shift, plan.memory, backfill = !plan.double)
    val b: Inst = if (plan.double) { new Inst(x, plan.xp, plan.lp, plan.mu, memory = true, backfill = true) } else { null }
    private var flag = false
    private var since = 0                 // letters since A took its candidate
    private var due = if (plan.double) { plan.xp } else { 0 }
    /** whether the last letter ended an occurrence */
    var lastReport = false
    /** the letter at text position tau; returns true when an occurrence of x ends here */
    def step(t: Int => Char, tau: Int): Boolean = { lastReport = step0(t, tau); lastReport }
    private def step0(t: Int => Char, tau: Int): Boolean = {
      val ra = a.step(t, tau)
      if (b == null) { return ra }
      val rb = b.step(t, tau)
      since += 1
      // B's report on this letter is about A's candidate before this letter (then X' = X ends here too) or, after A took a new
      // candidate on this letter, about the new one (then the countdown is 0)
      val res = ra && (flag || (rb && since == due))
      if (a.took != 0) {
        since = 0
        due = a.took match { case 1 => plan.xp - plan.l; case _ => a.shift + plan.xp - n }
        flag = (a.took == 3 && plan.xp <= n - a.shift) || (rb && due == 0)
      } else if (rb && since == due) { flag = true }
      res
    }
    def work: Int = a.work + (if (b == null) { 0 } else { b.work })
    def late: Int = a.late + (if (b == null) { 0 } else { b.late })
  }
}

/** STRATEGY.md §3m: the table-free matcher on the banks of the overlap recognizer (as `RtKmpProbe`). The young window of a bank
  * (ticks h .. n0) first builds the list N (one cell per pattern letter) and the forward replay list (one cell per replayed
  * letter) and runs the preprocessing, then matches the text; each tick has a budget of `k` steps (a comparison, a walk step or a
  * cell). Reported: the preprocessing steps per pattern letter, the plans, the matcher's work per letter, the cells per young
  * tick, the smallest budget with which every young window finishes, and the answers against the palindrome oracle. */
object TwoWayProbe {
  import TwoWay.*

  final case class Stats(report: String, wrong: Int, late: Int, tooSlow: Int, prepPerLetterMax: Double, needMax: Double)

  def run(words: Vector[String], minAnchor: Int, sc: OverlapPal.Sched, k: Int): Stats = {
    var maxSwitches = 0
    var banks = 0; var doubles = 0; var switches = 0; var wrong = 0; var late = 0; var tooSlow = 0
    var prepPerLetterMax = 0.0; var prepPerLetterSum = 0.0; var workMax = 0; var needMax = 0.0
    var cellsPerTickMax = 0.0; var cellsPerTickSum = 0.0
    val prepVals = scala.collection.mutable.ArrayBuffer[Double](); val needVals = scala.collection.mutable.ArrayBuffer[Double]()
    def pct(xs: scala.collection.mutable.ArrayBuffer[Double]): String = {
      val v = xs.sorted; def at(q: Int) = v(math.min(v.length - 1, v.length * q / 100))
      f"p50 ${at(50)}%.2f, p95 ${at(95)}%.2f, p99 ${at(99)}%.2f, max ${v.last}%.2f"
    }
    for (w <- words) {
      val as = OverlapPal.anchors(minAnchor, w.length + 1, sc)
      for (i <- as.indices if i + 1 < as.length && as(i + 1) <= w.length) {
        banks += 1
        val h = as(i); val n0 = as(i + 1); val n1 = if (i + 2 < as.length) { as(i + 2) } else { w.length + 1 }
        val t0 = n0 - h
        val p = w.take(h).reverse
        val c = new Cost
        val pl = plan(p, c)
        if (pl.double) { doubles += 1 }
        switches += pl.switches; maxSwitches = math.max(maxSwitches, pl.switches)
        val cells = 2 * h + (h - t0)                   // N, R (counted in the plan's steps) and the forward replay list
        val prep = c.steps + h + (h - t0)              // building N and the replay list is a step a cell
        prepPerLetterMax = math.max(prepPerLetterMax, prep.toDouble / h); prepPerLetterSum += prep.toDouble / h
        val window = n0 - h + 1
        cellsPerTickMax = math.max(cellsPerTickMax, cells.toDouble / window); cellsPerTickSum += cells.toDouble / window
        val m = new Matcher(p, pl)
        val text: Int => Char = x => w(t0 + x)
        // young: budget k a tick, preprocessing first, then the letters that have arrived (text letter x at tick t0 + x + 1)
        prepVals += prep.toDouble / h
        var left = prep; var x = 0; var youngWork = 0L
        for (tau <- h to n0) {
          var budget = k
          val d = math.min(budget.toLong, left); left -= d; budget -= d.toInt
          while (left == 0 && x < n0 - t0 && t0 + x < tau && budget > 0) {
            m.step(text, x); workMax = math.max(workMax, m.work); youngWork += m.work; budget -= math.max(1, m.work); x += 1
          }
        }
        needMax = math.max(needMax, (prep + youngWork).toDouble / window); needVals += (prep + youngWork).toDouble / window
        if (left > 0 || x < n0 - t0) { tooSlow += 1; while (x < n0 - t0) { m.step(text, x); x += 1 } }
        // answers: at n0 (the letters up to n0 - 1) and at every old tick n (the letter w(n - 1))
        var hit = false
        for (n <- n0 until math.min(n1, w.length + 1)) {
          if (n > n0) { hit = m.step(text, n - 1 - t0); workMax = math.max(workMax, m.work) }
          else { hit = m.lastReport }
          if (hit != LpsPal.isPal(w.take(n))) { wrong += 1 }
        }
        late += m.late
      }
    }
    val report = f"two-way W0=$minAnchor${sc.label}, budget $k steps a young tick: ${words.length} words, $banks banks ($doubles two-instance, " +
      f"$switches switches, at most $maxSwitches in a bank), wrong $wrong, late $late, young windows too slow $tooSlow\n" +
      f"  preprocessing steps a pattern letter: mean ${prepPerLetterSum / banks}%.2f, max $prepPerLetterMax%.2f (" + pct(prepVals) + ")\n" +
      f"  matcher work a letter (comparisons): max $workMax\n" +
      f"  cells a young tick (N, R and the replay list): mean ${cellsPerTickSum / banks}%.2f, max $cellsPerTickMax%.2f\n" +
      f"  steps a young tick needed (preprocessing + matching over the window): max $needMax%.2f (" + pct(needVals) + ")\n"
    Stats(report, wrong, late, tooSlow, prepPerLetterMax, needMax)
  }
}
