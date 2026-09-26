package research

import scala.collection.mutable

/** Common sub-expressions of an inlined grammar (STRATEGY.md §3c): the budgeted inliner only removes the compiler's rules,
  * so a large sub-expression that inlining has copied many times stays copied. Naming a sub-expression t that occurs c times
  * with s nodes as a new rule saves (c − 1)(s − 1) − 1 nodes at the price of one rule; the inliner pays, near N = 1,000,
  * about a thousand nodes per rule it removes, so a naming that saves more than that lowers the nodes at the same N.
  * Sub-expressions are hash-consed bottom up (one id per distinct tree), in time linear in the grammar. Read-only. */
object PegCse {
  import Expr.*

  final case class Stat(id: Int, count: Long, size: Long, sample: Expr) {
    def saving: Long = (count - 1) * (size - 1) - 1
  }

  /** every distinct sub-expression of the right-hand sides with its number of occurrences and its size */
  def stats(g: Grammar): Vector[Stat] = {
    val ids = mutable.HashMap.empty[Any, Int]
    val count = mutable.ArrayBuffer.empty[Long]
    val size = mutable.ArrayBuffer.empty[Long]
    val sample = mutable.ArrayBuffer.empty[Expr]
    def intern(key: Any, s: Long, e: Expr): Int = {
      val id = ids.getOrElseUpdate(key, { count += 0L; size += s; sample += e; count.size - 1 })
      count(id) += 1
      id
    }
    def go(e: Expr): (Int, Long) = e match {
      case Seq(ps) => val cs = ps.map(go); intern(("seq", cs.map(_._1)), 1 + cs.map(_._2).sum, e) -> (1 + cs.map(_._2).sum)
      case Choice(as) => val cs = as.map(go); intern(("alt", cs.map(_._1)), 1 + cs.map(_._2).sum, e) -> (1 + cs.map(_._2).sum)
      case Not(x) => val (i, s) = go(x); intern(("not", i), 1 + s, e) -> (1 + s)
      case And(x) => val (i, s) = go(x); intern(("and", i), 1 + s, e) -> (1 + s)
      case Star(x) => val (i, s) = go(x); intern(("star", i), 1 + s, e) -> (1 + s)
      case other => intern(other, 1L, e) -> 1L
    }
    for ((_, body) <- g.rules) { go(body) }
    count.indices.map(i => Stat(i, count(i), size(i), sample(i))).toVector
  }

  /** the `top` sub-expressions with the largest saving, and the number of them saving more than `threshold` nodes */
  def probe(g: Grammar, top: Int, threshold: Long): String = {
    val st = stats(g).filter(s => s.count >= 2 && s.size >= 2).sortBy(-_.saving)
    val (n, nodes) = g.size
    val lines = st.take(top).map(s => f"  saving ${s.saving}%9d  count ${s.count}%7d  size ${s.size}%6d  ${Expr.render(s.sample).take(90)}")
    s"$n rules, $nodes rhs nodes, ${stats(g).size} distinct sub-expressions; ${st.count(_.saving > threshold)} save more than $threshold nodes each\n" +
      lines.mkString("\n")
  }

  /** replace every occurrence of `t` in the right-hand sides by `Ref(name)` and add `name = t` (meaning preserving: a rule is
    * its body at the same position); occurrences are found by a bottom-up (hash, size) pass and confirmed structurally */
  def extract(g: Grammar, t: Expr, name: String): Grammar = {
    val tSize = Expr.size(t).toLong
    val tHash = t.hashCode
    def go(e: Expr): (Expr, Long) = {
      val (e2, s) = e match {
        case Seq(ps) => val cs = ps.map(go); (Seq(cs.map(_._1)), 1 + cs.map(_._2).sum)
        case Choice(as) => val cs = as.map(go); (Choice(cs.map(_._1)), 1 + cs.map(_._2).sum)
        case Not(x) => val (y, s) = go(x); (Not(y), 1 + s)
        case And(x) => val (y, s) = go(x); (And(y), 1 + s)
        case Star(x) => val (y, s) = go(x); (Star(y), 1 + s)
        case other => (other, 1L)
      }
      // the size is that of the original subtree, so a match is decided before any inner replacement
      if (s == tSize && e.hashCode == tHash && e == t) { (Ref(name), s) } else { (e2, s) }
    }
    new Grammar(g.rules.map { case (n, b) => (n, go(b)._1) } :+ (name -> t))
  }

  /** name the best sub-expression while it saves more than `threshold` nodes, at most `k` times (fresh names `prefix<i>`) */
  def extractBest(g0: Grammar, k: Int, threshold: Long, prefix: String): (Grammar, Int) = {
    var g = g0
    var done = 0
    var go = true
    val taken = g0.rules.map(_._1).toSet
    while (go && done < k) {
      val best = stats(g).filter(s => s.count >= 2 && s.size >= 2).maxByOption(_.saving)
      best match {
        case Some(b) if b.saving > threshold =>
          var i = done; while (taken(s"$prefix$i")) { i += 1 }
          g = extract(g, b.sample, s"$prefix$i"); done += 1
        case _ => go = false
      }
    }
    (g, done)
  }

  /** alternate naming (at most `k` per round, savings above `threshold`) and the budgeted inliner under `nodeBudget`, while the
    * rule count falls; returns the grammar and the (rules, nodes) after each round */
  def improve(g0: Grammar, nodeBudget: Long, maxRhs: Int, k: Int, threshold: Long, rounds: Int): (Grammar, Vector[(Int, Long)]) = {
    var g = PegBudgetInline.inline(g0, PegBudgetInline.Options(nodeBudget = nodeBudget, maxRhs = maxRhs)).grammar
    val trace = Vector.newBuilder[(Int, Long)]
    trace += ((g.rules.size, g.size._2.toLong))
    var r = 0
    var go = true
    while (go && r < rounds) {
      val (ge, n) = extractBest(g, k, threshold, s"Z${r}_")
      val gi = PegBudgetInline.inline(ge, PegBudgetInline.Options(nodeBudget = nodeBudget, maxRhs = maxRhs)).grammar
      trace += ((gi.rules.size, gi.size._2.toLong))
      if (n == 0 || gi.rules.size >= g.rules.size) { go = false } else { g = gi }
      r += 1
    }
    (g, trace.result())
  }
}

