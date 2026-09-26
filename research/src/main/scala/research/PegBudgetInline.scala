package research

import scala.collection.mutable

/** Cost-controlled elimination of named intermediates (plan §5 of the under-1000 instructions): after the
  * conservative compaction of `PegOptimize`, a rule `r` with right-hand side of `s` nodes referenced `u` times
  * (inside predicates and repetitions too, every occurrence counted) is replaced by its body at every use when
  * the immediate change of the totals stays within the budgets:
  *
  *   deltaNodes = u (s − 1) − s,   deltaBytes ≈ u (b + 2 − l) − (b + l + 5)
  *
  * (`b` body bytes, `l` name bytes; the byte formula assumes an always-parenthesised substitution and the renderer's
  * `name = body;\n`, so it is an estimate — the exported metrics decide). The pass is exactly meaning preserving for
  * every retained rule (a reference means its body at the same position); it never touches the start rule, a
  * protected rule, or a rule that references itself, and it re-checks self-reference after every substitution
  * (mutual recursion makes the survivor self-referencing). Greedy: the candidate with the smallest delta first.
  */
object PegBudgetInline {
  sealed trait Priority
  case object Nodes extends Priority
  case object Bytes extends Priority
  /** the node delta plus `lambda` times the growth this elimination adds to the future elimination of every user q:
    * occ(q, r) (s_r − 1) (u_q − 1) (STRATEGY.md §3c, a look-ahead against the myopic order) */
  final case class Lookahead(lambda: Double) extends Priority

  /** `nodeBudget`/`byteBudget`: bounds on the grammar totals; `maxRhs`: bound on every right-hand side after a
    * substitution; `protect`: never eliminated (the start rule never is) */
  final case class Options(nodeBudget: Long, byteBudget: Long = Long.MaxValue, maxRhs: Int = Int.MaxValue,
                           priority: Priority = Nodes, protect: Set[String] = Set(), maxEliminations: Int = Int.MaxValue)
  /** `trace`: after each elimination, (rules left, total rhs nodes, byte estimate). With a fixed `maxRhs` the eliminations
    * under a smaller node budget are a prefix of those under a larger one (the cheapest candidate is taken first, and if it
    * does not fit, no candidate does), so one run with the largest budget gives the whole N-versus-nodes curve. */
  final case class Result(grammar: Grammar, eliminated: Vector[String], nodes: Long, bytesEstimate: Long, trace: Vector[(Int, Long, Long)] = Vector())

  /** occurrences of every referenced rule in `e` */
  def occurrences(e: Expr): Map[String, Int] = {
    val m = mutable.HashMap.empty[String, Int]
    def go(x: Expr): Unit = x match {
      case Expr.Ref(n) => m(n) = m.getOrElse(n, 0) + 1
      case Expr.Seq(ps) => ps.foreach(go)
      case Expr.Choice(as) => as.foreach(go)
      case Expr.Not(y) => go(y)
      case Expr.And(y) => go(y)
      case Expr.Star(y) => go(y)
      case _ => ()
    }
    go(e)
    m.toMap
  }

  /** every `Ref(name)` replaced by `body`; no normalisation */
  def substitute(e: Expr, name: String, body: Expr): Expr = e match {
    case Expr.Ref(n) if n == name => body
    case Expr.Seq(ps) => Expr.Seq(ps.map(substitute(_, name, body)))
    case Expr.Choice(as) => Expr.Choice(as.map(substitute(_, name, body)))
    case Expr.Not(y) => Expr.Not(substitute(y, name, body))
    case Expr.And(y) => Expr.And(substitute(y, name, body))
    case Expr.Star(y) => Expr.Star(substitute(y, name, body))
    case _ => e
  }

  private def utf8(s: String): Long = s.getBytes("UTF-8").length.toLong

