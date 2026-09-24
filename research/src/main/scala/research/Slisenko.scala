package research

import scala.collection.mutable

/** Reference implementation of Slisenko's recognizer of initial palindromes (Zapiski LOMI 68, 1977,
  * §§3–4), computed offline on a whole word. It is the algorithm whose linear speed-up gives a
  * real-time multitape machine for palindromes.
  *
  * Points: the word `W(1..n)` is spread out as `ω(2k−1) = W(k)`, `ω(2k) = separator`; the points
  * `1..2n` are the candidate centres. `R(x)` is the symmetry radius of point `x`, `S(x) = [x−R(x), x+R(x)]`.
  * A chained lattice is an arithmetic progression of points in which neighbours lie in each other's
  * symmetry regions; a chain is a maximal one. The function `f` walks through points and chains;
  * the centres of initial palindromes are exactly the points satisfying (A) or (B).
  */
object Slisenko {
  val Separator: Char = '|'

  final case class Lattice(first: Int, step: Int, count: Int) {
    require(count >= 2 && step >= 1)
    def points: Vector[Int] = Vector.tabulate(count)(i => first + i * step)
    def begin: Int = first
    def end: Int = first + (count - 1) * step
    def contains(x: Int): Boolean = x >= begin && x <= end && (x - first) % step == 0
  }

  /** Either a point or a lattice. */
  type Value = Either[Int, Lattice]

  final class Word(val w: String) {
    val n: Int = w.length
    val last: Int = 2 * n
    /** ω(1..2n); index 0 unused. */
    val omega: Array[Char] = Array.tabulate(2 * n + 1) { i =>
      if (i == 0) { '\u0000' } else if (i % 2 == 0) { Separator } else { w.charAt((i - 1) / 2) }
    }
    private val radius = mutable.HashMap.empty[Int, Int]

    def valid(x: Int): Boolean = x >= 1 && x <= last

    def R(x: Int): Int = radius.getOrElseUpdate(x, {
      var k = 0
      while (x - k - 1 >= 1 && x + k + 1 <= last && omega(x - k - 1) == omega(x + k + 1)) { k += 1 }
      k
    })
    def left(x: Int): Int = x - R(x)
    def right(x: Int): Int = x + R(x)
    def inRegion(y: Int, x: Int): Boolean = left(x) <= y && y <= right(x)   // y ∈ S(x)

    def left(l: Lattice): Int = left(l.begin)
    def right(l: Lattice): Int = right(l.end)
    /** L⁰ when it is a point (the region has odd length), else None. */
    def centre(l: Lattice): Option[Int] = {
      val s = left(l) + right(l)
      if (s % 2 == 0) { Some(s / 2) } else { None }
    }
    def leftOf(v: Value): Int = v.fold(left, left)
    def rightOf(v: Value): Int = v.fold(right, right)

    /** Neighbouring lattice nodes must lie in each other's regions. */
    private def linked(a: Int, b: Int): Boolean = inRegion(a, b) && inRegion(b, a)

    /** The maximal chained lattice of step `h` through the neighbours `x < y = x + h`. */
    def maximalLattice(x: Int, h: Int): Lattice = {
      var first = x
      while (first - h >= 1 && linked(first - h, first)) { first -= h }
      var end = x + h
      while (end + h <= last && linked(end, end + h)) { end += h }
      Lattice(first, h, (end - first) / h + 1)
    }

    def extendable(l: Lattice): Boolean =
      (l.begin - l.step >= 1 && linked(l.begin - l.step, l.begin)) || (l.end + l.step <= last && linked(l.end, l.end + l.step))

    def symmetricLattice(l: Lattice): Boolean =
      centre(l).exists(l.contains) && right(l) < last && !extendable(l)

    def symmetricChain(l: Lattice): Boolean = centre(l).exists(l.contains) && right(l) < last
  }

  /** The values f(1), f(2), ... until the algorithm waits for more input or leaves the word. */
  def trace(w: String): Vector[Value] = {
    val word = new Word(w)
    if (word.n == 0) { return Vector.empty }
    val out = mutable.ArrayBuffer[Value](Left(1))
    var go = true
    while (go) {
      out.last match {
        case Left(x) =>
          if (!word.valid(x)) { go = false } else {
            // K: the latest symmetric lattice centred at x (Λ if none)
            val k = out.indices.reverse.collectFirst {
              case i if out(i).isRight && word.symmetricLattice(out(i).toOption.get) && word.centre(out(i).toOption.get).contains(x) =>
                out(i).toOption.get
            }
            val xPlus = word.right(x)
            val from = k match {
              case Some(l) => math.max(x + 1, l.end)
              case None => x + 1
            }
            val y = (from to xPlus).find(z => word.right(z) >= xPlus)
            y match {
              case None => out += Left(xPlus + 1)
              case Some(z) =>
                if (word.inRegion(x, z) && !k.exists(_.end == z)) {
                  out += Right(word.maximalLattice(x, z - x))
                } else {
                  out += Left(z)
                }
            }
          }
        case Right(l) =>
          if (word.right(l) >= word.last) { go = false } else if (word.symmetricChain(l)) {
            out += Left(word.centre(l).get)
          } else {
            out += Left(l.end)
          }
      }
      if (out.size > 4 * word.last + 8) { go = false }   // safety net while debugging
    }
    out.toVector
  }

