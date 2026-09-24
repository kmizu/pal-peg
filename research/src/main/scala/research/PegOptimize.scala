package research

import scala.collection.mutable

/** Conservative, meaning-preserving compaction of a plain PEG (Task 1 of the compression plan):
  * reachability from the start rule, local identities on total expressions, acyclic alias removal,
  * merging of rules with identical right-hand sides. Every pass preserves failure/success and the
  * end position of every retained rule; `Result.oldToNew` maps each old rule that survives (kept,
  * aliased or merged) to the rule that now carries its meaning. Deleted (unreachable) rules have
  * no entry.
  */
object PegOptimize {
  final case class Result(grammar: Grammar, oldToNew: Map[String, String])

  val Eps: Expr = Expr.Seq(Vector())
  val Fail: Expr = Expr.Not(Eps)

  /** does the expression always succeed (on every input, at every position)? */
  private def alwaysSucceeds(e: Expr): Boolean = e match {
    case Expr.Seq(ps) => ps.forall(alwaysSucceeds)
    case Expr.Choice(as) => as.exists(alwaysSucceeds)
    case Expr.Star(_) => true
    case Expr.And(x) => alwaysSucceeds(x)
    case Expr.Not(x) => x == Fail
    case _ => false
  }

  /** local identities, valid for total PEG expressions (no non-consuming recursion) */
  def norm(e: Expr): Expr = e match {
    case Expr.Lit("") => Eps
    case Expr.Seq(ps) =>
      val qs = ps.map(norm).flatMap { case Expr.Seq(xs) => xs; case x => Vector(x) }
      if (qs.contains(Fail)) { Fail } else if (qs.size == 1) { qs.head } else { Expr.Seq(qs) }
    case Expr.Choice(as) =>
      val bs0 = as.map(norm).flatMap { case Expr.Choice(xs) => xs; case x => Vector(x) }.filter(_ != Fail)
      val bs = bs0.indexWhere(alwaysSucceeds) match { case -1 => bs0; case i => bs0.take(i + 1) }
      if (bs.isEmpty) { Fail } else if (bs.size == 1) { bs.head } else { Expr.Choice(bs) }
    case Expr.Not(x) => norm(x) match {
      case y if y == Eps => Fail
      case y if y == Fail => Eps
      case y if alwaysSucceeds(y) => Fail
      case Expr.Not(y) => Expr.And(y)
      case Expr.And(y) => Expr.Not(y)
      case y => Expr.Not(y)
    }
    case Expr.And(x) => norm(x) match {
      case y if y == Fail => Fail
      case y if alwaysSucceeds(y) => Eps
      case Expr.And(y) => Expr.And(y)
      case Expr.Not(y) => Expr.Not(y)
      case y => Expr.And(y)
    }
    case Expr.Star(x) => norm(x) match {
      case y if y == Fail => Eps
      case y => Expr.Star(y)
    }
    case _ => e
  }

  private def refs(e: Expr, acc: mutable.HashSet[String]): Unit = e match {
    case Expr.Ref(n) => acc += n
    case Expr.Seq(ps) => ps.foreach(refs(_, acc))
    case Expr.Choice(as) => as.foreach(refs(_, acc))
    case Expr.Not(x) => refs(x, acc)
    case Expr.And(x) => refs(x, acc)
    case Expr.Star(x) => refs(x, acc)
    case _ => ()
  }

  private def subst(e: Expr, f: String => String): Expr = e match {
    case Expr.Ref(n) => Expr.Ref(f(n))
    case Expr.Seq(ps) => Expr.Seq(ps.map(subst(_, f)))
    case Expr.Choice(as) => Expr.Choice(as.map(subst(_, f)))
    case Expr.Not(x) => Expr.Not(subst(x, f))
    case Expr.And(x) => Expr.And(subst(x, f))
    case Expr.Star(x) => Expr.Star(subst(x, f))
    case _ => e
  }

  /** the rules reachable from `start`, in the original order */
  def reachable(rules: Vector[(String, Expr)], start: String): Vector[(String, Expr)] = {
    val table = rules.toMap
    val seen = mutable.HashSet.empty[String]
    val todo = mutable.Stack(start)
    while (todo.nonEmpty) {
      val n = todo.pop()
      if (seen.add(n)) { table.get(n).foreach { e => val rs = mutable.HashSet.empty[String]; refs(e, rs); rs.foreach(todo.push) } }
    }
    rules.filter { case (n, _) => seen(n) }
  }

