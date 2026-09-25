package research

/** Task B1 (plan §4): the overlap identity, the two-family anchor schedule, the event order at a tick, the
  * pointer-clock model of the controller, the replay direction and an array-KMP model of the banks — all as
  * an integer/array oracle. Nothing here is the Lpm implementation, a deadline claim or a PEG claim.
  *
  * Lengths are in reading order: tick n reads the n-th letter of `rd` (the input reversed).
  */
class OverlapSpecSuite extends munit.FunSuite {
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")

  private object Model {
    def isPal(s: String): Boolean = s == s.reverse
    def anchors(w0: Int, lim: Int): Vector[Int] = { var w = w0; val b = Vector.newBuilder[Int]; while (w <= lim) { b += w; b += 3 * w / 2; w *= 2 }; b.result().filter(_ <= lim).sorted }
    def isPow2(h: Int): Boolean = (h & (h - 1)) == 0
    /** the next anchor after h: the first answering tick n0 of the bank with pattern length h */
    def start(as: Vector[Int], h: Int): Int = as(as.indexOf(h) + 1)
    def t0(as: Vector[Int], h: Int): Int = start(as, h) - h
    def k(as: Vector[Int], h: Int): Int = 2 * h - start(as, h)
    def live(as: Vector[Int], n: Int): Vector[Int] = as.filter(h => h <= n && n < 2 * h)
    def answering(as: Vector[Int], n: Int): Vector[Int] = live(as, n).filter(h => as.indexOf(h) + 1 < as.size && start(as, h) <= n)
    def prefixFunction(p: String): Array[Int] = {
      val pi = Array.fill(p.length)(0)
      for (i <- 1 until p.length) { var q = pi(i - 1); while (q > 0 && p(i) != p(q)) { q = pi(q - 1) }; if (p(i) == p(q)) { q += 1 }; pi(i) = q }
      pi
    }
    /** one matcher bank of Appendix C: pattern = reverse of the first h letters, replayed text from t0 */
    final class Bank(rd: String, val h: Int, as: Vector[Int]) {
      val first: Int = start(as, h)
      val pattern: String = rd.take(h).reverse
      private val pi = prefixFunction(pattern)
      private var q = 0
      var accept: Boolean = false
      var loaded: Int = 0
      val fed = new StringBuilder
      val front = new StringBuilder
      for (c <- pattern.take(2 * h - first)) { front.insert(0, c) }   // the BUILD source walk prepends: forward order
      for (c <- front.toString) { feed(c) }
      def feed(c: Char): Unit = {
        while (q > 0 && (q == h || pattern(q) != c)) { q = pi(q - 1) }
        if (q < h && pattern(q) == c) { q += 1 }
        accept = q == h
        if (accept) { q = pi(h - 1) }
        loaded += 1
        fed.append(c)
      }
    }
    /** retire, feed, birth (with replay), answer — the bank answering at tick n must say whether rd.take(n) is a palindrome */
    def checkWord(rd: String, w0: Int, as: Vector[Int]): Unit = {
      var banks = Map.empty[Int, Bank]
      for ((c, idx) <- rd.zipWithIndex) {
        val tick = idx + 1
        banks = banks.filter { case (h, _) => 2 * h > tick }
        banks.values.foreach(_.feed(c))
        if (as.contains(tick) && as.indexOf(tick) + 1 < as.size) { banks = banks + (tick -> new Bank(rd, tick, as)) }
        if (tick >= 3 * w0 / 2) {
          assertEquals(banks.size, 2, s"'$rd' tick $tick banks ${banks.keys.toVector.sorted}")
          val ready = banks.values.filter(_.first <= tick).toVector
          assertEquals(ready.size, 1, s"'$rd' tick $tick")
          val b = ready.head
          assertEquals(b.loaded, tick - (b.first - b.h))
          assertEquals(b.fed.toString, rd.substring(b.first - b.h, tick), s"'$rd' tick $tick h=${b.h}")
          assertEquals(b.accept, isPal(rd.take(tick)), s"'$rd' tick $tick h=${b.h}")
        }
      }
    }
  }
  import Model.*

  test("identity: rd is a palindrome iff its suffix of length h is the reverse of its prefix of length h, ceil(n/2) <= h <= n (1,092,266 checks)") {
    var checks = 0
    for (rd <- Exhaust.words(16, "ab")) {
      val n = rd.length
      for (h <- math.max(1, (n + 1) / 2) to n) { assertEquals(isPal(rd), rd.takeRight(h) == rd.take(h).reverse, s"'$rd' h=$h"); checks += 1 }
    }
    assertEquals(checks, 1092266)
  }

  test("negative control: h = n/2 - 1 is wrong for some word at every even n >= 4 (for odd n, h = (n-1)/2 still covers every pair)") {
    for (n <- 4 to 16 by 2) {
      val h = n / 2 - 1
      assert(Exhaust.words(n, "ab").exists(rd => rd.length == n && isPal(rd) != (rd.takeRight(h) == rd.take(h).reverse)), s"n=$n")
    }
    for (n <- 3 to 15 by 2; rd <- Exhaust.words(n, "ab") if rd.length == n) {
      val h = (n - 1) / 2
      assertEquals(isPal(rd), rd.takeRight(h) == rd.take(h).reverse, s"'$rd' h=$h")
    }
  }

