package research

/** EOF-anchored site codes (plan §3): a pointer's allocation site `i` is carried as a second match extent
  * that ends `i` letters before the end of the input. Everything here is plain PEG.
  *
  *   Tail_0 = !.            Tail_i = . Tail_{i-1}         (succeeds iff exactly i letters remain)
  *   Code_i = &Tail_i / . Code_i                            (from p: succeeds iff n-p >= i, ends at n-i)
  *   Mask_k = Tail_i for every i < count with bit k set    (reads bit k of the remaining length)
  *
  * Sites are coloured so that colours are injective on every set of sites a pointer may point into
  * (`color`); the code of a site is its colour. The bounded fallback `Short` recognises the palindromes
  * shorter than 2 W0 exactly (EOF inside each alternative), `Long` requires at least 2 W0 letters.
  */
object SiteCode {
  import Expr.*
  type Site = (Int, Int)

  final case class Coloring(colors: Map[Site, Int], count: Int) {
    def injectiveOn(d: Set[Site]): Boolean = d.forall(colors.contains) && d.map(colors).size == d.size
    def requireInjective(domains: Iterable[Set[Site]]): Unit =
      for (d <- domains if !injectiveOn(d)) { throw new IllegalArgumentException(s"colouring is not injective on $d: ${d.toVector.sorted.map(s => s -> colors.get(s))}") }
    def apply(s: Site): Int = colors(s)
  }

  /** deterministic greedy colouring: sites in (layer, index) order, each takes the smallest colour not used by a
    * site that shares a domain with it */
  def color(domains: Vector[Set[Site]]): Coloring = {
    val sites = domains.flatten.distinct.sorted
    val neighbours: Map[Site, Set[Site]] = sites.map { s => s -> (domains.filter(_.contains(s)).flatten.toSet - s) }.toMap
    val colors = scala.collection.mutable.LinkedHashMap.empty[Site, Int]
    for (s <- sites) {
      val used = neighbours(s).flatMap(colors.get)
      var c = 0
      while (used(c)) { c += 1 }
      colors(s) = c
    }
    val result = Coloring(colors.toMap, if (colors.isEmpty) { 0 } else { colors.values.max + 1 })
    result.requireInjective(domains)
    result
  }

  def nbits(n: Int): Int = { var b = 0; while ((1 << b) < n) { b += 1 }; b }
  def nextPow2(n: Int): Int = { var p = 1; while (p < n) { p *= 2 }; p }

  private val Eps: Expr = Seq(Vector())
  def tail(i: Int): String = s"Tail_$i"
  def code(i: Int): String = s"Code_$i"
  def mask(k: Int): String = s"Mask_$k"

  /** Tail_0..Tail_{count-1}, Code_0..Code_{count-1}, Mask_0..Mask_{nbits(count)-1} */
  /** `codeByStar` (STRATEGY.md S1): `Code_i = (!Tail_i .)* &Tail_i` instead of `Code_i = &Tail_i / . Code_i` — the same
    * extent (the loop stops exactly i letters before the end, and fails with the `&Tail_i` when fewer remain), but the
    * rule no longer refers to itself, so the budgeted inliner may remove it (it never removes a self-referencing rule) */
  def helpers(count: Int, codeByStar: Boolean = false): Vector[(String, Expr)] = {
    val tails = (0 until count).map { i => (tail(i), if (i == 0) { Not(AnyChar) } else { Seq(Vector(AnyChar, Ref(tail(i - 1)))) }) }
    val codes = (0 until count).map { i =>
      if (codeByStar) { (code(i), Seq(Vector(Star(Seq(Vector(Not(Ref(tail(i))), AnyChar))), And(Ref(tail(i)))))) }
      else { (code(i), Choice(Vector(And(Ref(tail(i))), Seq(Vector(AnyChar, Ref(code(i))))))) }
    }
    val masks = (0 until nbits(count)).map { k =>
      val is = (0 until count).filter(i => ((i >> k) & 1) == 1).map(i => Ref(tail(i)))
      (mask(k), if (is.size == 1) { is.head } else { Choice(is.toVector) })
    }
    (tails ++ codes ++ masks).toVector
  }

  /** Pal_0..Pal_{2w0-1}, Short (exact-length palindromes shorter than 2 w0, EOF inside each alternative), Long (>= 2 w0 letters) */
  def fallback(w0: Int): Vector[(String, Expr)] = fallbackBelow(2 * w0)

  /** Pal_0..Pal_{limit-1}, Short (the palindromes shorter than `limit`, EOF inside each alternative), Long (>= limit letters) */
  def fallbackBelow(limit: Int): Vector[(String, Expr)] = {
    val top = limit - 1
    val pals = (0 to top).map { k =>
      val body: Expr = k match {
        case 0 => Eps
        case 1 => AnyChar
        case _ => Choice(Vector(Seq(Vector(Lit("a"), Ref(s"Pal_${k - 2}"), Lit("a"))), Seq(Vector(Lit("b"), Ref(s"Pal_${k - 2}"), Lit("b")))))
      }
      (s"Pal_$k", body)
    }
    val short = ("Short", Choice((0 to top).map(k => Seq(Vector(Ref(s"Pal_$k"), Not(AnyChar)))).toVector))
    val long = ("Long", Seq(Vector.fill(limit)(AnyChar)))
    pals.toVector :+ short :+ long
  }
}
