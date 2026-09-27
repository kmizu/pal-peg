package research

/** STRATEGY.md §3g: young slots that may not store a new cell (`youngPattern`, slot `YN`). The non-allocating step has no
  * allocation site, stalls exactly where the full step would store a new cell and otherwise acts as the full step, and the
  * code backend compiles it to the interpreter's language. */
class CapSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(30, "min")
  private val finalA = RolePal.Options(youngReport = false, fuseIn = true, compact = true, endAsEntry = true, endOnce = true)
  private val b1332 = OverlapPal.Sched(13, 32)
  /** the configuration of the §3e grammar */
  private val levers = finalA.copy(unfold = true, mergeTmp = true, lgap = true, merge2 = true, m2off = Set("G", "R"), sched = b1332)

  test("the non-allocating young step has no allocation site, and the N layers of a pattern none either") {
    val m = RolePal.machine(11, 4, 128, levers.copy(youngPattern = "ANANANANAN"))
    assert(Lpm.sitesOf(m.slots("YN")).isEmpty)
    for (t <- 1 to m.budget if m.schedule(t - 1) == "YN") { assert(m.sitesAt(t).isEmpty, s"layer $t") }
    assertEquals(m.schedule.count(_ == "YN"), 5)
    assertEquals(m.schedule.count(_ == "YU"), 5)
    val mo = RolePal.machine(11, 4, 128, levers.copy(oldPattern = "NAN"))
    assert(Lpm.sitesOf(mo.slots("ON")).isEmpty)
    assertEquals(mo.schedule.count(_ == "ON"), 2)
  }

  /** replays `w` on `m`; at every N layer also applies the full young step to the same state and checks the stall rule */
  private def stallRule(m: Lps.Machine, w: String): Option[String] = {
    val regNames = m.regs.map(_._1)
    var st = Lpm.State(Map.empty, m.initTrue)
    var prevSep: Lpm.RCell = null
    var bad: Option[String] = None
    for (i <- (w.length - 1) to 0 by -1 if bad.isEmpty) {
      val tick = w.length - i
      val in = new Lpm.RCell(m.alphabet.indexOf(w.charAt(i)), prevSep, null, null, null, -1, -1, tick)
      val sep = new Lpm.RCell(m.alphabet.length, in, null, null, null, -2, -1, tick)
      for (t <- 1 to m.budget if bad.isEmpty) {
        val pre = st
        st = Lpm.unitStep(m.slotAt(t), regNames, m.bits, st, in, sep, t == 1, t, tick)
        if (m.schedule(t - 1) == "YN") {
          val full = Lpm.unitStep(m.slots("YU"), regNames, m.bits, pre, in, sep, t == 1, t, tick)
          val fresh = full.regs.values.exists(c => c != null && c.tick == tick && c.layer == t)
          val same = (s: Lpm.State) => regNames.forall(r => s.regs.getOrElse(r, null) eq st.regs.getOrElse(r, null)) && s.bits == st.bits
          if (fresh && !same(pre)) { bad = Some(s"tick $tick layer $t: the full step stores a new cell, but the N step did not stall") }
          else if (!fresh && !same(full)) { bad = Some(s"tick $tick layer $t: the full step stores no new cell, but the N step differs from it") }
        }
      }
      prevSep = sep
    }
    bad
  }

  private def words(maxLen: Int, sc: OverlapPal.Sched): Vector[String] =
    OverlapProfile.switchWords(maxLen, sc) ++ OverlapProfile.adversarialWords(maxLen, sc).take(40) ++ OverlapProfile.everyLengthWords(maxLen)

  test("an N layer stalls exactly where the full young step would store a new cell, and otherwise acts as the full step (words <= 260)") {
    val m = RolePal.machine(11, 4, 128, levers.copy(youngPattern = "ANANANANAN"))
    for (w <- words(260, b1332)) { assertEquals(stallRule(m, w), None, s"'${w.take(40)}' (${w.length})") }
  }

  test("the code backend compiles N slots to the interpreter's language (young: 1 or 2 slots after IN; old: 1; minAnchor 8; with and without sound pts)") {
    val variants = Vector("plain" -> LpsCode.Options(), "sound pts" -> LpsCode.Options(sound = true, pointsTo = true))
    for ((ky, pat, opat, maxLen) <- Vector((2, "N", "", 12), (3, "AN", "", 12), (3, "NA", "", 12), (2, "", "N", 12)); (name, v) <- variants) {
      val m = RolePal.machine(ky, 2, 8, finalA.copy(unfold = true, mergeTmp = true, youngPattern = pat, oldPattern = opat))
      val opts = v.copy(fallbackBelow = Some(OverlapPal.firstAnswer(8)), minBirth = 8)
      assertEquals(LpsCode.checkSim(m, maxLen, opts), None, s"$pat/$opat $name")
      for (w <- Exhaust.words(6, "ab") if w.nonEmpty) { assertEquals(LpsCode.checkRules(m, w, opts), None, s"$pat/$opat $name '$w'") }
    }
  }
}
