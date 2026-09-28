package research

/** STRATEGY.md §3f: the options of the code backend that shrink one read (`sound`: no re-test of the target's tag;
  * `pointsTo`: dispatch over the points-to sites) keep the language and every register's extent and code, and the
  * points-to sets are sound on the §3e machine, where they keep the old role's sites out of every young register. */
class ReadBranchSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(30, "min")
  private val finalA = RolePal.Options(youngReport = false, fuseIn = true, compact = true, endAsEntry = true, endOnce = true)
  private val b1332 = OverlapPal.Sched(13, 32)
  /** the configuration of the §3e grammar */
  private val levers = finalA.copy(unfold = true, mergeTmp = true, lgap = true, merge2 = true, m2off = Set("G", "R"), sched = b1332)
  private val variants = Vector("sound" -> LpsCode.Options(sound = true), "pts" -> LpsCode.Options(pointsTo = true),
    "sound pts" -> LpsCode.Options(sound = true, pointsTo = true))

  test("sound and pointsTo at minAnchor 8 (A): PEG = interpreter on all words <= 13 (1/1) and <= 12 (2/2); extents and codes match the cells on words <= 7") {
    for ((ky, ko, maxLen) <- Vector((1, 1, 13), (2, 2, 12)); (name, v) <- variants) {
      val m = RolePal.machine(ky, ko, 8, finalA)
      val opts = v.copy(fallbackBelow = Some(OverlapPal.firstAnswer(8)), minBirth = 8)
      assertEquals(LpsCode.checkSim(m, maxLen, opts), None, s"$name $ky/$ko")
      for (w <- Exhaust.words(7, "ab") if w.nonEmpty) { assertEquals(LpsCode.checkRules(m, w, opts), None, s"$name $ky/$ko '$w'") }
    }
  }

  private def words(maxLen: Int, sc: OverlapPal.Sched): Vector[String] =
    OverlapProfile.switchWords(maxLen, sc) ++ OverlapProfile.adversarialWords(maxLen, sc).take(20) ++ OverlapProfile.everyLengthWords(maxLen)

  test("sound and pointsTo on the §3e configuration at 2/2, minAnchor 64 (the staged clock of beta 13/32 needs it): the PEG answers as the interpreter (the oracle below the first answer); extents and codes match the cells") {
    val m = RolePal.machine(2, 2, 64, levers)
    val first = OverlapPal.firstAnswer(64, b1332)
    val ws = words(first + 40, b1332)
    val sample = ws.filter(w => w.length >= first && w.length % 11 == 0).take(12)
    for ((name, v) <- variants) {
      val opts = v.copy(fallbackBelow = Some(first), minBirth = 64)
      val g = LpsCode.compile(m, opts)
      for (w <- ws) {
        val want = if (w.length < first) { LpsPal.isPal(w) } else { Lps.run(m, w).outputs.last }
        assertEquals(g.accepts(w), want, s"$name '${w.take(40)}' (${w.length})")
      }
      for (w <- sample) { assertEquals(LpsCode.checkRules(m, w, opts), None, s"$name '${w.take(40)}' (${w.length})") }
    }
  }

  test("every rule the code backend references is defined with sound and pointsTo, for the size options of the role machine") {
    val variantsRole = Vector("A" -> finalA, "unfold tmp" -> finalA.copy(unfold = true, mergeTmp = true), "levers" -> levers)
    for ((oname, o) <- variantsRole; (name, v) <- variants) {
      val w0 = if (o.sched == b1332) { 64 } else { 32 }
      val g = LpsCode.compileMeta(RolePal.machine(2, 2, w0, o), v.copy(fallbackBelow = Some(OverlapPal.firstAnswer(w0, o.sched)), minBirth = w0,
        mirrorFallback = true)).grammar
      val defined = g.rules.map(_._1).toSet
      val undefined = g.rules.flatMap { case (_, e) => PegBudgetInline.occurrences(e).keys }.toSet -- defined
      assert(undefined.isEmpty, s"$oname $name: undefined ${undefined.toVector.sorted.take(5)}")
    }
  }

  private def sha(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString

  test("the §3f compact grammar (the §3e machine with sound and pts) is out/anchors-role-y11-o4-w128-pts.peg.gz") {
    val m = RolePal.machine(11, 4, 128, levers)
    val opts = LpsCode.Options(fallbackBelow = Some(OverlapPal.firstAnswer(128, b1332)), minBirth = 128, codeByStar = true, mirrorFallback = true,
      sound = true, pointsTo = true)
    val g = PegOptimize.compact(LpsCode.compileMeta(m, opts).grammar).grammar
    assertEquals(sha(g.render), "4f54dd50fbb755fb3881c9dc4c8a77859f4c9bf0d32c19b3fc886b3ddc170eb7")
  }

  test("the points-to sets of the §3e machine (11/4, minAnchor 128) are sound on words <= 300 and keep the old role's sites out of the young registers") {
    val m = RolePal.machine(11, 4, 128, levers)
    val sets = PointsTo(m)
    val oldLayers = (1 to m.budget).filter(t => m.schedule(t - 1) == "OU").toSet
    for ((r, _) <- m.regs if r.startsWith("y_")) {
      val old = sets.reg(r).filter(s => oldLayers(s._1))
      assert(old.isEmpty, s"$r may hold the old role's sites $old")
    }
    for (w <- words(300, b1332)) {
      val v = PointsTo.violations(m, sets, w)
      assert(v.isEmpty, s"'${w.take(40)}' (${w.length}): ${v.take(3).mkString("; ")}")
    }
  }
}
