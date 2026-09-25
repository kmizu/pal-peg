package research

import scala.collection.mutable

/** Endpoint truncation without moving backward (under-1000 plan §6): for every rule `r` of a total PEG three fresh
  * rules are added —
  *
  *   Z_r  succeeds with zero consumption iff r succeeds with zero consumption,
  *   P_r  returns r's end position iff r consumes at least one character,
  *   D_r  returns r's end position minus one iff r consumes at least one character,
  *
  * and fails otherwise. The original rules are kept unchanged (a conservative extension). Rules:
  *
  *   Z(t) = fail, P(t) = t, D(t) = &t for a one-character terminal; Z("") = "", P("") = D("") = fail;
  *   Z(&A) = &A, Z(!A) = !A, P/D of a predicate = fail;
  *   Z(A/B) = Z(A) / !A Z(B), P(A/B) = P(A) / !A P(B), D(A/B) = D(A) / !A D(B);
  *   Z(A B) = Z(A) Z(B), P(A B) = P(A) B / Z(A) P(B), D(A B) = &(A P(B)) A D(B) / &(A Z(B)) D(A);
  *   A* (A progressing): Z(A*) = !A, P(A*) = A A*, D(A*) = a fresh rule  &(A P(A*)) A D(A*) / &(A !A) D(A).
  *
  * Multi-character literals are split into one-character terminals first. `PegTruncateSuite` checks the three
  * specifications at every position on all short words. The transformer terminates on every finite grammar
  * (each rule and each `A*` gets a fixed number of fresh rules), but a *machine* rule that would use `D` of a
  * recurrent rule needs `D` of that `D` and so on: the family `D^k` does not close (§6 of `UNDER1000.md`).
  */
object PegTruncate {
  import Expr.*

  val Fail: Expr = Not(Seq(Vector()))
  val Eps: Expr = Seq(Vector())

  final class Transformer(g: Grammar) {
    private val out = mutable.LinkedHashMap.empty[String, Expr]
    private val stars = mutable.HashMap.empty[Expr, String]
    private var fresh = 0
    for ((n, e) <- g.rules) { out(n) = e }

    private def split(e: Expr): Expr = e match {
      case Lit(s) if s.length > 1 => Seq(s.toVector.map(c => Lit(c.toString)))
      case Seq(ps) => Seq(ps.map(split))
      case Choice(as) => Choice(as.map(split))
      case Not(x) => Not(split(x))
      case And(x) => And(split(x))
      case Star(x) => Star(split(x))
      case _ => e
    }
    private def isTerminal(e: Expr): Boolean = e match { case Lit(s) => s.length == 1; case Cls(_) => true; case AnyChar => true; case _ => false }

    private def zRef(r: String): Expr = { ensure(r); Ref(s"Z_$r") }
    private def pRef(r: String): Expr = { ensure(r); Ref(s"P_$r") }
    private def dRef(r: String): Expr = { ensure(r); Ref(s"D_$r") }
    private val done = mutable.HashSet.empty[String]
    private def ensure(r: String): Unit = if (done.add(r)) {
      val body = split(g.table(r))
      out(s"Z_$r") = Fail; out(s"P_$r") = Fail; out(s"D_$r") = Fail   // placeholders (recursion)
      out(s"Z_$r") = z(body); out(s"P_$r") = p(body); out(s"D_$r") = d(body)
    }
    private def starD(a: Expr): Expr = {
      val name = stars.getOrElseUpdate(a, { fresh += 1; s"DStar_$fresh" })
      if (!out.contains(name)) {
        out(name) = Fail
        out(name) = Choice(Vector(Seq(Vector(And(Seq(Vector(a, p(Star(a))))), a, Ref(name))), Seq(Vector(And(Seq(Vector(a, Not(a)))), d(a)))))
      }
      Ref(name)
    }

    def z(e: Expr): Expr = e match {
      case t if isTerminal(t) => Fail
      case Lit("") => Eps
      case Seq(ps) if ps.isEmpty => Eps
      case Seq(ps) if ps.size == 1 => z(ps.head)
      case Seq(ps) => Seq(Vector(z(ps.head), z(Seq(ps.tail))))
      case Choice(as) if as.size == 1 => z(as.head)
      case Choice(as) => Choice(Vector(z(as.head), Seq(Vector(Not(as.head), z(Choice(as.tail))))))
      case Not(x) => Not(x)
      case And(x) => And(x)
      case Star(x) => Not(x)
      case Ref(r) => zRef(r)
      case Lit(_) | Cls(_) | AnyChar => Fail
    }
    def p(e: Expr): Expr = e match {
      case t if isTerminal(t) => t
      case Lit("") => Fail
      case Seq(ps) if ps.isEmpty => Fail
      case Seq(ps) if ps.size == 1 => p(ps.head)
      case Seq(ps) =>
        val a = ps.head
        val b = Seq(ps.tail)
        Choice(Vector(Seq(Vector(p(a), b)), Seq(Vector(z(a), p(b)))))
      case Choice(as) if as.size == 1 => p(as.head)
      case Choice(as) => Choice(Vector(p(as.head), Seq(Vector(Not(as.head), p(Choice(as.tail))))))
      case Not(_) | And(_) => Fail
      case Star(x) => Seq(Vector(x, Star(x)))
      case Ref(r) => pRef(r)
      case Lit(_) | Cls(_) | AnyChar => e
    }
    def d(e: Expr): Expr = e match {
      case t if isTerminal(t) => And(t)
      case Lit("") => Fail
      case Seq(ps) if ps.isEmpty => Fail
      case Seq(ps) if ps.size == 1 => d(ps.head)
      case Seq(ps) =>
        val a = ps.head
        val b = Seq(ps.tail)
        Choice(Vector(Seq(Vector(And(Seq(Vector(a, p(b)))), a, d(b))), Seq(Vector(And(Seq(Vector(a, z(b)))), d(a)))))
      case Choice(as) if as.size == 1 => d(as.head)
      case Choice(as) => Choice(Vector(d(as.head), Seq(Vector(Not(as.head), d(Choice(as.tail))))))
      case Not(_) | And(_) => Fail
      case Star(x) => starD(x)
      case Ref(r) => dRef(r)
      case Lit(_) | Cls(_) | AnyChar => And(e)
    }

    /** the original grammar plus Z_r, P_r, D_r for every rule (and the DStar helpers) */
    def grammar(): Grammar = { g.rules.foreach { case (n, _) => ensure(n) }; new Grammar(out.toVector) }
  }

  def transform(g: Grammar): Grammar = new Transformer(g).grammar()
}
