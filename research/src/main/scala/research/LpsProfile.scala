package research

import scala.collection.mutable

/** Deadline and work profile of a PAL machine (`LpsPal2`/`LpsPal3`) on one word (Task 4 of the
  * compression plan): every flags job's release, start, finish and deadline ticks and its universal
  * steps by phase; and the checks that the plan asks for:
  *
  *  - a request is never overwritten (a release finds the stage's previous request taken);
  *  - the controller reads a result only after the job of that stage generation and index finished;
  *  - the answering stage's matcher bit equals "the text read so far ends with the pattern" at every
  *    tick (a late full-match report is a violation even when the output happens to be right).
  */
object LpsProfile {
  final case class Job(stage: Int, generation: Int, r: Int, release: Int, var start: Int = -1, var finish: Int = -1, var deadline: Int = -1,
                       units: mutable.Map[String, Int] = mutable.LinkedHashMap.empty) {
    def row: String = f"| $stage | $generation | $r | $release | $start | $finish | $deadline | " + units.map { case (k, v) => s"$k=$v" }.mkString(" ") + " |"
  }
  final case class Profile(jobs: Vector[Job], violations: Vector[String], maxFlagsUnitsPerTick: Int) {
    def table: String = "| stage | gen | r | release | start | finish | deadline | units |\n|---|---|---|---|---|---|---|---|\n" + jobs.map(_.row).mkString("\n")
  }

  private val phaseNames = Map(0 -> "build", 1 -> "back", 2 -> "rev", 3 -> "match", 4 -> "mback", 8 -> "idle", 9 -> "fwd", 10 -> "chain", 11 -> "commit")

  private def phaseOf(st: Lpm.State, px: String, bits: Int): Int = (0 until bits).map(i => if (st.bits(s"${px}p$i")) { 1 << i } else { 0 }).sum
  private def ivOf(st: Lpm.State, s: Int): Int = (0 until 3).map(i => if (st.bits(s"iv${s}_$i")) { 1 << i } else { 0 }).sum

  /** `w` is read from its end, as the machine does; the returned ticks are 1-based reading ticks */
  def profile(m: Lps.Machine, w: String): Profile = {
    val rd = w.reverse
    val jobs = mutable.ArrayBuffer.empty[Job]
    val violations = mutable.ArrayBuffer.empty[String]
    val generation = Array(0, 0)          // births per stage so far
    val birthTick = Array(-1, -1)
    val pending = Array[Option[Job]](None, None)
    var current: Option[Job] = None       // the job the flags worker is executing
    val finished = mutable.HashMap.empty[(Int, Int, Int), Int]   // (stage, generation, r) -> finish tick
    val resultWriter = mutable.HashMap.empty[String, (Int, Int)]  // res register -> (generation, finish tick) of its writer
    var prev: Lpm.State = null
    var flagsUnitsThisTick = 0
    var maxUnits = 0
    var tick = 0
    Lps.traceSlots(m, w) { (t, slot, st) =>
      if (t != tick) { maxUnits = math.max(maxUnits, flagsUnitsThisTick); flagsUnitsThisTick = 0; tick = t }
      if (slot == "CT1") {
        for (s <- 0 to 1) {
          if (st.bits(s"b$s")) { generation(s) += 1; birthTick(s) = t; pending(s) = None }
          if (st.bits(s"rel$s")) {
            val r = ivOf(st, s) - 1
            if (pending(s).exists(_.start < 0)) { violations += s"tick $t: release of (stage $s, r $r) overwrites the untaken request r ${pending(s).get.r}" }
            val j = Job(s, generation(s), r, t)
            jobs += j; pending(s) = Some(j)
            // the result r is read from the boundary into interval r + 2 on
            j.deadline = -1
          }
        }
      }
      if (slot == "FU" && prev != null) {
        val ph = phaseOf(st, "f_", 4)
        val was = phaseOf(prev, "f_", 4)
        val name = phaseNames.getOrElse(was, s"p$was")
        if (was == 8 && ph == 9) {
          // a job was taken: which stage? the one whose pending request was consumed (pp cleared)
          val s = if (prev.bits("pp0") && !st.bits("pp0")) { 0 } else { 1 }
          current = pending(s); current.foreach(_.start = t); pending(s) = None
        }
        if (was != 8 || ph != 8) { flagsUnitsThisTick += 1 }
        current.foreach { j => if (was != 8) { j.units(name) = j.units.getOrElse(name, 0) + 1 } }
        if (ph == 8 && was == 11) { current.foreach { j => j.finish = t; finished((j.stage, j.generation, j.r)) = t; resultWriter(s"res${j.stage}_${j.r}") = (j.generation, t) }; current = None }
      }
      if (slot == "CT2" && prev != null) {
        for (s <- 0 to 1 if st.bits(s"bnd$s") && st.bits(s"alive$s")) {
          val k = ivOf(st, s)
          if (k >= 2 && k <= 5) {
            val r = k - 2
            jobs.find(j => j.stage == s && j.generation == generation(s) && j.r == r).foreach(_.deadline = t)
            resultWriter.get(s"res${s}_$r") match {
              case Some((g, f)) if g == generation(s) && f <= t => ()
              case Some((g, f)) => violations += s"tick $t: stage $s reads result $r of generation $g (finished at $f), its own generation is ${generation(s)}"
              case None => violations += s"tick $t: stage $s reads result $r that no job wrote"
            }
          }
        }
      }
      if (slot == "CT3") {
        // the output itself, against the palindrome oracle (the structured words are then oracle-checked too)
        val want = LpsPal.isPal(rd.take(t))
        if (st.bits("out") != want) { violations += s"tick $t: output ${st.bits("out")}, the prefix read so far ${if (want) { "is" } else { "is not" }} a palindrome" }
        // the matcher of an answering stage must report exactly when the text ends with the pattern
        for (s <- 0 to 1 if st.bits(s"alive$s")) {
          val k = ivOf(st, s)
          if (k >= 2 && k <= 5) {
            val wStart = birthTick(s)                      // pattern = (rd.take W)^R with W = birth tick
            val text = rd.substring(wStart, t)             // letters after the birth
            val pattern = rd.substring(0, wStart).reverse
            val want = text.endsWith(pattern)
            if (st.bits(s"m${s}_acc") != want) { violations += s"tick $t: matcher $s reports ${st.bits(s"m${s}_acc")}, the text ${if (want) { "does" } else { "does not" }} end with the pattern" }
          }
        }
      }
      prev = st
    }
    maxUnits = math.max(maxUnits, flagsUnitsThisTick)
    for (j <- jobs if j.deadline > 0 && (j.finish < 0 || j.finish > j.deadline)) { violations += s"job (stage ${j.stage}, gen ${j.generation}, r ${j.r}) released at ${j.release} finished at ${j.finish}, deadline ${j.deadline}" }
    Profile(jobs.toVector, violations.toVector, maxUnits)
  }

