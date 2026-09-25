package research

import scala.collection.mutable

/** Bank-level profile and checks of `OverlapPal` (plan §4.4, Task B2): per bank the birth, BUILD end, REV
  * end, replay completion, first answer and retirement ticks, the units by phase and the backlog; and the
  * violations: (a) a birth at a non-anchor / a missed anchor, (b) the answering bank chosen by `clk` differs
  * from the schedule, (c) at an answering tick the bank's report bit differs from "the text read so far ends
  * with the pattern" (a true full match must be reported in its own tick), (d) the output differs from the
  * oracle, (e) the FIFO content is not rd[t0:n] at the end of REV or a suffix of it in MATCH, (f) during
  * BUILD the replay is not at the front (pendF null while rc still counts) or rc is not null at the end of REV.
  * A backlog on false ticks is a statistic, not a violation.
  */
object OverlapProfile {
  final case class BankLife(bank: Int, h: Int, var buildEnd: Int = -1, var revEnd: Int = -1, var replayDone: Int = -1, var retire: Int = -1,
                            units: mutable.Map[String, Int] = mutable.LinkedHashMap.empty, var maxBacklog: Int = 0) {
    def row: String = f"| $bank | $h | $buildEnd | $revEnd | $replayDone | $retire | $maxBacklog | " + units.map { case (k, v) => s"$k=$v" }.mkString(" ") + " |"
  }
  final case class Profile(lives: Vector[BankLife], violations: Vector[String], maxUnitsPerTick: Int) {
    def table: String = "| bank | h | build end | rev end | replay done | retire | max backlog | units |\n|---|---|---|---|---|---|---|---|\n" + lives.map(_.row).mkString("\n")
  }
  private val phaseNames = Map(0 -> "build", 1 -> "back", 2 -> "rev", 3 -> "match", 4 -> "mback", 5 -> "idle")
  private def phaseOf(st: Lpm.State, px: String): Int = (0 until 3).map(i => if (st.bits(s"${px}p$i")) { 1 << i } else { 0 }).sum
  private def letters(c: Lpm.RCell, m: Lps.Machine): String = { val b = new StringBuilder; var x = c; while (x != null) { b.append(m.alphabet.charAt(x.p1.tag)); x = x.p2 }; b.toString }

