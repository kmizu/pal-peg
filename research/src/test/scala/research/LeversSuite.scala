package research

/** The levers of STRATEGY.md §3e: options that change the compiled grammar only when they are on, keep the machine's
  * behaviour, and the adopted young-11 machine (lgap + the unfold-then-popL merge, beta 13/32). */
class LeversSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(30, "min")
  private val b716 = OverlapPal.Sched(7, 16)
  private val a = RolePal.Options(youngReport = false, fuseIn = true, compact = true, endAsEntry = true, endOnce = true, sched = b716,
    unfold = true, mergeTmp = true)
  private def sha(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString

  test("without the new options the §3d compact grammar is unchanged (a literal False guard is never compiled in)") {
    val m = RolePal.machine(12, 4, 128, a)
    val opts = LpsCode.Options(fallbackBelow = Some(OverlapPal.firstAnswer(128, b716)), minBirth = 128, codeByStar = true, mirrorFallback = true)
    val g = PegOptimize.compact(LpsCode.compileMeta(m, opts).grammar).grammar
    assertEquals(sha(g.render), "dad88e0c17e66a9901fcc94326d2c085c17af2948cb9d94917d1df4e5de300e1")
  }

  private def words(maxLen: Int, sc: OverlapPal.Sched = b716): Vector[String] =
    OverlapProfile.switchWords(maxLen, sc) ++ OverlapProfile.adversarialWords(maxLen, sc).take(150) ++ OverlapProfile.everyLengthWords(maxLen)

  private def clean(m: Lps.Machine, w0: Int, ws: Vector[String], label: String, sc: OverlapPal.Sched = b716): Unit =
    for (w <- ws) {
      assertEquals(Lps.run(m, w).outputs, OverlapPal.expectedOutputs(w0, w, sc), s"$label '${w.take(40)}' (${w.length})")
      val pr = OverlapProfile.profile(m, w, w0, strict = true, layout = OverlapProfile.Roles, fusedIn = true, sc = sc)
      assert(pr.violations.isEmpty, s"$label '${w.take(40)}' (${w.length}): ${pr.violations.take(3).mkString("; ")}")
    }

  test("lgap, merge2 and merge3 keep the behaviour: the oracle's outputs and a clean strict role profile (20/6, W0 32, words <= 200)") {
    val ws = words(200)
    for ((name, o) <- Vector("lgap" -> a.copy(lgap = true), "lgap merge2" -> a.copy(lgap = true, merge2 = true),
      "lgap merge2 merge3" -> a.copy(lgap = true, merge2 = true, merge3 = true))) { clean(RolePal.machine(20, 6, 32, o), 32, ws, name) }
  }

  test("pairs (two steps of a role in one slot) has the same outputs as the unpaired machine tick by tick") {
    val m = RolePal.machine(12, 4, 32, a)
    val mp = RolePal.machine(12, 4, 32, a.copy(pairs = true))
    for (w <- words(160)) { assertEquals(Lps.run(mp, w).outputs, Lps.run(m, w).outputs, s"'${w.take(40)}' (${w.length})") }
  }

  test("the §3e machine (role 11/4, lgap, unfold-then-popL, beta 13/32, W0 128): the oracle's outputs and a clean strict role profile on words <= 300") {
    val b1332 = OverlapPal.Sched(13, 32)
    val m = RolePal.machine(11, 4, 128, a.copy(sched = b1332, lgap = true, merge2 = true, m2off = Set("G", "R")))
    clean(m, 128, words(300, b1332), "11/4 lgap U 13/32", b1332)
  }

  test("the §3e compact grammar is out/anchors-role-y11-o4-w128.peg.gz") {
    val b1332 = OverlapPal.Sched(13, 32)
    val m = RolePal.machine(11, 4, 128, a.copy(sched = b1332, lgap = true, merge2 = true, m2off = Set("G", "R")))
    val opts = LpsCode.Options(fallbackBelow = Some(OverlapPal.firstAnswer(128, b1332)), minBirth = 128, codeByStar = true, mirrorFallback = true)
    val g = PegOptimize.compact(LpsCode.compileMeta(m, opts).grammar).grammar
    assertEquals(sha(g.render), "29cfd5249894fd9c26a3b76d08064bbd54114adfb9f12086a361d1c6fe992416")
  }

  test("the §3e machine answers every counterexample of both resource files") {
    val b1332 = OverlapPal.Sched(13, 32)
    val m = RolePal.machine(11, 4, 128, a.copy(sched = b1332, lgap = true, merge2 = true, m2off = Set("G", "R")))
    for (res <- Vector("anchors-counterexamples.txt", "pools-counterexamples.txt")) {
      val src = scala.io.Source.fromResource(res)
      val ws = try { src.getLines().filterNot(_.startsWith("#")).map(l => l.split(" \\| ")(2).trim).toVector } finally { src.close() }
      for (w <- ws) { assertEquals(Lps.run(m, w).outputs, OverlapPal.expectedOutputs(128, w, b1332), s"$res '${w.take(40)}' (${w.length})") }
    }
  }
}

