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
  /** Step demand (under-1000 pools plan, Task 0): per tick and bank, the index of the last universal-step copy that
    * changed any of the bank's registers or bits (copies are deterministic: after the first no-op every later copy
    * is a no-op too). A bank is *young* at tick t while t < start(h) of its current life, *old* from start(h) on;
    * `firstAnswer` is the demand at t = start(h) (the first answering tick, counted as old). */
  /** `oldBacklogMax`: the largest FIFO length of an old bank at the end of a tick; `oldBehindMax`: the longest run of
    * consecutive old ticks that end with the bank behind (letters queued or a pop in progress) */
  final case class Demand(youngMax: Vector[Int] = Vector(0, 0), oldMax: Int = 0, firstAnswerMax: Int = 0,
                          oldHist: Map[Int, Int] = Map(), youngHist: Map[Int, Int] = Map(), oldBacklogMax: Int = 0, oldBehindMax: Int = 0) {
    def merge(o: Demand): Demand = {
      def mh(a: Map[Int, Int], b: Map[Int, Int]): Map[Int, Int] = (a.keySet ++ b.keySet).map(k => k -> (a.getOrElse(k, 0) + b.getOrElse(k, 0))).toMap
      Demand(youngMax.zip(o.youngMax).map { case (x, y) => math.max(x, y) }, math.max(oldMax, o.oldMax), math.max(firstAnswerMax, o.firstAnswerMax),
        mh(oldHist, o.oldHist), mh(youngHist, o.youngHist), math.max(oldBacklogMax, o.oldBacklogMax), math.max(oldBehindMax, o.oldBehindMax))
    }
    def hist(h: Map[Int, Int]): String = h.toVector.sortBy(_._1).map { case (k, c) => s"$k:$c" }.mkString(" ")
    def summary: String = s"demand: young A ${youngMax(0)}, young B ${youngMax(1)}, old $oldMax (first answering tick $firstAnswerMax); " +
      s"old histogram [${hist(oldHist)}]; young histogram [${hist(youngHist)}]; old backlog max $oldBacklogMax, old behind max $oldBehindMax ticks"
  }
  /** Step kinds (STRATEGY.md, experiment E0): every universal step of a young or old bank, classified by its state change —
    * `build letter` (the pattern source advanced), `build pop`, `match fast` (a FIFO letter consumed, no pop started),
    * `match slow` (a FIFO letter consumed, a pop started), `match pop`, `fifo reversal` (the back stack moved to the front),
    * `idle` (no change), `other` (the end of a build, a phase change without a letter). `fastPairs` counts, within a tick,
    * the disjoint pairs of consecutive fast matches: the steps a two-letter fast match would save. */
  final case class StepKinds(young: Map[String, Long] = Map(), old: Map[String, Long] = Map(), youngFastPairs: Long = 0, oldFastPairs: Long = 0) {
    private def mm(a: Map[String, Long], b: Map[String, Long]) = (a.keySet ++ b.keySet).map(k => k -> (a.getOrElse(k, 0L) + b.getOrElse(k, 0L))).toMap
    def merge(o: StepKinds): StepKinds = StepKinds(mm(young, o.young), mm(old, o.old), youngFastPairs + o.youngFastPairs, oldFastPairs + o.oldFastPairs)
    private def row(m: Map[String, Long], pairs: Long): String = {
      val busy = m.filter(k => k._1 != "idle" && !k._1.startsWith("+")).values.sum
      val kinds = Vector("build letter", "build pop", "match fast", "match slow", "match pop", "fifo reversal", "other")
      kinds.map(k => f"$k ${m.getOrElse(k, 0L)} (${100.0 * m.getOrElse(k, 0L) / math.max(busy, 1)}%.1f%%)").mkString(", ") +
        f"; busy $busy, idle ${m.getOrElse("idle", 0L)}; fast pairs $pairs (${100.0 * pairs / math.max(busy, 1)}%.1f%% of the busy steps)" +
        f"; GNODE pushes ${m.getOrElse("+gnode push", 0L)} (${100.0 * m.getOrElse("+gnode push", 0L) / math.max(busy, 1)}%.1f%%)"
    }
    def summary: String = s"young steps: ${row(young, youngFastPairs)}\nold steps: ${row(old, oldFastPairs)}"
  }
  final case class Profile(lives: Vector[BankLife], violations: Vector[String], maxUnitsPerTick: Int, demand: Demand = Demand(), kinds: StepKinds = StepKinds()) {
    def table: String = "| bank | h | build end | rev end | replay done | retire | max backlog | units |\n|---|---|---|---|---|---|---|---|\n" + lives.map(_.row).mkString("\n")
  }
  private val phaseNames = Map(0 -> "build", 1 -> "back", 2 -> "rev", 3 -> "match", 4 -> "mback", 5 -> "idle")
  /** the phase number (0 BUILD, 1 BPOP, 2 REV, 3 MATCH, 4 MPOP, 5 IDLE) in either state encoding (GapKmp `compact`:
    * bits q2 q1 q0 for a full core, q1 q0 for a match-only one; unborn = `cur` (and, for a full core, `src`) null) */
  private def phaseOf(st: Lpm.State, px: String, m: Lps.Machine): Int = if (m.bits.contains(s"${px}q0")) {
    val full = m.bits.contains(s"${px}q2")
    val popping = st.bits(s"${px}q0") || st.bits(s"${px}q1")
    val unborn = st.regs.getOrElse(s"${px}cur", null) == null && (!full || st.regs.getOrElse(s"${px}src", null) == null)
    val side = !full || st.bits(s"${px}q2")
    if (unborn) { 5 } else if (side) { if (popping) { 4 } else { 3 } } else { if (popping) { 1 } else { 0 } }
  } else { (0 until 3).map(i => if (st.bits(s"${px}p$i")) { 1 << i } else { 0 }).sum }
  private def letters(c: Lpm.RCell, m: Lps.Machine): String = { val b = new StringBuilder; var x = c; while (x != null) { b.append(m.alphabet.charAt(x.p1.tag)); x = x.p2 }; b.toString }

  /** Where a bank life's registers are. `Banks` (OverlapPal): family s keeps `m{s}_` for its whole life, slots `M{s}IN`
    * and `M{s}U…`. `Roles` (RolePal): the young life is in `y_` until its first answering tick and in `o_` from then
    * on; slots `IN`, `YU`, `OU`. */
  sealed trait Layout
  case object Banks extends Layout
  case object Roles extends Layout

  /** `strict`: a bank must have left BUILD/REV (phase MATCH or MPOP, pattern source and replay countdown exhausted) by the
    * end of its last young tick — the condition under which a match-only old core can take it over (pools plan).
    * With `Roles`, also: at every handover the old role receives exactly the young role's state plus this tick's letter. */
  /** `fusedIn` (RolePal with `fuseIn`): the IN slot also runs the first old and young steps, so the handover-copy check is
    * skipped and the replay countdown is one lower after IN (the first young step consumes a pattern letter) */
  def profile(m: Lps.Machine, w: String, minAnchor: Int, strict: Boolean = false, layout: Layout = Banks, fusedIn: Boolean = false,
              sc: OverlapPal.Sched = OverlapPal.Half): Profile = {
    val rd = w.reverse
    val n = w.length
    val as = OverlapPal.anchors(minAnchor, 3 * n + 3, sc)
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
    /** the register prefix of family s's current life at tick t (after CT) */
    def px(s: Int, t: Int): String = layout match {
      case Banks => s"m${s}_"
      case Roles => cur(s) match { case Some(life) if t >= start(life.h) => "o_"; case _ => "y_" }
    }
    def isU(s: Int, t: Int, slot: String): Boolean = layout match {
      case Banks => slot.startsWith(s"M${s}U")
      case Roles => cur(s).isDefined && slot.startsWith(if (px(s, t) == "y_") { "YU" } else { "OU" })
    }
    def isIn(s: Int, slot: String): Boolean = layout match { case Banks => slot == s"M${s}IN"; case Roles => slot == "IN" }
    // step demand (Task 0 of the pools plan)
    val prefixes = Vector("m0_", "m1_", "y_", "o_")
    val regsOf = prefixes.map(p => p -> m.regs.map(_._1).filter(_.startsWith(p))).toMap
    val bitsOf = prefixes.map(p => p -> m.bits.filter(_.startsWith(p))).toMap
    def changed(a: Lpm.State, b: Lpm.State, p: String): Boolean =
      regsOf(p).exists(r => !(a.regs.getOrElse(r, null) eq b.regs.getOrElse(r, null))) || bitsOf(p).exists(x => a.bits(x) != b.bits(x))
    val copyIdx = Array(0, 0)
    val demandNow = Array(0, 0)
    val youngMax = Array(0, 0)
    var oldMax = 0
    var firstMax = 0
    val oldHist = mutable.HashMap.empty[Int, Int]
    val youngHist = mutable.HashMap.empty[Int, Int]
    var oldBacklogMax = 0
    var oldBehindMax = 0
    val behindRun = Array(0, 0)
    val kindCount = Array(mutable.HashMap.empty[String, Long], mutable.HashMap.empty[String, Long])   // 0 young, 1 old
    val fastPairs = Array(0L, 0L)
    val fastRun = Array(0, 0)   // per family: consecutive fast matches in this tick
    val runRole = Array(0, 0)
    def closeRun(s: Int): Unit = { fastPairs(runRole(s)) += fastRun(s) / 2; fastRun(s) = 0 }
    Lps.traceSlots(m, w) { (t, slot, st) =>
      if (t != tick) { maxUnits = math.max(maxUnits, unitsThisTick); unitsThisTick = 0; tick = t }
      if (slot == "CT") {
        copyIdx(0) = 0; copyIdx(1) = 0; demandNow(0) = 0; demandNow(1) = 0
        for (s <- 0 to 1 if st.bits(s"b$s")) {
          behindRun(s) = 0   // a new life of family s
          if (!as.contains(t)) { v(s"tick $t: bank $s born at a non-anchor") }
          cur(s).foreach(_.retire = t)
          val life = BankLife(s, t)
          lives += life; cur(s) = Some(life)
        }
        if (as.contains(t) && as.indexOf(t) + 1 < as.size && !st.bits("b0") && !st.bits("b1") && t >= minAnchor) { v(s"tick $t: anchor without a birth") }
      }
      if (slot == "CT") { closeRun(0); closeRun(1) }   // runs do not cross a tick
      for (s <- 0 to 1 if isU(s, t, slot) && prev != null) {
        copyIdx(s) += 1
        val p = px(s, t)
        if (changed(prev, st, p)) { demandNow(s) = copyIdx(s) }
        cur(s).foreach { life =>
          val role = if (t < start(life.h)) { 0 } else { 1 }
          val was = phaseOf(prev, p, m)
          val now = phaseOf(st, p, m)
          def moved(r: String): Boolean = !(prev.regs.getOrElse(s"$p$r", null) eq st.regs.getOrElse(s"$p$r", null))
          val kind = if (!changed(prev, st, p)) { "idle" }
            else if ((was == 0 || was == 1) && moved("src")) { "build letter" }
            else if (was == 1) { "build pop" }
            else if (was == 3 && moved("pendB")) { "fifo reversal" }
            else if (was == 3 && moved("pendF")) { if (now == 3) { "match fast" } else { "match slow" } }
            else if (was == 4) { "match pop" }
            else { "other" }
          kindCount(role)(kind) = kindCount(role).getOrElse(kind, 0L) + 1
          // E2: a mismatch step that pushed GNODE(cur, L) (its next step always unfolds it: the steps S2' would save)
          val newL = st.regs.getOrElse(s"${p}L", null)
          if (moved("L") && newL != null && (newL.tag == 34 || newL.tag == 44) && (newL.p1 eq prev.regs.getOrElse(s"${p}cur", null))) {
            kindCount(role)("+gnode push") = kindCount(role).getOrElse("+gnode push", 0L) + 1
          }
          if (kind == "match fast") { if (fastRun(s) > 0 && runRole(s) != role) { closeRun(s) }; runRole(s) = role; fastRun(s) += 1 } else { closeRun(s) }
        }
      }
      for (s <- 0 to 1 if isIn(s, slot) && st.bits(s"b$s")) {
        val rc = st.regs.getOrElse(s"${px(s, t)}rc", null)
        // k_A = (1 - beta) t (A born at t = W), k_B = 2 beta W = 2 beta t / (1 + beta) (B born at t = (1 + beta) W)
        val want = (if (s == 0) { t - t * sc.num / sc.den } else { 2 * sc.num * t / (sc.den + sc.num) }) - (if (fusedIn) { 1 } else { 0 })
        if (rc == null || rc.tick != want) { v(s"tick $t: bank $s replay countdown depth ${if (rc == null) "null" else rc.tick.toString}, expected $want") }
      }
      if (layout == Roles && !fusedIn && slot == "IN" && prev != null && (st.bits("b0") || st.bits("b1"))) {
        // the handover: the old role is the young role before IN, plus this tick's letter if the bank was not idle
        val core = Vector("cur", "L", "K", "R", "rv", "ks", "tmp", "end").filter(r => m.regs.exists(_._1 == s"y_$r"))
        val same = core.forall(r => prev.regs.getOrElse(s"y_$r", null) eq st.regs.getOrElse(s"o_$r", null)) &&
          Vector("p0", "p1", "p2", "s0", "s1").forall(b => prev.bits(s"y_$b") == st.bits(s"o_$b"))
        val idle = phaseOf(prev, "y_", m) == OverlapPal.IDLE
        val wantQ = queue(prev, "y_", m) + (if (idle) { "" } else { rd(t - 1).toString })
        if (!same) { v(s"tick $t: the handover did not copy the young core registers and phase") }
        if (queue(st, "o_", m) != wantQ) { v(s"tick $t: the old FIFO after the handover is '${queue(st, "o_", m).take(30)}', expected '${wantQ.take(30)}'") }
      }
      for (s <- 0 to 1 if isU(s, t, slot) && prev != null) {
        val p = px(s, t)
        val ph = phaseOf(st, p, m)
        val was = phaseOf(prev, p, m)
        if (was != OverlapPal.IDLE) { unitsThisTick += 1 }
        cur(s).foreach { life =>
          if (was != OverlapPal.IDLE) { val nm = phaseNames(was); life.units(nm) = life.units.getOrElse(nm, 0) + 1 }
          if ((was == 0 || was == 1) && ph == 2 && life.buildEnd < 0) { life.buildEnd = t }
          if (was == 2 && ph == 3 && life.revEnd < 0) {
            life.revEnd = t
            val h = life.h
            val t0 = start(h) - h
            val fifo = queue(st, p, m)
            if (fifo != rd.substring(t0, t)) { v(s"tick $t: bank $s FIFO at the end of REV is '${fifo.take(30)}', expected '${rd.substring(t0, t).take(30)}'") }
            if (st.regs.getOrElse(s"${p}rc", null) != null) { v(s"tick $t: bank $s rc not null at the end of REV") }
          }
          if (life.replayDone < 0 && st.regs.getOrElse(s"${p}rc", null) == null && life.h < t + 1) { life.replayDone = t }
          if (ph == 0 && st.regs.getOrElse(s"${p}rc", null) != null && st.regs.getOrElse(s"${p}pendF", null) == null && st.bits(s"${p}have")) {
            // the first load has happened (have) and the replay countdown still runs: pendF must hold the replay
            v(s"tick $t: bank $s pendF null while the replay countdown runs")
          }
        }
      }
      if (slot == "OUT") {
        for (s <- 0 to 1; life <- cur(s)) {
          val p = px(s, t)
          val d = demandNow(s)
          val st0 = start(life.h)
          if (strict && t + 1 == st0) {
            val ph = phaseOf(st, p, m)
            if ((ph != 3 && ph != 4) || st.regs.getOrElse(s"${p}src", null) != null || st.regs.getOrElse(s"${p}rc", null) != null) {
              v(s"tick $t: bank $s is still building (phase $ph) at the end of its young window (h = ${life.h})")
            }
          }
          if (t < st0) { youngMax(s) = math.max(youngMax(s), d); youngHist(d) = youngHist.getOrElse(d, 0) + 1 }
          else {
            oldMax = math.max(oldMax, d); oldHist(d) = oldHist.getOrElse(d, 0) + 1; if (t == st0) { firstMax = math.max(firstMax, d) }
            val q = queueLen(st, p)
            val behind = q > 0 || phaseOf(st, p, m) == 4 || st.regs.getOrElse(s"${p}rv", null) != null || st.regs.getOrElse(s"${p}tmp", null) != null
            oldBacklogMax = math.max(oldBacklogMax, q)
            behindRun(s) = if (behind) { behindRun(s) + 1 } else { 0 }
            oldBehindMax = math.max(oldBehindMax, behindRun(s))
          }
          val ph = phaseOf(st, p, m)
          val backlog = queueLen(st, p) + (if (st.bits(s"${p}have")) { 1 } else { 0 })
          life.maxBacklog = math.max(life.maxBacklog, backlog)
          if ((ph == 3 || ph == 4) && t >= st0) {
            val t0 = st0 - life.h
            val fifo = (if (st.bits(s"${p}have")) { val c = st.regs(s"${p}c"); m.alphabet.charAt(c.tag).toString } else { "" }) + queue(st, p, m)
            if (!rd.substring(t0, t).endsWith(fifo)) { v(s"tick $t: bank $s FIFO '${fifo.take(30)}' is not a suffix of the text") }
          }
        }
        answering(t) match {
          case Some(h) if t >= 3 =>
            val fam = if ((h & (h - 1)) == 0) { 0 } else { 1 }
            if (layout == Banks) {
              val aAnswers = !OverlapPal.aYoungIn(st, sc)
              if (aAnswers != (fam == 0)) { v(s"tick $t: the controller selects bank ${if (aAnswers) 0 else 1}, the schedule says $fam (h = $h)") }
            }
            val accBit = layout match { case Banks => s"m${fam}_acc"; case Roles => "o_acc" }
            val t0 = start(h) - h
            val want = rd.substring(t0, t).endsWith(rd.take(h).reverse)
            if (st.bits(accBit) != want) { v(s"tick $t: bank $fam reports ${st.bits(accBit)}, the text ${if (want) "does" else "does not"} end with the pattern (h = $h)") }
          case _ => ()
        }
        val wantOut = if (t >= 3 && t < OverlapPal.firstAnswer(minAnchor, sc)) { false } else { LpsPal.isPal(rd.take(t)) }
        if (st.bits("out") != wantOut) { v(s"tick $t: output ${st.bits("out")}, expected $wantOut") }
      }
      prev = st
    }
    maxUnits = math.max(maxUnits, unitsThisTick)
    closeRun(0); closeRun(1)
    Profile(lives.toVector, violations.toVector, maxUnits, Demand(youngMax.toVector, oldMax, firstMax, oldHist.toMap, youngHist.toMap, oldBacklogMax, oldBehindMax),
      StepKinds(kindCount(0).toMap, kindCount(1).toMap, fastPairs(0), fastPairs(1)))
  }

  /** random palindromes, near-palindromes (one letter appended to a palindrome of the other parity) and random words,
    * lengths up to `maxLen` */
  def randomWords(count: Int, maxLen: Int, seed: Long = 21L): Vector[String] = {
    val rnd = new scala.util.Random(seed)
    Vector.fill(count) {
      val half = Vector.fill(1 + rnd.nextInt(math.max(1, maxLen / 2)))(if (rnd.nextBoolean()) 'a' else 'b').mkString
      rnd.nextInt(4) match {
        case 0 => half + half.reverse
        case 1 => half + (if (rnd.nextBoolean()) "a" else "b") + half.reverse
        case 2 => half + half.reverse.drop(1) + (if (rnd.nextBoolean()) "a" else "b")
        case _ => Vector.fill(2 * half.length)(if (rnd.nextBoolean()) 'a' else 'b').mkString
      }
    }.map(_.take(maxLen))
  }

  private def queue(st: Lpm.State, p: String, m: Lps.Machine): String = {
    val f = st.regs.getOrElse(s"${p}pendF", null)
    val b = st.regs.getOrElse(s"${p}pendB", null)
    val nn = st.regs.getOrElse(s"${p}pendN", null)
    val front = if (f == null) { "" } else { letters(f, m) }
    val back = if (b == null) { "" } else { letters(b, m).reverse }
    val next = if (nn == null) { "" } else { letters(nn, m).reverse }
    if (st.bits(s"${p}rev")) { back + front + next } else { front + back + next }
  }
  private def queueLen(st: Lpm.State, p: String): Int = {
    def len(c: Lpm.RCell): Int = { var k = 0; var x = c; while (x != null) { k += 1; x = x.p2 }; k }
    len(st.regs.getOrElse(s"${p}pendF", null)) + len(st.regs.getOrElse(s"${p}pendB", null)) + len(st.regs.getOrElse(s"${p}pendN", null))
  }

  /** adversarial words for the start-up of a bank: `b a^(n-2) b` at every length (the bank whose first answer is n has
    * the pattern a^(h-1) b: the most expensive table build and a full-chain back-up on the last letter), and
    * `a^i b a^j b a^i` with the b's near the anchors (long runs inside the pattern and the text) */
  def adversarialWords(maxLen: Int, sc: OverlapPal.Sched = OverlapPal.Half): Vector[String] = {
    val ws = Vector.newBuilder[String]
    for (n <- 3 to maxLen) { ws += "b" + "a" * (n - 2) + "b" }
    var w = 2
    while (3 * w <= maxLen) {
      for (n <- Vector(sc.bOf(w), 2 * w, 2 * sc.bOf(w), sc.bOf(w) + 1, 2 * w + 1) if n <= maxLen; i <- Vector(1, w / 4, w / 2, w - 1) if i >= 1 && 2 * i + 2 <= n) {
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
  def switchWords(maxLen: Int, sc: OverlapPal.Sched = OverlapPal.Half): Vector[String] = {
    val rnd = new scala.util.Random(9)
    def tm(k: Int): String = (0 until k).map(i => if (Integer.bitCount(i) % 2 == 0) 'a' else 'b').mkString
    def pal(k: Int): String = { val h = tm(k / 2); h + (if (k % 2 == 1) "a" else "") + h.reverse }
    val ws = Vector.newBuilder[String]
    var w = 2
    while (3 * w <= maxLen + 1) {
      for (base <- Vector(sc.bOf(w), 2 * w, 2 * sc.bOf(w)); nn <- Vector(base - 1, base, base + 1) if nn >= 1 && nn <= maxLen) {
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

  /** the words of `probes/runner_words.py` at every length 3 .. maxLen (the Rust-runner check found a 121-letter Thue–Morse
    * palindrome that the switch family, which is built around the anchors, never reaches): `b a^(n-2) b` and its flip,
    * the Thue–Morse palindrome and a flip at n/3, period 3, `a^i b a^j b a^i` with i = n/4, a random palindrome */
  def everyLengthWords(maxLen: Int, seed: Long = 5L): Vector[String] = {
    val rnd = new scala.util.Random(seed)
    def tm(k: Int): String = (0 until k).map(i => if (Integer.bitCount(i) % 2 == 0) 'a' else 'b').mkString
    def pal(k: Int): String = { val h = tm(k / 2); h + (if (k % 2 == 1) "a" else "") + h.reverse }
    def flip(w: String, i: Int): String = w.updated(i, if (w(i) == 'a') 'b' else 'a')
    val ws = Vector.newBuilder[String]
    for (n <- 3 to maxLen) {
      ws += "b" + "a" * (n - 2) + "b"
      ws += "b" + "a" * (n - 3) + "bb"
      val p = pal(n)
      ws += p
      ws += flip(p, n / 3)
      ws += ("aab" * n).take(n)
      val i = math.max(1, n / 4); val j = n - 2 * i - 2
      if (j >= 0) { ws += "a" * i + "b" + "a" * j + "b" + "a" * i }
      val half = Vector.fill(n / 2)(if (rnd.nextBoolean()) 'a' else 'b').mkString
      ws += half + (if (n % 2 == 1) "a" else "") + half.reverse
    }
    ws.result().distinct
  }

  /** One configuration of the rate grid: bank A `a` and bank B `b` copies per tick, the old bank limited to `old`. */
  final case class GridResult(label: String, pass: Boolean, words: Int, firstBad: Option[(String, String)], demand: Demand)

  /** Profile `families` (name -> words, in order) on `m` in parallel over `threads` threads; stop scheduling at the first
    * violating word. The machines, interpreter and profiler keep all mutable state per call, so words are independent. */
  def grid(label: String, m: Lps.Machine, minAnchor: Int, strict: Boolean, families: Vector[(String, Vector[String])], threads: Int,
           layout: Layout = Banks, fusedIn: Boolean = false, sc: OverlapPal.Sched = OverlapPal.Half): GridResult = {
    import java.util.concurrent.{Executors, TimeUnit}
    import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
    val pool = Executors.newFixedThreadPool(threads)
    val failed = new AtomicBoolean(false)
    val first = new AtomicReference[Option[(String, String)]](None)
    val count = new AtomicInteger(0)
    val demand = new AtomicReference[Demand](Demand())
    for ((fam, ws) <- families; w <- ws) {
      pool.submit(new Runnable {
        def run(): Unit = if (!failed.get()) {
          val pr = profile(m, w, minAnchor, strict, layout, fusedIn, sc)
          count.incrementAndGet()
          demand.updateAndGet(d => d.merge(pr.demand))
          if (pr.violations.nonEmpty && failed.compareAndSet(false, true)) { first.set(Some((s"$fam: $w", pr.violations.take(2).mkString("; ")))) }
        }
      })
    }
    pool.shutdown()
    pool.awaitTermination(7, TimeUnit.DAYS)
    GridResult(label, !failed.get(), count.get(), first.get(), demand.get())
  }
}
