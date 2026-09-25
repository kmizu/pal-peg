package research

/** Task 1 (matcher): the executable specification of the binary KMP with persistent gap sequences (plan §4.2 and
  * Appendix A) with exact vectors — integer entry ids, copied sequences. It is NOT a pointer machine, a PEG or a
  * size measurement. Beyond the appendix, the range lemma: every sequence the matcher forms is a contiguous range
  * of entry indices and `front` is always the suffix range `[cur+1 .. m]`, so the only persistent operation a
  * pointer implementation needs is "the entry after E_a". */
class BinaryGapKmpSpecSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")

  final case class Row(i: Int, f: Int, z: Int, g: Vector[Int], h: Vector[Int])
  final case class Build(h: Vector[Vector[Int]], z: Vector[Int], f: Int, resetFront: Vector[Int], rows: Vector[Row])

  def build(p: String): Build = {
    var h = Vector.empty[Vector[Int]]
    var z = Vector.empty[Int]
    var f = 0
    var g = Vector.empty[Int]
    val rows = Vector.newBuilder[Row]
    for (i <- p.indices) {
      val (hi, zi, nf, ng) = if (i == 0) { (Vector.empty[Int], -1, 0, Vector.empty[Int]) } else {
        val c = h(f) ++ Vector(f) ++ g
        assertEquals(c, (z(f) + 1 until i).toVector, s"C is not the range: '$p' at $i")
        val same = p(i) == p(f)
        val hi = if (same) { c } else { g }
        val zi = if (same) { z(f) } else { f }
        val d = (if (same) { g } else { c }) :+ i
        assertEquals(d, (d.head to i).toVector, s"D is not a range: '$p' at $i")
        (hi, zi, d.head, d.tail)
      }
      assertEquals(g, (f + 1 until i).toVector, s"g_$i: '$p'")
      assertEquals(hi, (zi + 1 until i).toVector, s"h_$i: '$p'")
      rows += Row(i, f, zi, g, hi)
      h = h :+ hi
      z = z :+ zi
      f = nf
      g = ng
    }
    Build(h, z, f, g :+ p.length, rows.result())
  }

  /** the report after every text letter: "the text so far ends with the pattern" */
  def outputs(p: String, t: String): Vector[Boolean] = {
    val b = build(p)
    val m = p.length
    var cur = 0
    var front = (1 to m).toVector
    val out = Vector.newBuilder[Boolean]
    for (ch <- t) {
      val d = if (ch == p(cur)) { front } else { b.h(cur) ++ Vector(cur) ++ front }
      cur = d.head
      front = d.tail
      assertEquals(front, (cur + 1 to m).toVector, s"front is not the suffix range: '$p' on '$t'")
      val acc = cur == m
      out += acc
      if (acc) { cur = b.f; front = b.resetFront }
    }
    out.result()
  }

  test("table/gap invariants and the range lemma on the 8,190 nonempty binary patterns of length <= 12") {
    var n = 0
    for (p <- Exhaust.words(12, "ab") if p.nonEmpty) {
      val b = build(p)
      val m = p.length
      val oracle = (0 until m).filter(j => p.take(j) == p.drop(m - j)).max
      assertEquals(b.f, oracle, s"f_m of '$p'")
      n += 1
    }
    assertEquals(n, 8190)
  }

  test("online outputs on the 129,794 pattern/text pairs (pattern <= 7, text <= 8), every prefix") {
    var pairs = 0
    for (p <- Exhaust.words(7, "ab") if p.nonEmpty; t <- Exhaust.words(8, "ab")) {
      val expected = (1 to t.length).map(k => t.take(k).endsWith(p)).toVector
      assertEquals(outputs(p, t), expected, s"'$p' on '$t'")
      pairs += 1
    }
    assertEquals(pairs, 129794)
  }

  test("the difficult pattern aaab: the rows of the plan and the reset state after the last letter") {
    val b = build("aaab")
    assertEquals(b.rows.map(r => (r.f, r.z, r.g, r.h)),
      Vector((0, -1, Vector(), Vector()), (0, -1, Vector(), Vector(0)), (1, -1, Vector(), Vector(0, 1)), (2, 2, Vector(), Vector())))
    assertEquals(b.f, 0)
    assertEquals(b.resetFront, Vector(1, 2, 3, 4))
    val b2 = build("ababa")
    assertEquals(b2.f, 3)
    assertEquals(b2.resetFront, Vector(4, 5))
  }

  test("overlapping full matches reset to the border: 'aa' in 'aaaa', 'aba' in 'ababa', 'aaab' in 'aaaaab aaab'") {
    assertEquals(outputs("aa", "aaaa"), Vector(false, true, true, true))
    assertEquals(outputs("aba", "ababa"), Vector(false, false, true, false, true))
    assertEquals(outputs("aaab", "aaaaabaaab"), Vector(false, false, false, false, false, true, false, false, false, true))
  }
}
