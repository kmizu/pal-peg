package research

/** STRATEGY.md §3p: the pointer-level model of the table-free matcher against the reference (`TwoWay`). */
class TwoWayPtrSuite extends munit.FunSuite {
  import TwoWayPtr.*

  /** the pattern as input cells: P[0] is the newest (the birth cell), f1 walks to P[1], ... */
  private def cells(p: String): Cell = {
    var prev: Cell = null
    for (x <- (p.length - 1) to 0 by -1) { prev = new Cell(if (p(x) == 'a') { 0 } else { 1 }, prev, null, null, x) }
    prev
  }
  private def young(p: String): Young = { val y = new Young(cells(p), p.length); y.buildN(); y.buildNP(); y }
  /** the reference critical start of every prefix length K = 1 .. n */
  private def refCrit(p: String): Vector[Int] = {
    val c = new TwoWay.Cost
    val lt = TwoWay.prefixMaxSuffixes(p, rev = false, c); val gt = TwoWay.prefixMaxSuffixes(p, rev = true, c)
    (0 to p.length).map(k => if (k == 0) { 0 } else { math.max(lt(k)._1, gt(k)._1) }).toVector
  }
  private def npAt(y: Young, x: Int): Cell = { var c = y.nph; for (_ <- 0 until x) { c = c.f2 }; c }

  test("the passes give the critical start of the whole pattern, of every prefix, and the crossing") {
    val rnd = new scala.util.Random(3)
    for (trial <- 0 until 3000) {
      val n = 2 + rnd.nextInt(28)
      val bias = Seq(0.5, 0.8, 0.95)(trial % 3)
      val p = Iterator.fill(n)(if (rnd.nextDouble() < bias) { 'a' } else { 'b' }).mkString
      val y = young(p)
      val c = refCrit(p)
      val (w, _, cross) = y.critical(null)
      assertEquals(w.start.pos, c(n), s"whole p=$p")
      val small = (v: Int) => 2 * v <= n
      if (small(c(n))) { assertEquals(cross, null, s"no crossing p=$p") }
      else {
        val ms = (1 until n).filter(k => small(c(k))).last
        assertEquals(cross.pos, c(ms + 1), s"crossing p=$p")
      }
      for (l <- 1 until n) {
        val (wl, _, _) = y.critical(npAt(y, l))
        assertEquals(wl.start.pos, c(l), s"prefix $l p=$p")
      }
    }
  }
}
