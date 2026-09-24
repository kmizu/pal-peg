package research

import scala.collection.mutable

/** Slisenko's recognizer of initial palindromes as a *head machine* driven in real time.
  *
  * The machine is the "direct realization" of the recursion `f` (§4 of the paper): the region search
  * (two heads moving outward from a point), the Fischer–Paterson border computation over the region
  * `X = S(x)` (KMP with the failure chain realized by head movement), and the extension of chained
  * lattices (scans of `S(first)` and `S(end)`, switching node whenever the confirmed radius reaches
  * `2h`, which links the next node automatically by the mirror argument; a mismatch below `2h` ends
  * the lattice because the mirror argument then bounds the candidate's radius below `h`).
  *
  * Costs are counted in *units*: one unit is one step of the multi-head machine (every head moves by
  * at most one cell, one comparison). Repositioning walks and counter operations are charged one unit
  * per cell. The machine blocks when a comparison needs a point beyond the frontier.
  *
  * `run(w, speed)` feeds one letter (two points of ω) per tick and lets the machine execute at most
  * `speed` units per tick. A prefix palindrome `W(1..m)` must be certified during tick `m` — this is
  * what the PEG will have to do with a fixed micro-step budget per input position, so the smallest
  * sufficient `speed` over all inputs is the quantity that decides the size of the grammar.
  */
object SlisenkoRT {

  private val SCAN = 0
  private val FINDY_START = 1
  private val KMP = 2
  private val CHAIN = 3
  private val TO_Y = 4
  private val PROBE = 5
  private val LAT_LEFT = 6
  private val LAT_RIGHT = 7
  private val LAT_DONE = 8

  /** Regions of radius at most this are handled without the KMP pass. */
  val SmallRadius: Int = 3

  final class Machine(val w: String) {
    val word = new Slisenko.Word(w)
    val omega: Array[Char] = word.omega
    val n: Int = w.length
    var F: Int = 0                 // points known: ω(1..F)
    var tick: Int = 0
    var units: Long = 0            // total units executed
    val certified = mutable.HashMap.empty[Int, Int]  // centre -> tick of certification
    val trace = mutable.ArrayBuffer.empty[String]    // f-values, for debugging

    // ---- registers -------------------------------------------------------------------------
    var phase: Int = SCAN
    var pending: Int = 0           // units of walking / counting still to pay before the phase acts
    var pendingPhase: Int = 0      // phase that scheduled the pending units (for accounting)
    val unitsBy: Array[Long] = new Array[Long](16)   // units per phase, pending charges attributed to their phase
    val findyHist = mutable.TreeMap.empty[Int, Int]    // radius at FIND_Y entry -> count
    var x: Int = 1                 // current point
    var r: Int = 0                 // confirmed radius of S(x)
    var kEnd: Int = 0              // last node of the symmetric lattice centred at x (0 = none)
    var xl: Int = 0
    var xr: Int = 0
    var fb: Int = 0                // frontier value when the right-moving head was last refreshed
    // KMP
    var fail: Array[Int] = Array.empty
    var kmpI: Int = 0              // positions 1..kmpI of X processed (fail(1..kmpI) known)
    var kmpP: Int = 0              // current border length p = fail(kmpI)
    var b: Int = 0                 // chain value
    var bmax: Int = 0
    var y: Int = 0
    var rY: Int = 0
    var probeLeft: Int = 0         // remaining probe comparisons
    // lattice
    var first: Int = 0
    var end: Int = 0
    var h: Int = 0
    var rFirst: Int = 0
    var rEnd: Int = 0
    var nodes: Int = 0             // number of nodes minus one (m)
    var lMinus: Int = 0

    private def certify(c: Int): Unit = { if (!certified.contains(c)) { certified(c) = tick } }

    private def enterScan(x0: Int, r0: Int, k: Int): Unit = {
      x = x0; r = r0; kEnd = k; phase = SCAN
      trace += s"P$x0"
    }

    private def enterFindY(): Unit = {
      xl = x - r; xr = x + r; phase = FINDY_START
    }

    /** One unit of work. Returns false when the machine is blocked on input (no unit spent). */
    def step(): Boolean = {
      if (pending > 0) { pending -= 1; units += 1; unitsBy(pendingPhase) += 1; return true }
      val phaseAtStart = phase
      val unitsBefore = units
      val result = stepPhase()
      unitsBy(phaseAtStart) += units - unitsBefore
      if (pending > 0) { pendingPhase = phaseAtStart }
      result
    }

