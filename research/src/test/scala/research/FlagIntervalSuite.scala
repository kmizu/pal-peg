package research

/** The truncated flags iterator (Task 3), as an integer model against the palindrome oracle: for every
  * binary y with |y| = (r+1)S <= 12 and r < 4, the flags of the lengths lo = rS .. m agree. The model is
  * the one of research/probes/truncated_flags_probe.py. */
class FlagIntervalSuite extends munit.FunSuite {
  private def check(y: String, r: Int, s: Int): Int = {
    val m = y.length
    val lo = r * s
    val pi = Array.fill(m)(0)
    for (i <- 1 until m) {
      var k = pi(i - 1)
      while (k > 0 && y(k) != y(i)) { k = pi(k - 1) }
      if (y(k) == y(i)) { k += 1 }
      pi(i) = k
    }
    var k = 0
    var rem = s
    var overflow = false
    var distance = 0
    def lose(n: Int): Unit = for (_ <- 0 until n) { distance += 1; if (rem > 0) { rem -= 1 } else { overflow = true } }
    for (c <- y.reverse) {
      while (k > 0 && (k == m || y(k) != c)) { val nxt = pi(k - 1); lose(k - nxt); k = nxt }
      if (k < m && y(k) == c) { k += 1 } else { lose(1) }
    }
    val L = k
    assertEquals(distance, m - L)
    assertEquals(rem, math.max(L - lo, 0))
    assertEquals(overflow, L < lo)
    var g = List.empty[Int]
    var cnt = 0
    var pf = false
    var units = 0
    if (rem == 0) { pf = !overflow }
    else {
      var go = true
      while (go) {
        val d = k - pi(k - 1)
        var gw = d
        var built = 0
        while (rem > 0 && gw > 0) { rem -= 1; gw -= 1; built += 1; units += 1 }
        if (rem == 0) {
          if (gw == 0) { g = d :: g; cnt = 0; pf = true } else { cnt = built; pf = false }
          go = false
        } else { assert(gw == 0); g = d :: g; k = pi(k - 1) }
      }
    }
    assert(units == math.max(L - lo, 0) && units <= s)
    for (length <- lo to m) {
      val want = y.take(length) == y.take(length).reverse
      assertEquals(pf, want, s"y=$y r=$r S=$s length=$length G=$g cnt=$cnt")
      if (pf) { g match { case d :: t => g = t; cnt = d - 1; pf = cnt == 0; case Nil => cnt = 0; pf = false } }
      else if (cnt > 0) { cnt -= 1; pf = cnt == 0 }
    }
    1
  }

  test("truncated flags agree with the palindrome oracle on all binary y with |y| <= 12, r < 4") {
    var count = 0
    for (m <- 1 to 12; y <- Exhaust.words(m, "ab") if y.length == m; r <- 0 to 3 if m % (r + 1) == 0) { count += check(y, r, m / (r + 1)) }
    assert(count > 10000, s"only $count cases")
  }
}