  test("schedule: for W0 in {2,4,8,64} and n up to 4096, two live banks and exactly one answering, with n/2 <= h <= n") {
    for (w0 <- Vector(2, 4, 8, 64)) {
      val as = anchors(w0, 3 * 4096)
      for (n <- 3 * w0 / 2 to 4096) {
        val lv = live(as, n)
        assertEquals(lv.size, 2, s"W0=$w0 n=$n live=$lv")
        assertEquals(lv.count(isPow2), 1, s"W0=$w0 n=$n live=$lv")
        val an = answering(as, n)
        assertEquals(an.size, 1, s"W0=$w0 n=$n answering=$an live=$lv")
        val h = an.head
        assert((n + 1) / 2 <= h && h <= n, s"W0=$w0 n=$n h=$h")
        if (isPow2(h)) { assert(3 * h / 2 <= n && n < 2 * h) } else { assert(4 * h / 3 <= n && n < 2 * h) }
      }
    }
  }

  test("event order: retire, then birth into the empty slot, then answer; the answering bank is never the one born at n") {
    val as = anchors(2, 3 * 4096)
    var slots = Map.empty[Int, Int]   // bank slot (0 = A, 1 = B) -> h
    for (n <- 1 to 4096) {
      val retired = slots.filter { case (_, h) => 2 * h == n }
      slots = slots.filter { case (_, h) => 2 * h > n }
      if (as.contains(n) && as.indexOf(n) + 1 < as.size) {
        val slot = if (isPow2(n)) { 0 } else { 1 }
        assert(!slots.contains(slot), s"n=$n slot $slot still holds ${slots.get(slot)}")
        for ((s, _) <- retired) { assertEquals(s, slot, s"n=$n: the retiring bank and the born bank differ") }
        slots = slots + (slot -> n)
      }
      if (n >= 3) {
        val an = answering(as, n)
        assertEquals(an.size, 1)
        assert(an.head != n, s"n=$n answers with the bank born now")
      }
    }
  }

  test("pointer-clock model: dyadic births, the half clock fires at 3W/2, clk == 0 selects family A, rc depths are the replay lengths") {
    for (w0 <- Vector(2, 4, 8, 64)) {
      val as = anchors(w0, 3 * 4096)
      var pw = 0; var nb = 0; var clk = 0
      val births = Vector.newBuilder[Int]
      for (n <- 1 to 4096) {
        val dy = n > 1 && nb - 1 == 0
        val bA = dy && n >= w0
        val bB = clk > 0 && clk - 1 == 0
        val pwOld = pw
        if (n == 1 || dy) { pw = n; nb = n } else { nb -= 1 }
        val clkNew = if (bA) { pwOld } else { math.max(clk - 1, 0) }
        if (bA) { births += n; assertEquals(pwOld, k(as, n), s"W0=$w0 A birth $n: rc depth") }
        if (bB) { births += n; assertEquals(pw, k(as, n), s"W0=$w0 B birth $n: rc depth") }
        clk = clkNew
        if (n >= 3 * w0 / 2) {
          val ansA = clk == 0
          assertEquals(ansA, isPow2(answering(as, n).head), s"W0=$w0 n=$n")
        }
      }
      assertEquals(births.result(), as.filter(h => h >= w0 && h <= 4096), s"W0=$w0 births")
    }
  }

  test("replay direction: prepending the first k pattern letters gives rd[t0:h] in forward order; k = W/2 for A and W for B") {
    val as = anchors(2, 3 * 8192)
    val rd = (0 until 8200).map(i => if (Integer.bitCount(i) % 2 == 0) 'a' else 'b').mkString
    for (h <- as if h <= 8192 && as.indexOf(h) + 1 < as.size) {
      val kk = k(as, h)
      val pattern = rd.take(h).reverse
      val front = new StringBuilder
      for (c <- pattern.take(kk)) { front.insert(0, c) }
      assertEquals(front.toString, rd.substring(t0(as, h), h), s"h=$h")
      if (isPow2(h)) { assertEquals(kk, h / 2) } else { assertEquals(kk, 2 * h / 3) }
    }
  }

  test("array-KMP banks with replay answer every prefix correctly: all words <= 16, structured words <= 2048") {
    val as = anchors(2, 3 * 4096)
    for (rd <- Exhaust.words(16, "ab") if rd.nonEmpty) { checkWord(rd, 2, as) }
    def tm(n: Int): String = (0 until n).map(i => if (Integer.bitCount(i) % 2 == 0) 'a' else 'b').mkString
    val rnd = new scala.util.Random(5)
    val words = Vector.newBuilder[String]
    for (n <- Vector(100, 257, 1000, 2048)) {
      words += "a" * n
      words += ("ab" * n).take(n)
      words += tm(n)
      val h = rnd.nextString(0) + Vector.fill(n / 2)(if (rnd.nextBoolean()) 'a' else 'b').mkString
      val pal = h + h.reverse
      words += pal
      words += pal.updated(n / 3, if (pal(n / 3) == 'a') 'b' else 'a')
    }
    for (rd <- words.result()) { checkWord(rd, 2, as) }
    for (w0 <- Vector(4, 64); rd <- Vector(tm(300) + tm(300).reverse, "a" * 700)) { checkWord(rd, w0, anchors(w0, 3 * 4096)) }
  }
}