  def profile(m: Lps.Machine, w: String, minAnchor: Int): Profile = {
    val rd = w.reverse
    val n = w.length
    val as = OverlapPal.anchors(minAnchor, 3 * n + 3)
    def start(h: Int): Int = as(as.indexOf(h) + 1)
    def answering(t: Int): Option[Int] = as.find(h => h <= t && t < 2 * h && as.indexOf(h) + 1 < as.size && start(h) <= t)
    val lives = mutable.ArrayBuffer.empty[BankLife]
    val cur = Array[Option[BankLife]](None, None)
    val violations = mutable.ArrayBuffer.empty[String]
    var prev: Lpm.State = null
    var unitsThisTick = 0
    var maxUnits = 0
    var tick = 0
    def v(msg: String): Unit = if (violations.size < 20) { violations += msg }
    Lps.traceSlots(m, w) { (t, slot, st) =>
      if (t != tick) { maxUnits = math.max(maxUnits, unitsThisTick); unitsThisTick = 0; tick = t }
      if (slot == "CT") {
        for (s <- 0 to 1 if st.bits(s"b$s")) {
          if (!as.contains(t)) { v(s"tick $t: bank $s born at a non-anchor") }
          cur(s).foreach(_.retire = t)
          val life = BankLife(s, t)
          lives += life; cur(s) = Some(life)
          val rc = st.regs.getOrElse(s"m${s}_rc", null)
          val want = if (s == 0) { t / 2 } else { 2 * t / 3 }
          // rc is set in the bank's IN slot; checked there
          val _ = (rc, want)
        }
        if (as.contains(t) && as.indexOf(t) + 1 < as.size && !st.bits("b0") && !st.bits("b1") && t >= minAnchor) { v(s"tick $t: anchor without a birth") }
      }
      for (s <- 0 to 1 if slot == s"M${s}IN") {
        if (st.bits(s"b$s")) {
          val rc = st.regs.getOrElse(s"m${s}_rc", null)
          val want = if (s == 0) { t / 2 } else { 2 * t / 3 }
          if (rc == null || rc.tick != want) { v(s"tick $t: bank $s replay countdown depth ${if (rc == null) "null" else rc.tick.toString}, expected $want") }
        }
      }
      for (s <- 0 to 1 if slot == s"M${s}U" && prev != null) {
        val ph = phaseOf(st, s"m${s}_")
        val was = phaseOf(prev, s"m${s}_")
        if (was != OverlapPal.IDLE) { unitsThisTick += 1 }
        cur(s).foreach { life =>
          if (was != OverlapPal.IDLE) { val nm = phaseNames(was); life.units(nm) = life.units.getOrElse(nm, 0) + 1 }
          if ((was == 0 || was == 1) && ph == 2 && life.buildEnd < 0) { life.buildEnd = t }
          if (was == 2 && ph == 3 && life.revEnd < 0) {
            life.revEnd = t
            val h = life.h
            val t0 = start(h) - h
            val fifo = queue(st, s, m)
            if (fifo != rd.substring(t0, t)) { v(s"tick $t: bank $s FIFO at the end of REV is '${fifo.take(30)}', expected '${rd.substring(t0, t).take(30)}'") }
            if (st.regs.getOrElse(s"m${s}_rc", null) != null) { v(s"tick $t: bank $s rc not null at the end of REV") }
          }
          if (life.replayDone < 0 && st.regs.getOrElse(s"m${s}_rc", null) == null && life.h < t + 1) { life.replayDone = t }
          if (ph == 0 && st.regs.getOrElse(s"m${s}_rc", null) != null && st.regs.getOrElse(s"m${s}_pendF", null) == null && st.bits(s"m${s}_have")) {
            // the first load has happened (have) and the replay countdown still runs: pendF must hold the replay
            v(s"tick $t: bank $s pendF null while the replay countdown runs")
          }
        }
      }
      if (slot == "OUT") {
        for (s <- 0 to 1; life <- cur(s)) {
          val ph = phaseOf(st, s"m${s}_")
          val backlog = queueLen(st, s) + (if (st.bits(s"m${s}_have")) { 1 } else { 0 })
          life.maxBacklog = math.max(life.maxBacklog, backlog)
          if (ph == 3 || ph == 4) {
            val h = life.h
            val t0 = start(h) - h
            if (t >= start(h)) {
              val fifo = (if (st.bits(s"m${s}_have")) { val c = st.regs(s"m${s}_c"); m.alphabet.charAt(c.tag).toString } else { "" }) + queue(st, s, m)
              if (!rd.substring(t0, t).endsWith(fifo)) { v(s"tick $t: bank $s FIFO '${fifo.take(30)}' is not a suffix of the text") }
            }
          }
        }
        answering(t) match {
          case Some(h) if t >= 3 =>
            val fam = if ((h & (h - 1)) == 0) { 0 } else { 1 }
            val clkNull = st.regs.getOrElse("clk", null) == null
            if (clkNull != (fam == 0)) { v(s"tick $t: clk selects bank ${if (clkNull) 0 else 1}, the schedule says $fam (h = $h)") }
            val t0 = start(h) - h
            val want = rd.substring(t0, t).endsWith(rd.take(h).reverse)
            if (st.bits(s"m${fam}_acc") != want) { v(s"tick $t: bank $fam reports ${st.bits(s"m${fam}_acc")}, the text ${if (want) "does" else "does not"} end with the pattern (h = $h)") }
          case _ => ()
        }
        val wantOut = if (t >= 3 && t < OverlapPal.firstAnswer(minAnchor)) { false } else { LpsPal.isPal(rd.take(t)) }
        if (st.bits("out") != wantOut) { v(s"tick $t: output ${st.bits("out")}, expected $wantOut") }
      }
      prev = st
    }
    maxUnits = math.max(maxUnits, unitsThisTick)
    Profile(lives.toVector, violations.toVector, maxUnits)
  }

