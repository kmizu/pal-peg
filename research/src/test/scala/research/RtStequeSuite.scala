package research

/** STRATEGY.md §3l: the real-time catenable steque against a list model, on random persistent histories (every operation
  * applies to any version kept so far), with its invariants and a bound on the cells one operation stores. */
class RtStequeSuite extends munit.FunSuite {
  // the random-history test takes about 22 s alone and passed the 30 s default only when the machine was idle
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(2, "min")
  import RtSteque.*

  /** applies `n` random operations; returns the largest number of cells one operation stored */
  private def history(seed: Long, n: Int, pool: Int, verify: Boolean = true): Int = {
    given m: Meter = new Meter
    val rnd = new scala.util.Random(seed)
    val vs = scala.collection.mutable.ArrayBuffer[(Node, List[Any])]((null, Nil))
    var next = 0
    var maxCells = 0
    for (step <- 0 until n) {
      val (s, l) = vs(rnd.nextInt(vs.length))
      m.reset()
      val op = rnd.nextInt(10)
      val r: (Node, List[Any]) =
        if (op < 3) { next += 1; (push(s, next), if (verify) { next :: l } else { Nil }) }
        else if (op < 6) { next += 1; (inject(s, next), if (verify) { l :+ next } else { Nil }) }
        else if (op < 8 && s != null) { val (x, s1) = pop(s); if (verify) { assertEquals(x, l.head, s"step $step") }; (s1, if (verify) { l.tail } else { Nil }) }
        else { val (s2, l2) = vs(rnd.nextInt(vs.length)); (concat(s, s2), if (verify) { l ++ l2 } else { Nil }) }
      maxCells = maxCells max m.total
      if (verify) {
        assertEquals(toList(r._1), r._2, s"step $step op $op")
        check(r._1).foreach(e => fail(s"step $step op $op: $e"))
      }
      if (vs.length < pool) { vs += r } else { vs(rnd.nextInt(pool)) = r }
    }
    maxCells
  }

  test("random persistent histories agree with lists and keep the invariants") {
    for (seed <- 1L to 20L) { history(seed, 2000, 40) }
  }

  test("the cells one operation stores do not grow with the size") {
    val small = (1L to 5L).map(s => history(s, 500, 8, verify = false)).max
    val large = (1L to 5L).map(s => history(s + 100, 50000, 8, verify = false)).max
    assert(large <= small + 4, s"small $small, large $large")
  }

  test("a long queue: inject then pop, one element at a time") {
    given m: Meter = new Meter
    var s: Node = null
    var worst = 0
    for (i <- 0 until 5000) { m.reset(); s = inject(s, i); worst = worst max m.total }
    for (i <- 0 until 5000) { m.reset(); val (x, s1) = pop(s); assertEquals(x, i); s = s1; worst = worst max m.total }
    assert(s == null)
    assert(worst <= 20, s"worst $worst")
  }
}
