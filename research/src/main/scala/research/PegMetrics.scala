package research

import scala.collection.mutable

/** Size metrics of a plain PEG (§3 of the compression plan): rules, right-hand-side tree nodes, the
  * largest right-hand side, rendered bytes, distinct DAG nodes, per-component counts, dispatch arms. */
object PegMetrics {
  final case class Metrics(
    rules: Int,
    rhsNodes: Long,
    maxRhsNodes: Int,
    renderedUtf8Bytes: Long,
    distinctRhsDagNodes: Int,
    dispatchArmCount: Long,
    rulesByComponent: Vector[(String, Int)],
    rhsNodesByComponent: Vector[(String, Long)]) {
    def json(extra: Seq[(String, String)]): String = {
      def q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      val fields = extra.map { case (k, v) => s"  ${q(k)}: ${q(v)}" } ++ Seq(
        s"  \"N\": $rules", s"  \"rhs_nodes\": $rhsNodes", s"  \"max_rhs_nodes\": $maxRhsNodes",
        s"  \"rendered_utf8_bytes\": $renderedUtf8Bytes", s"  \"distinct_rhs_dag_nodes\": $distinctRhsDagNodes",
        s"  \"dispatch_arm_count\": $dispatchArmCount",
        "  \"N_by_component\": {" + rulesByComponent.map { case (c, n) => s"${q(c)}: $n" }.mkString(", ") + "}",
        "  \"rhs_nodes_by_component\": {" + rhsNodesByComponent.map { case (c, n) => s"${q(c)}: $n" }.mkString(", ") + "}")
      "{\n" + fields.mkString(",\n") + "\n}\n"
    }
    def markdownRow(label: String): String =
      s"| $label | $rules | $rhsNodes | $maxRhsNodes | $renderedUtf8Bytes | $distinctRhsDagNodes | $dispatchArmCount |"
  }
  val markdownHeader: String =
    "| grammar | N | rhs nodes | max rhs | bytes | distinct DAG nodes | dispatch arms |\n|---|---|---|---|---|---|---|"

  /** `component` names the component of a rule (the slot recorded by the compiler, else the name's prefix) */
  def of(g: Grammar, component: String => String): Metrics = {
    val dag = mutable.HashSet.empty[Expr]
    def walk(e: Expr): Unit = if (dag.add(e)) e match {
      case Expr.Seq(ps) => ps.foreach(walk)
      case Expr.Choice(as) => as.foreach(walk)
      case Expr.Not(x) => walk(x)
      case Expr.And(x) => walk(x)
      case Expr.Star(x) => walk(x)
      case _ => ()
    }
    /** alternatives of all choices, counted per occurrence in the tree */
    def armsOf(e: Expr): Long = e match {
      case Expr.Seq(ps) => ps.map(armsOf).sum
      case Expr.Choice(as) => as.size.toLong + as.map(armsOf).sum
      case Expr.Not(x) => armsOf(x)
      case Expr.And(x) => armsOf(x)
      case Expr.Star(x) => armsOf(x)
      case _ => 0L
    }
    val sizes = g.rules.map { case (_, e) => Expr.size(e) }
    g.rules.foreach { case (_, e) => walk(e) }
    val comps = g.rules.map { case (n, _) => component(n) }
    val rulesBy = comps.groupBy(identity).map { case (c, xs) => c -> xs.size }.toVector.sortBy(_._1)
    val nodesBy = sizes.zip(comps).groupBy(_._2).map { case (c, xs) => c -> xs.map(_._1.toLong).sum }.toVector.sortBy(_._1)
    Metrics(g.rules.size, sizes.map(_.toLong).sum, if (sizes.isEmpty) { 0 } else { sizes.max },
      g.render.getBytes("UTF-8").length.toLong, dag.size, g.rules.map { case (_, e) => armsOf(e) }.sum, rulesBy, nodesBy)
  }

  def componentOfName(n: String): String = {
    val head = n.split("_").head
    if (head.startsWith("X") || head.startsWith("K") || head == "C") { "aux" } else { head }
  }
}