  /** Centres claimed by conditions (A) and (B). */
  def centres(w: String): Set[Int] = {
    val word = new Word(w)
    trace(w).flatMap {
      case Left(x) => if (word.valid(x) && word.left(x) == 1) { Vector(x) } else { Vector.empty }
      case Right(l) =>
        // (B): nodes up to the centre of the region (x ≤ L⁰), when the region reaches point 1
        if (word.left(l) == 1) { l.points.filter(x => 2 * x <= word.left(l) + word.right(l)) } else { Vector.empty }
    }.toSet
  }

  /** Work of the direct machine on `w`, in elementary head moves.
    *
    * For a point value x: the region search (2·R(x)) and the Fischer–Paterson pass over
    * `X * X` for the region X = ω[x⁻..x⁺] (one step per symbol plus one per failure-link
    * iteration, as in the algorithm's case 3). For a lattice: the extension sweep over its region.
    */
  final case class Cost(regions: Long, fp: Long, fpIterations: Long, lattices: Long, values: Int) {
    def total: Long = regions + fp + lattices
  }

  def cost(w: String): Cost = {
    val word = new Word(w)
    var regions = 0L
    var fp = 0L
    var fpIter = 0L
    var lattices = 0L
    val values = trace(w)
    for (v <- values) v match {
      case Left(x) if word.valid(x) =>
        regions += 2L * word.R(x)
        // Fischer–Paterson on Z = X * X, X = ω[x⁻..x⁺]
        val xs = (word.left(x) to word.right(x)).map(word.omega).toVector
        val z = xs ++ Vector('*') ++ xs
        val fail = new Array[Int](z.length + 1)   // KMP failure function of z (1-indexed lengths)
        fail(0) = 0
        if (z.nonEmpty) { fail(1) = 0 }
        var i = 1
        while (i < z.length) {
          var k = fail(i)
          fp += 1
          while (k > 0 && z(k) != z(i)) { k = fail(k); fp += 1; fpIter += 1 }
          fail(i + 1) = if (z(k) == z(i)) { k + 1 } else { 0 }
          i += 1
        }
      case Left(_) => ()
      case Right(l) => lattices += 2L * (word.right(l) - word.left(l) + 1)
    }
    Cost(regions, fp, fpIter, lattices, values.size)
  }

  private def fibonacciWord(n: Int): String = {
    var a = "a"
    var b = "ab"
    while (b.length < n) { val c = b + a; a = b; b = c }
    b.take(n)
  }

  private def zimin(k: Int): String = if (k == 0) { "a" } else { val z = zimin(k - 1); z + ("ab".charAt(k % 2)).toString + z }

  /** Maximum of total work / |W| over exhaustive short words and structured or random long words. */
  def costReport(maxLen: Int): Unit = {
    var worst = (0.0, "")
    for (w <- Exhaust.words(maxLen)) {
      val c = cost(w)
      val ratio = if (w.isEmpty) { 0.0 } else { c.total.toDouble / w.length }
      if (ratio > worst._1) { worst = (ratio, w) }
    }
    println(f"exhaustive <= $maxLen: max work/|W| = ${worst._1}%.2f on '${worst._2}'")
    val rng = new scala.util.Random(7)
    val samples: Vector[(String, String)] = Vector(
      "a^2000" -> "a" * 2000,
      "(ab)^1000" -> "ab" * 1000,
      "(aab)^700" -> "aab" * 700,
      "fibonacci 2000" -> fibonacciWord(2000),
      "zimin 10" -> zimin(10),
      "a^1000 b a^1000" -> ("a" * 1000 + "b" + "a" * 1000),
      "a^500 b a^500 b a^500" -> ("a" * 500 + "b" + "a" * 500 + "b" + "a" * 500),
      "random 2000" -> Vector.fill(2000)("ab".charAt(rng.nextInt(2))).mkString,
      "random pal 2000" -> { val h = Vector.fill(1000)("ab".charAt(rng.nextInt(2))).mkString; h + h.reverse })
    for ((name, w) <- samples) {
      val c = cost(w)
      println(f"$name%-24s |W|=${w.length}%5d values=${c.values}%6d regions=${c.regions}%8d fp=${c.fp}%8d (iterations ${c.fpIterations}%7d) lattices=${c.lattices}%8d  total/|W|=${c.total.toDouble / w.length}%.2f")
    }
  }

  /** Brute force: prefix lengths m such that W(1..m) is a palindrome, i.e. centres 1..n with R(m) = m−1. */
  def bruteCentres(w: String): Set[Int] =
    (1 to w.length).filter(m => Exhaust.isPal(w.substring(0, m))).toSet

  def check(maxLen: Int, alphabet: String = "ab"): Option[String] = {
    var bad: Option[String] = None
    val it = Exhaust.words(maxLen, alphabet)
    var tested = 0
    while (bad.isEmpty && it.hasNext) {
      val w = it.next()
      tested += 1
      val got = centres(w).filter(_ <= w.length)
      val want = bruteCentres(w)
      if (got != want) {
        println(s"mismatch on '$w': algorithm=${got.toVector.sorted} brute=${want.toVector.sorted}")
        println("  trace: " + trace(w).map(_.fold(_.toString, l => s"L(${l.first},+${l.step}x${l.count})")).mkString(" "))
        bad = Some(w)
      }
    }
    if (bad.isEmpty) { println(s"ok: Slisenko centres agree with brute force on all $tested words of length <= $maxLen") }
    bad
  }
}