  private def queue(st: Lpm.State, s: Int, m: Lps.Machine): String = {
    val f = st.regs.getOrElse(s"m${s}_pendF", null)
    val b = st.regs.getOrElse(s"m${s}_pendB", null)
    val nn = st.regs.getOrElse(s"m${s}_pendN", null)
    val front = if (f == null) { "" } else { letters(f, m) }
    val back = if (b == null) { "" } else { letters(b, m).reverse }
    val next = if (nn == null) { "" } else { letters(nn, m).reverse }
    if (st.bits(s"m${s}_rev")) { back + front + next } else { front + back + next }
  }
  private def queueLen(st: Lpm.State, s: Int): Int = {
    def len(c: Lpm.RCell): Int = { var k = 0; var x = c; while (x != null) { k += 1; x = x.p2 }; k }
    len(st.regs.getOrElse(s"m${s}_pendF", null)) + len(st.regs.getOrElse(s"m${s}_pendB", null)) + len(st.regs.getOrElse(s"m${s}_pendN", null))
  }

  /** adversarial words for the start-up of a bank: `b a^(n-2) b` at every length (the bank whose first answer is n has
    * the pattern a^(h-1) b: the most expensive table build and a full-chain back-up on the last letter), and
    * `a^i b a^j b a^i` with the b's near the anchors (long runs inside the pattern and the text) */
  def adversarialWords(maxLen: Int): Vector[String] = {
    val ws = Vector.newBuilder[String]
    for (n <- 3 to maxLen) { ws += "b" + "a" * (n - 2) + "b" }
    var w = 2
    while (3 * w <= maxLen) {
      for (n <- Vector(3 * w / 2, 2 * w, 3 * w, 3 * w / 2 + 1, 2 * w + 1) if n <= maxLen; i <- Vector(1, w / 4, w / 2, w - 1) if i >= 1 && 2 * i + 2 <= n) {
        val j = n - 2 * i - 2
        ws += "a" * i + "b" + "a" * j + "b" + "a" * i
        ws += "a" * i + "b" + "a" * j + "b" + "a" * (i - 1) + "b"    // the same with the last letter flipped
      }
      w *= 2
    }
    ws.result().distinct
  }

  /** words around the bank switches: for W = 2..maxW, n in {3W/2, 2W, 3W} +- 1: palindromes, a^n, a^(n-1) b,
    * (ab)*, small-period palindromes, and one flip at the read positions t0-1, t0, h-1, h, n-h-1, n-h */
  def switchWords(maxLen: Int): Vector[String] = {
    val rnd = new scala.util.Random(9)
    def tm(k: Int): String = (0 until k).map(i => if (Integer.bitCount(i) % 2 == 0) 'a' else 'b').mkString
    def pal(k: Int): String = { val h = tm(k / 2); h + (if (k % 2 == 1) "a" else "") + h.reverse }
    val ws = Vector.newBuilder[String]
    var w = 2
    while (3 * w <= maxLen + 1) {
      for (base <- Vector(3 * w / 2, 2 * w, 3 * w); nn <- Vector(base - 1, base, base + 1) if nn >= 1 && nn <= maxLen) {
        val p = pal(nn)
        ws += p
        ws += "a" * nn
        ws += "a" * (nn - 1) + "b"
        ws += ("ab" * nn).take(nn)
        ws += ("aba" * nn).take(nn)
        val h = if ((nn & (nn - 1)) == 0) { nn } else { w }
        val t0 = math.max(0, nn - h)
        for (pos <- Vector(t0 - 1, t0, h - 1, h, nn - h - 1, nn - h) if pos >= 0 && pos < nn) { ws += p.updated(pos, if (p(pos) == 'a') 'b' else 'a') }
        ws += Vector.fill(nn)(if (rnd.nextBoolean()) 'a' else 'b').mkString
      }
      w *= 2
    }
    ws.result().distinct.map(_.reverse)   // the machine reads from the end: the read order is rd = w.reverse
  }
}