    private def stepPhase(): Boolean = {
      phase match {
        case SCAN =>
          val a = x - r - 1
          val bpos = x + r + 1
          if (a < 1) { certify(x); enterFindY(); units += 1; true }
          else if (bpos > F) { false }
          else {
            if (bpos > fb + 2) { pending += F - fb; fb = F }   // refresh the right head's trail up to the frontier
            fb = math.max(fb, bpos)
            units += 1
            if (omega(a) == omega(bpos)) { r += 1 } else { enterFindY() }
            true
          }

        case FINDY_START =>
          val from = math.max(x + 1, kEnd)
          findyHist(r) = findyHist.getOrElse(r, 0) + 1
          units += 1
          if (r == 0 || from > xr) {
            // y = Λ: f(k+1) = x⁺ + 1, heads move by one
            enterScan(xr + 1, 0, 0)
          } else {
            bmax = 2 * (xr - from) + 1
            if (r <= SmallRadius) {
              // tiny region: the border chain is read off directly (a fixed number of symbol tests)
              val len = 2 * r + 1
              def isBorder(bb: Int): Boolean = (0 until bb).forall(j => omega(xl + j) == omega(xr - bb + 1 + j))
              val borders = (len - 2 to 1 by -2).filter(isBorder)
              b = borders.find(_ <= bmax).getOrElse(0)
              pending += r * r
              phase = CHAIN
            } else {
              val len = 2 * r + 1
              fail = new Array[Int](len + 2)
              kmpI = 1; kmpP = 0
              pending += 2                      // P to X[1], T to X[2]
              if (kEnd > x + 1) { pending += r }  // counter (G' − xl) for the chain bound marks
              phase = KMP
            }
          }
          true

        case KMP =>
          val len = 2 * r + 1
          if (kmpI >= len) { b = kmpP; phase = CHAIN; true }
          else {
            // compute fail(kmpI+1); text symbol X[kmpI+1], pattern symbol X[kmpP+1]
            val t = omega(xl + kmpI)          // X[kmpI+1]
            val ps = omega(xl + kmpP)         // X[kmpP+1]
            units += 1
            pending += 2                      // annotation cell, deque bookkeeping (amortized)
            if (t == ps) { kmpP += 1; kmpI += 1; fail(kmpI) = kmpP }
            else if (kmpP == 0) { kmpI += 1; fail(kmpI) = 0 }
            else { val np = fail(kmpP); pending += 2 * (kmpP - np); kmpP = np }   // head walks left along the chain, 2 units per cell
            true
          }

        case CHAIN =>
          units += 1
          if (b == 0) { enterScan(xr + 1, 0, 0); true }
          else if (b > bmax) { val nb = fail(b); pending += 2 * (b - nb); b = nb; true }   // only reached from KMP
          else {
            y = xr - (b - 1) / 2
            rY = xr - y
            pending += b                      // counter (b−1)/2 from the pattern head's offset
            pending += rY + 1                 // right head walks left from xr+1 to y
            phase = TO_Y
            true
          }

        case TO_Y =>
          units += 1
          val d = y - x
          if (d <= rY) { probeLeft = 0; phase = PROBE; true }
          else {
            probeLeft = d - rY
            pending += rY + 1                 // left probe walks from y to y − rY − 1
            pending += math.max(0, F - (xr + 1))   // right probe: fresh head from the frontier to xr+1
            fb = F
            pending += 2 * rY + 1             // counters R − 2rY for the probe length
            phase = PROBE
            true
          }

        case PROBE =>
          if (probeLeft == 0) {
            // x ∈ S(y)
            units += 1
            if (kEnd == y) { enterScan(y, math.max(rY, y - x), 0) }
            else { first = x; end = y; h = y - x; rFirst = r; rEnd = math.max(rY, h); nodes = 1; phase = LAT_LEFT; trace += s"L($x,+$h)" }
            true
          } else {
            val j = (y - x) - probeLeft + 1   // radius being tested
            val a = y - j
            val bpos = y + j
            if (bpos > F) { false }
            else {
              units += 1
              if (omega(a) == omega(bpos)) { probeLeft -= 1; if (probeLeft == 0) { rY = y - x } }
              else {
                // x ∉ S(y): R(y) = j − 1 is final, go straight to FIND_Y for y
                x = y; r = j - 1; kEnd = 0; trace += s"P$y"; enterFindY()
              }
              true
            }
          }

        case LAT_LEFT =>
          // scan S(first) outward; a confirmed radius >= 2h links first − h (mirror argument), so switch node;
          // a mismatch below 2h ends the left side (the candidate's radius is then below h by the same argument)
          if (rFirst >= 2 * h) { first -= h; rFirst = h; nodes += 1; units += 1; true }
          else {
            val a = first - rFirst - 1
            val bpos = first + rFirst + 1
            if (a < 1) { lMinus = 1; phase = LAT_RIGHT; units += 1; true }
            else if (bpos > F) { false }
            else {
              units += 1
              if (omega(a) == omega(bpos)) { rFirst += 1 } else { lMinus = first - rFirst; phase = LAT_RIGHT }
              true
            }
          }

        case LAT_RIGHT =>
          if (rEnd >= 2 * h) { end += h; rEnd = h; nodes += 1; units += 1; true }
          else {
            val a = end - rEnd - 1
            val bpos = end + rEnd + 1
            if (a < 1) { phase = LAT_DONE; units += 1; true }
            else if (bpos > F) { false }
            else {
              if (bpos > fb + 2) { pending += F - fb; fb = F }
              fb = math.max(fb, bpos)
              units += 1
              if (omega(a) == omega(bpos)) {
                rEnd += 1
                val confirmed = end + rEnd
                if (lMinus == 1 && confirmed % 2 == 1) {
                  val c = (confirmed + 1) / 2
                  if (c >= first && (c - first) % h == 0) { certify(c) }
                }
              } else { phase = LAT_DONE }
              true
            }
          }

        case LAT_DONE =>
          val lPlus = end + rEnd
          units += 1
          pending += 2 * h                    // compare the two radii (unary counters below 2h)
          val symmetric = nodes % 2 == 0 && rFirst == rEnd
          if (symmetric) {
            val centre = first + (nodes / 2) * h
            enterScan(centre, (lPlus - lMinus) / 2, end)
          } else {
            x = end; r = rEnd; kEnd = 0; trace += s"P$end"; enterFindY()
          }
          true

        case _ => false
      }
    }
  }