  /** structured words for the deadline checks: powers of two ± 1, unary, alternating, a^k b a^k, Thue–Morse,
    * period doubling, long near-palindromes with one flipped letter */
  def structuredWords(maxLen: Int): Vector[String] = {
    def thueMorse(n: Int): String = (0 until n).map(i => if (Integer.bitCount(i) % 2 == 0) 'a' else 'b').mkString
    def periodDoubling(n: Int): String = { val b = new StringBuilder("a"); while (b.length < n) { b.append(b.toString.replace('b', 'x').replace('a', 'b').replace('x', 'a').take(b.length)) }; b.toString.take(n) }
    val lens = (1 to 8).flatMap(j => Vector((1 << j) - 1, 1 << j, (1 << j) + 1)).filter(n => n >= 1 && n <= maxLen).distinct
    val ws = Vector.newBuilder[String]
    for (n <- lens) {
      ws += "a" * n
      ws += (0 until n).map(i => if (i % 2 == 0) 'a' else 'b').mkString
      if (n >= 3) { val k = (n - 1) / 2; ws += "a" * k + "b" + "a" * (n - 1 - k) }
      ws += thueMorse(n)
      ws += periodDoubling(n)
      val half = thueMorse(n / 2)
      val pal = half + (if (n % 2 == 1) "a" else "") + half.reverse
      ws += pal
      if (pal.length >= 2) { val i = pal.length / 3; ws += pal.updated(i, if (pal(i) == 'a') 'b' else 'a') }
    }
    ws.result().distinct
  }

  /** words aimed at the flags interval of a stage of width W (born at tick W, half S = W/2): the machine reads
    * rd = p ++ y ++ z with |p| = W, |y| = (r+1)S, and y chosen so that its longest palindromic prefix L is
    * below, at, or above lo = rS, on a chain node or inside a gap (review finding 2) */
  def flagsIntervalWords(maxLen: Int): Vector[String] = {
    val rnd = new scala.util.Random(3)
    def rand(n: Int): String = Vector.fill(n)(if (rnd.nextBoolean()) 'a' else 'b').mkString
    def pal(n: Int): String = { val h = rand(n / 2); h + (if (n % 2 == 1) "a" else "") + h.reverse }
    val ws = Vector.newBuilder[String]
    for (w <- Vector(8, 16, 32); r <- 0 to 3) {
      val s = w / 2
      val m = (r + 1) * s
      val lo = r * s
      val ys = Vector("a" * m, (0 until m).map(i => if (i % 2 == 0) 'a' else 'b').mkString, pal(m),
        { val p = pal(m); p.updated(m - 1, if (p(m - 1) == 'a') 'b' else 'a') },
        pal(lo) + rand(m - lo), pal(math.min(lo + 1, m)) + rand(m - math.min(lo + 1, m)),
        (if (lo >= 1) { pal(lo - 1) + rand(m - lo + 1) } else { rand(m) }),
        { val q = pal(math.max(s / 2, 1)); (q * (m / q.length + 1)).take(m) }, rand(m))
      for (y <- ys) {
        val p = rand(w)
        val rd = p + y + rand(math.max(0, 2 * w - w - m + 1))   // reaches interval r + 2 of the stage
        val word = rd.reverse
        if (word.length <= maxLen) { ws += word }
      }
    }
    ws.result().distinct
  }
}