  final case class Options(inlineSingleUse: Boolean = false)

  def compact(g: Grammar, options: Options = Options()): Result = {
    val start = g.start
    var rules: Vector[(String, Expr)] = g.rules.map { case (n, e) => (n, norm(e)) }
    val alias = mutable.HashMap.empty[String, String]   // old name -> representative (transitively)
    def rep(n: String): String = { var x = n; while (alias.contains(x) && alias(x) != x) { x = alias(x) }; x }
    var changed = true
    while (changed) {
      changed = false
      // 1. acyclic aliases A = B (the start rule keeps its name: B is renamed to S instead)
      val table = rules.toMap
      def endOfChain(n: String): Option[String] = {
        // follow A = B = C ... to the first rule that is not an alias; None on a cycle
        val seen = mutable.HashSet(n)
        var t = n
        var done = false
        var cyclic = false
        while (!done) table.get(t) match {
          case Some(Expr.Ref(c)) if table.contains(c) => if (!seen.add(c)) { cyclic = true; done = true } else { t = c }
          case _ => done = true
        }
        if (cyclic) { None } else { Some(t) }
      }
      var startBody: Option[Expr] = None   // the start rule takes the body of the end of its alias chain
      for ((n, e) <- rules) e match {
        case Expr.Ref(b) if b != n && table.contains(b) && !alias.contains(n) =>
          endOfChain(n).filter(_ != n).foreach { t =>
            if (n == start) { alias(t) = n; startBody = Some(table(t)) } else { alias(n) = t }
            changed = true
          }
        case _ => ()
      }
      if (changed) {
        val kept = rules.filter { case (n, _) => rep(n) == n }
        rules = (startBody match {
          case Some(e) => kept.map { case (n, x) => if (n == start) { (n, e) } else { (n, x) } }
          case None => kept
        }).map { case (n, e) => (n, norm(subst(e, rep))) }
      }
      // 2. identical right-hand sides
      val groups = rules.groupBy(_._2).values.filter(_.size >= 2)
      if (groups.nonEmpty) {
        for (grp <- groups) {
          val r = grp.map(_._1).find(_ == start).getOrElse(grp.head._1)
          for ((n, _) <- grp if n != r) { alias(n) = r }
        }
        rules = rules.filter { case (n, _) => rep(n) == n }.map { case (n, e) => (n, norm(subst(e, rep))) }
        changed = true
      }
      // 3. reachability
      val live = reachable(rules, start)
      if (live.size != rules.size) { rules = live; changed = true }
    }
    if (options.inlineSingleUse) {
      // inline acyclic helpers referenced exactly once (never the start rule)
      val uses = mutable.HashMap.empty[String, Int]
      def count(e: Expr): Unit = e match {
        case Expr.Ref(n) => uses(n) = uses.getOrElse(n, 0) + 1
        case Expr.Seq(ps) => ps.foreach(count)
        case Expr.Choice(as) => as.foreach(count)
        case Expr.Not(x) => count(x)
        case Expr.And(x) => count(x)
        case Expr.Star(x) => count(x)
        case _ => ()
      }
      rules.foreach { case (_, e) => count(e) }
      val table = mutable.HashMap(rules*)
      val single = rules.collect { case (n, _) if n != start && uses.getOrElse(n, 0) == 1 => n }.toSet
      def selfRef(n: String, e: Expr): Boolean = { val rs = mutable.HashSet.empty[String]; refs(e, rs); rs(n) }
      def inl(e: Expr): Expr = e match {
        case Expr.Ref(n) if single(n) && table.contains(n) && !selfRef(n, table(n)) => inl(table(n))
        case Expr.Seq(ps) => Expr.Seq(ps.map(inl))
        case Expr.Choice(as) => Expr.Choice(as.map(inl))
        case Expr.Not(x) => Expr.Not(inl(x))
        case Expr.And(x) => Expr.And(inl(x))
        case Expr.Star(x) => Expr.Star(inl(x))
        case _ => e
      }
      rules = rules.map { case (n, e) => (n, norm(inl(e))) }
      rules = reachable(rules, start)
    }
    val kept = rules.map(_._1).toSet
    val oldToNew = g.rules.map(_._1).flatMap { n => val r = rep(n); if (kept(r)) { Some(n -> r) } else { None } }.toMap
    Result(new Grammar(rules), oldToNew)
  }
}