  /** Units per phase over the whole run at unlimited speed. */
  def profile(w: String): Unit = {
    val m = new Machine(w)
    var t = 1
    while (t <= w.length) {
      m.tick = t; m.F = 2 * t
      var go = true
      while (go) { go = m.step() }
      t += 1
    }
    val names = Vector("SCAN", "FINDY_START", "KMP", "CHAIN", "TO_Y", "PROBE", "LAT_LEFT", "LAT_RIGHT", "LAT_DONE")
    println(s"|W|=${w.length} total units=${m.units} per letter=${m.units.toDouble / w.length}")
    for (i <- names.indices) { println(f"  ${names(i)}%-12s ${m.unitsBy(i)}%8d  (${m.unitsBy(i).toDouble / w.length}%6.2f per letter)") }
    println("  FIND_Y entries by radius: " + m.findyHist.map { case (k, v) => s"$k:$v" }.mkString(" "))
    println("  f-values: " + m.trace.size + "; first 60: " + m.trace.take(60).mkString(" "))
  }

  /** Feed `w` letter by letter, `speed` units per tick. Returns a description of the first failure. */
  def run(w: String, speed: Int, verbose: Boolean = false): Option[String] = {
    val m = new Machine(w)
    var bad: Option[String] = None
    var t = 1
    while (bad.isEmpty && t <= w.length) {
      m.tick = t; m.F = 2 * t
      var u = 0
      var go = true
      while (go && u < speed) { if (m.step()) { u += 1 } else { go = false } }
      val isPal = Exhaust.isPal(w.substring(0, t))
      val cert = m.certified.get(t)
      if (isPal && !cert.contains(t)) { bad = Some(s"'$w': prefix $t is a palindrome, certified at $cert (speed $speed); trace ${m.trace.mkString(" ")}") }
      else if (!isPal && cert.isDefined) { bad = Some(s"'$w': prefix $t is not a palindrome but was certified at $cert; trace ${m.trace.mkString(" ")}") }
      if (verbose) { println(f"tick $t%4d F=${m.F}%5d units=$u%5d phase=${m.phase} x=${m.x} r=${m.r}") }
      t += 1
    }
    bad
  }

  /** Smallest speed for which `run` succeeds (None if the algorithm fails even at `cap`). */
  def minSpeed(w: String, cap: Int = 1 << 20): Option[Int] = {
    if (run(w, cap).isDefined) { return None }
    var lo = 1
    var hi = cap
    while (lo < hi) {
      val mid = lo + (hi - lo) / 2
      if (run(w, mid).isEmpty) { hi = mid } else { lo = mid + 1 }
    }
    Some(lo)
  }