  def inline(g: Grammar, opts: Options): Result = {
    val start = g.start
    val order = g.rules.map(_._1)
    val body = mutable.HashMap(g.rules*)
    val size = mutable.HashMap(g.rules.map { case (n, e) => n -> Expr.size(e).toLong }*)
    val bytes = mutable.HashMap(g.rules.map { case (n, e) => n -> utf8(Expr.render(e)) }*)
    val occ = mutable.HashMap(g.rules.map { case (n, e) => n -> mutable.HashMap(occurrences(e).toSeq*) }*)
    val users = mutable.HashMap.empty[String, mutable.HashSet[String]]
    for ((n, m) <- occ; t <- m.keys) { users.getOrElseUpdate(t, mutable.HashSet.empty) += n }
    val useCount = mutable.HashMap.empty[String, Long]
    for ((_, m) <- occ; (t, k) <- m) { useCount(t) = useCount.getOrElse(t, 0L) + k }
    var totalNodes = size.values.sum
    var totalBytes = order.map(n => utf8(n) + bytes(n) + 5).sum
    val live = mutable.LinkedHashSet(order*)
    val gone = Vector.newBuilder[String]
    val trace = Vector.newBuilder[(Int, Long, Long)]
    def uses(r: String): Long = useCount.getOrElse(r, 0L)
    def deltas(r: String): (Long, Long) = {
      val u = uses(r)
      val s = size(r)
      val b = bytes(r)
      val l = utf8(r)
      (u * (s - 1) - s, u * (b + 2 - l) - (b + l + 5))
    }
    def future(r: String): Long = {
      val s = size(r)
      users.get(r).map(_.iterator.map(q => occ(q).getOrElse(r, 0).toLong * (s - 1) * math.max(0L, uses(q) - 1)).sum).getOrElse(0L)
    }
    def key(r: String): (Long, Long) = {
      val (dn, db) = deltas(r)
      opts.priority match { case Nodes => (dn, db); case Bytes => (db, dn); case Lookahead(l) => (dn + (l * future(r)).toLong, db) }
    }
    def less(a: (Long, Long), b: (Long, Long)): Boolean = a._1 < b._1 || (a._1 == b._1 && a._2 < b._2)
    def eligible(r: String): Boolean = r != start && !opts.protect(r) && !occ(r).contains(r) && uses(r) >= 1 && {
      val (dn, db) = deltas(r)
      totalNodes + dn <= opts.nodeBudget && totalBytes + db <= opts.byteBudget &&
        users(r).forall(q => size(q) + occ(q)(r) * (size(r) - 1) <= opts.maxRhs)
    }
    var go = true
    var steps = 0
    while (go && steps < opts.maxEliminations) {
      var best: Option[(String, (Long, Long))] = None
      for (r <- live if eligible(r)) {
        val k = key(r)
        if (best.forall { case (_, bk) => less(k, bk) }) { best = Some((r, k)) }
      }
      best match {
        case None => go = false
        case Some((r, _)) =>
          val (dn, db) = deltas(r)
          val br = body(r)
          val sr = size(r)
          val bb = bytes(r)
          val lr = utf8(r)
          for ((t, k) <- occ(r)) { useCount(t) = useCount(t) - k }   // r's own references disappear with r
          for (q <- users(r).toVector) {
            val c = occ(q)(r)
            body(q) = substitute(body(q), r, br)
            size(q) += c * (sr - 1)
            bytes(q) += c * (bb + 2 - lr)
            occ(q).remove(r)
            for ((t, k) <- occ(r)) {
              occ(q)(t) = occ(q).getOrElse(t, 0) + c * k
              useCount(t) = useCount.getOrElse(t, 0L) + c.toLong * k
              users.getOrElseUpdate(t, mutable.HashSet.empty) += q
            }
          }
          useCount.remove(r)
          for (t <- occ(r).keys) { users.get(t).foreach(_ -= r) }
          users.remove(r); occ.remove(r); body.remove(r); size.remove(r); bytes.remove(r); live -= r
          totalNodes += dn
          totalBytes += db
          gone += r
          steps += 1
          trace += ((live.size, totalNodes, totalBytes))
      }
    }
    Result(new Grammar(order.filter(live).map(n => (n, body(n)))), gone.result(), totalNodes, totalBytes, trace.result())
  }

  /** the smallest node increase of one more elimination, ignoring the node budget (the price of a rule at this point of the
    * greedy run; `PegCse.improveAdaptive` names a sub-expression only when it saves more than this) */
  def cheapest(g: Grammar, maxRhs: Int = Int.MaxValue, protect: Set[String] = Set()): Option[Long] = {
    val occ = g.rules.map { case (n, e) => n -> occurrences(e) }.toMap
    val size = g.rules.map { case (n, e) => n -> Expr.size(e).toLong }.toMap
    val uses = mutable.HashMap.empty[String, Long]
    val users = mutable.HashMap.empty[String, List[String]]
    for ((n, m) <- occ; (t, k) <- m) { uses(t) = uses.getOrElse(t, 0L) + k; users(t) = n :: users.getOrElse(t, Nil) }
    g.rules.iterator.map(_._1).filter { r =>
      r != g.start && !protect(r) && !occ(r).contains(r) && uses.getOrElse(r, 0L) >= 1 &&
        users.getOrElse(r, Nil).forall(q => size(q) + occ(q)(r) * (size(r) - 1) <= maxRhs)
    }.map(r => uses(r) * (size(r) - 1) - size(r)).minOption
  }
}

