package research

/** The anchor schedule of STRATEGY.md S3 (`OverlapPal.Sched`): the staged clock gives the births, the replay cells and
  * the answering bank of beta = 7/16 and 3/8; beta = 1/2 is the original controller. */
class AnchorSchedSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(20, "min")
  private val b716 = OverlapPal.Sched(7, 16)
  private val b38 = OverlapPal.Sched(3, 8)

  test("the stages are the binary digits of the three intervals (1 - 2 beta) P, (3 beta - 1) P, (3 beta - 1) P") {
    assertEquals(b716.stages, Vector((3, Some("saveA")), (2, None), (4, Some("birthB")), (2, None), (4, Some("saveB"))))
    assertEquals((b716.depth, b716.stageBits, b716.minW0), (4, 3, 32))
    assertEquals(b38.stages, Vector((2, Some("saveA")), (3, Some("birthB")), (3, Some("saveB"))))
    assertEquals(OverlapPal.anchors(32, 200, b716), Vector(32, 46, 64, 92, 128, 184))
    assertEquals(OverlapPal.anchors(16, 100, b38), Vector(16, 22, 32, 44, 64, 88))
    assertEquals(OverlapPal.anchors(16, 100), Vector(16, 24, 32, 48, 64, 96))
    assertEquals(OverlapPal.firstAnswer(32, b716), 46)
    val b1332 = OverlapPal.Sched(13, 32)
    assertEquals(b1332.stages, Vector((3, None), (4, Some("saveA")), (3, None), (4, None), (5, Some("birthB")), (3, None), (4, None), (5, Some("saveB"))))
    assertEquals((b1332.depth, b1332.stageBits, b1332.minW0), (5, 4, 64))
    assertEquals(OverlapPal.anchors(64, 400, b1332), Vector(64, 90, 128, 180, 256, 360))
  }

  test("beta = 1/2 builds the original controller") {
    assertEquals(OverlapPal.ctlRegsOf(OverlapPal.Half), OverlapPal.ctlRegs)
    assertEquals(OverlapPal.ctlBitsOf(32, OverlapPal.Half), OverlapPal.ctlBits(32))
  }

  private def words(sc: OverlapPal.Sched, maxLen: Int): Vector[String] =
    (OverlapProfile.randomWords(60, maxLen, 5L) ++ OverlapProfile.switchWords(maxLen, sc) ++ OverlapProfile.adversarialWords(maxLen, sc).take(120)).distinct

  for ((sc, w0) <- Vector((b716, 32), (b38, 16), (OverlapPal.Sched(13, 32), 64))) {
    test(s"beta = ${sc.num}/${sc.den} at W0 = $w0: the model has the oracle's outputs and a clean profile (births, replay cells, answering bank)") {
      val m = OverlapPal.machine(24, 24, w0, OverlapPal.GapCore, sc = sc)
      for (w <- words(sc, 7 * w0 / 2)) {
        assertEquals(Lps.run(m, w).outputs, OverlapPal.expectedOutputs(w0, w, sc), s"'${w.take(40)}' (${w.length})")
        val pr = OverlapProfile.profile(m, w, w0, sc = sc)
        assert(pr.violations.isEmpty, s"'${w.take(40)}' (${w.length}): ${pr.violations.take(3).mkString("; ")}")
      }
    }
  }

  test("beta = 7/16 through the code backend (role 1/1, W0 32): the PEG's rules agree with the interpreter on words across the anchors 32, 46, 64") {
    val ro = RolePal.Options(youngReport = false, fuseIn = true, compact = true, endAsEntry = true, endOnce = true, sched = b716)
    val m = RolePal.machine(1, 1, 32, ro)
    val opts = LpsCode.Options(fallbackBelow = Some(OverlapPal.firstAnswer(32, b716)), minBirth = 32)
    val rnd = new scala.util.Random(17)
    for (n <- Vector(33, 47, 52, 65, 70)) {
      val w = Vector.fill(n)(if (rnd.nextBoolean()) 'a' else 'b').mkString
      assertEquals(LpsCode.checkRules(m, w, opts), None, s"'$w'")
    }
  }

  test("beta = 7/16: the role machine (20/6) has the oracle's outputs and a clean strict role profile") {
    val ro = RolePal.Options(youngReport = false, fuseIn = true, compact = true, endAsEntry = true, endOnce = true, sched = b716)
    val role = RolePal.machine(20, 6, 32, ro)
    for (w <- words(b716, 112)) {
      assertEquals(Lps.run(role, w).outputs, OverlapPal.expectedOutputs(32, w, b716), s"'${w.take(40)}' (${w.length})")
      val pr = OverlapProfile.profile(role, w, 32, strict = true, layout = OverlapProfile.Roles, fusedIn = true, sc = b716)
      assert(pr.violations.isEmpty, s"'${w.take(40)}' (${w.length}): ${pr.violations.take(3).mkString("; ")}")
    }
  }

  /** the palindromes the grids and the every-length family found (src/test/resources/anchors-counterexamples.txt) */
  private def counterexamples: Vector[(String, String)] = {
    val src = scala.io.Source.fromResource("anchors-counterexamples.txt")
    try { src.getLines().filterNot(_.startsWith("#")).map { l => val parts = l.split(" \\| "); (parts(0), parts(2).trim) }.toVector } finally { src.close() }
  }
  private val a = RolePal.Options(youngReport = false, fuseIn = true, compact = true, endAsEntry = true, endOnce = true)
  /** the chosen machine of STRATEGY.md §3b: role 13/4, configuration A, beta 7/16, W0 64 */
  private def chosen: Lps.Machine = RolePal.machine(13, 4, 64, a.copy(sched = b716))

  test("every counterexample is a palindrome, fails its configuration (the POOLS machine of main included), and the chosen machine answers it") {
    val cs = counterexamples
    assertEquals(cs.size, 8)
    val fin = chosen
    val b1332 = OverlapPal.Sched(13, 32)
    for ((cfg, w) <- cs) {
      assert(LpsPal.isPal(w), cfg)
      val failing: Lps.Machine = cfg match {
        case c if c.startsWith("role 15/4 A, beta 1/2") => RolePal.machine(15, 4, 32, a)
        case c if c.startsWith("role 13/4 A, beta 7/16") => RolePal.machine(13, 4, 32, a.copy(sched = b716))
        case c if c.startsWith("role 13/3 A unfold no-endonce") => RolePal.machine(13, 3, 32, a.copy(endOnce = false, unfold = true, sched = b716))
        case c if c.startsWith("role 13/3 A unfold") => RolePal.machine(13, 3, 32, a.copy(unfold = true, sched = b716))
        case c if c.startsWith("role 12/4 A unfold, beta 7/16, W0 64") => RolePal.machine(12, 4, 64, a.copy(unfold = true, sched = b716))
        case c if c.startsWith("role 12/3 A unfold, beta 13/32") => RolePal.machine(12, 3, 64, a.copy(unfold = true, sched = b1332))
        case c if c.startsWith("model 12/12/6") => OverlapPal.machine(12, 12, 32, OverlapPal.GapCore, 6, b716)
        case c if c.startsWith("model 13/13/4, beta 3/8") => OverlapPal.machine(13, 13, 32, OverlapPal.GapCore, 4, b38)
        case c => fail(s"unknown configuration $c")
      }
      assert(!Lps.run(failing, w).outputs.last, s"$cfg should answer false on its counterexample")
      assertEquals(Lps.run(fin, w).outputs, OverlapPal.expectedOutputs(64, w, b716), s"the chosen machine on the counterexample of $cfg")
    }
  }

  test("the chosen machine answers every counterexample of POOLS (src/test/resources/pools-counterexamples.txt)") {
    val src = scala.io.Source.fromResource("pools-counterexamples.txt")
    val ws = try { src.getLines().filterNot(_.startsWith("#")).map(l => l.split(" \\| ")(2).trim).toVector } finally { src.close() }
    assert(ws.size >= 5)
    val fin = chosen
    for (w <- ws) { assertEquals(Lps.run(fin, w).outputs, OverlapPal.expectedOutputs(64, w, b716), s"'${w.take(40)}' (${w.length})") }
  }

  test("the chosen machine (role 13/4 A, beta 7/16, W0 64): the oracle's outputs and a clean strict role profile on the switch, adversarial and every-length words <= 300") {
    val m = chosen
    for (w <- OverlapProfile.switchWords(300, b716) ++ OverlapProfile.adversarialWords(300, b716).take(200) ++ OverlapProfile.everyLengthWords(300)) {
      assertEquals(Lps.run(m, w).outputs, OverlapPal.expectedOutputs(64, w, b716), s"'${w.take(40)}' (${w.length})")
      val pr = OverlapProfile.profile(m, w, 64, strict = true, layout = OverlapProfile.Roles, fusedIn = true, sc = b716)
      assert(pr.violations.isEmpty, s"'${w.take(40)}' (${w.length}): ${pr.violations.take(3).mkString("; ")}")
    }
  }
}