  private def thueMorse(n: Int): String = (0 until n).map(i => if (Integer.bitCount(i) % 2 == 0) { 'a' } else { 'b' }).mkString

  private def periodDoubling(n: Int): String = {
    var s = "a"
    while (s.length < n) { s = s.map(c => if (c == 'a') { "ab" } else { "aa" }).mkString }
    s.take(n)
  }

  /** Worst speed over families of long words. */
  def stress(len: Int, seeds: Int): Unit = {
    def show(name: String, w: String): Unit = minSpeed(w) match {
      case None => println(f"$name%-28s |W|=${w.length}%6d  FAILURE: ${run(w, 1 << 20).getOrElse("?").take(160)}")
      case Some(s) => println(f"$name%-28s |W|=${w.length}%6d  min speed = $s%5d")
    }
    show("fibonacci", fibonacciWord(len))
    show("thue-morse", thueMorse(len))
    show("period-doubling", periodDoubling(len))
    show("zimin", zimin(11).take(len))
    show("a^k b a^k b ...", ("a" * 40 + "b") * (len / 41 + 1) take len)
    show("(a^k b)^m a^k", { val k = 50; (("a" * k + "b") * (len / (k + 1))) + "a" * k })
    for (seed <- 0 until seeds) {
      val rng = new scala.util.Random(seed)
      val half = Vector.fill(len / 2)("ab".charAt(rng.nextInt(2))).mkString
      show(s"random pal seed $seed", half + half.reverse)
      val q = Vector.fill(len / 4)("ab".charAt(rng.nextInt(2))).mkString
      val pp = q + q.reverse
      show(s"pal of pal seed $seed", pp + pp.reverse)
      show(s"random seed $seed", Vector.fill(len)("ab".charAt(rng.nextInt(2))).mkString)
      val r3 = Vector.fill(len / 6)("ab".charAt(rng.nextInt(2))).mkString
      val p3 = r3 + r3.reverse + r3
      show(s"p+p^R+p seed $seed", p3 + p3.reverse)
    }
  }

  private def fibonacciWord(n: Int): String = {
    var a = "a"
    var b = "ab"
    while (b.length < n) { val c = b + a; a = b; b = c }
    b.take(n)
  }

  private def zimin(k: Int): String = if (k == 0) { "a" } else { val z = zimin(k - 1); z + ("ab".charAt(k % 2)).toString + z }

  /** Exhaustive correctness at unbounded speed, then the speed profile. */
  def report(maxLen: Int): Unit = {
    var worst = (0, "")
    var count = 0
    val it = Exhaust.words(maxLen)
    var failure: Option[String] = None
    while (failure.isEmpty && it.hasNext) {
      val w = it.next()
      count += 1
      minSpeed(w) match {
        case None => failure = Some(run(w, 1 << 20).getOrElse("?"))
        case Some(s) => if (s > worst._1) { worst = (s, w) }
      }
    }
    failure match {
      case Some(f) => println(s"ALGORITHM FAILURE: $f")
      case None => println(s"ok: $count words of length <= $maxLen; max speed needed = ${worst._1} on '${worst._2}'")
    }
    val rng = new scala.util.Random(11)
    val samples: Vector[(String, String)] = Vector(
      "a^600" -> "a" * 600,
      "(ab)^300" -> "ab" * 300,
      "(aab)^200" -> "aab" * 200,
      "fibonacci 600" -> fibonacciWord(600),
      "zimin 9" -> zimin(9),
      "a^300 b a^300" -> ("a" * 300 + "b" + "a" * 300),
      "a^200 b a^200 b a^200" -> ("a" * 200 + "b" + "a" * 200 + "b" + "a" * 200),
      "random 600" -> Vector.fill(600)("ab".charAt(rng.nextInt(2))).mkString,
      "random pal 600" -> { val hh = Vector.fill(300)("ab".charAt(rng.nextInt(2))).mkString; hh + hh.reverse },
      "random pal+ 600" -> { val hh = Vector.fill(200)("ab".charAt(rng.nextInt(2))).mkString; hh + hh.reverse + hh })
    for ((name, w) <- samples) {
      minSpeed(w) match {
        case None => println(f"$name%-24s |W|=${w.length}%5d  FAILURE: ${run(w, 1 << 20).getOrElse("?").take(200)}")
        case Some(s) => println(f"$name%-24s |W|=${w.length}%5d  min speed = $s%6d")
      }
    }
  }
}
