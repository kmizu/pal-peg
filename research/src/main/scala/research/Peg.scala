package research

import scala.collection.mutable

/** Expressions of a parsing expression grammar. */
sealed trait Expr
object Expr {
  final case class Seq(parts: Vector[Expr]) extends Expr
  final case class Choice(alts: Vector[Expr]) extends Expr
  final case class Not(e: Expr) extends Expr
  final case class And(e: Expr) extends Expr
  final case class Star(e: Expr) extends Expr
  final case class Lit(s: String) extends Expr
  final case class Cls(chars: String) extends Expr
  case object AnyChar extends Expr
  final case class Ref(name: String) extends Expr

  val empty: Expr = Seq(Vector.empty)
  val fail: Expr = Not(empty)

  def seq(parts: Expr*): Expr = {
    val flat = parts.toVector.flatMap {
      case Seq(ps) => ps
      case e => Vector(e)
    }
    if (flat.size == 1) { flat.head } else { Seq(flat) }
  }

  def choice(alts: Expr*): Expr = {
    val flat = alts.toVector.flatMap {
      case Choice(as) => as
      case e => Vector(e)
    }
    if (flat.size == 1) { flat.head } else { Choice(flat) }
  }

  /** Number of expression nodes. */
  def size(e: Expr): Int = e match {
    case Seq(ps) => 1 + ps.map(size).sum
    case Choice(as) => 1 + as.map(size).sum
    case Not(x) => 1 + size(x)
    case And(x) => 1 + size(x)
    case Star(x) => 1 + size(x)
    case _ => 1
  }

  def render(e: Expr): String = {
    def lit(s: String): String = "\"" + s.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case c => c.toString
    } + "\""
    def atom(x: Expr): String = x match {
      case Seq(ps) if ps.isEmpty => "\"\""
      case Lit(s) => lit(s)
      case Cls(cs) => "[" + cs + "]"
      case AnyChar => "."
      case Ref(n) => n
      case Not(y) => "!" + atom(y)
      case And(y) => "&" + atom(y)
      case Star(y) => atom(y) + "*"
      case other => "(" + render(other) + ")"
    }
    e match {
      case Seq(ps) if ps.isEmpty => "\"\""
      case Seq(ps) => ps.map(atom).mkString(" ")
      case Choice(as) => as.map {
        case s @ Seq(ps) if ps.size > 1 => render(s)
        case a => atom(a)
      }.mkString(" / ")
      case other => atom(other)
    }
  }
}

/** A rule re-entered at the same input position without consuming: the grammar is not total. */
final class NonTerminating(message: String) extends RuntimeException(message)

/** A grammar: rules in definition order; the first rule is the start rule. */
final class Grammar(val rules: Vector[(String, Expr)]) {
  val table: Map[String, Expr] = rules.toMap
  require(rules.size == table.size, "duplicate rule names")
  val start: String = rules.head._1

  /** (rules, expression nodes on all right-hand sides). */
  def size: (Int, Int) = (rules.size, rules.map { case (_, e) => Expr.size(e) }.sum)

  def render: String = rules.map { case (n, e) => s"$n = ${Expr.render(e)};" }.mkString("\n") + "\n"

  def matcher(text: String): Matcher = new Matcher(this, text)

  def accepts(text: String, from: String = start): Boolean = matcher(text).rule(from, 0).contains(text.length)

  def extent(text: String, name: String, pos: Int): Option[Int] = matcher(text).rule(name, pos)
}

object Grammar {
  /** Parse `Name = expr;` rules (`<-` also accepted; `#` starts a comment). */
  def parse(text: String): Grammar = new GrammarParser(text).grammar()
}

final class Matcher(g: Grammar, text: String) {
  private val memo = mutable.HashMap.empty[(String, Int), Option[Int]]
  private val active = mutable.HashSet.empty[(String, Int)]

  def rule(name: String, pos: Int): Option[Int] = {
    val key = (name, pos)
    memo.get(key) match {
      case Some(v) => v
      case None =>
        if (active.contains(key)) { throw new NonTerminating(s"rule $name re-entered at position $pos") }
        active += key
        val body = g.table.getOrElse(name, throw new NoSuchElementException(s"undefined rule $name"))
        val r = eval(body, pos)
        active -= key
        memo(key) = r
        r
    }
  }

  def eval(e: Expr, pos: Int): Option[Int] = e match {
    case Expr.Seq(parts) =>
      var p = pos
      var i = 0
      var ok = true
      while (ok && i < parts.length) {
        eval(parts(i), p) match {
          case Some(q) => p = q
          case None => ok = false
        }
        i += 1
      }
      if (ok) { Some(p) } else { None }
    case Expr.Choice(alts) =>
      var i = 0
      var result: Option[Int] = None
      while (result.isEmpty && i < alts.length) {
        result = eval(alts(i), pos)
        i += 1
      }
      result
    case Expr.Not(x) => if (eval(x, pos).isEmpty) { Some(pos) } else { None }
    case Expr.And(x) => if (eval(x, pos).isDefined) { Some(pos) } else { None }
    case Expr.Star(x) =>
      var p = pos
      var go = true
      while (go) {
        eval(x, p) match {
          case Some(q) if q > p => p = q
          case _ => go = false
        }
      }
      Some(p)
    case Expr.Lit(s) => if (text.startsWith(s, pos)) { Some(pos + s.length) } else { None }
    case Expr.Cls(cs) => if (pos < text.length && cs.indexOf(text.charAt(pos)) >= 0) { Some(pos + 1) } else { None }
    case Expr.AnyChar => if (pos < text.length) { Some(pos + 1) } else { None }
    case Expr.Ref(name) => rule(name, pos)
  }
}

/** Recursive-descent parser for the grammar text format. */
private final class GrammarParser(text: String) {
  private var i = 0

  private def skip(): Unit = {
    var go = true
    while (go) {
      while (i < text.length && text.charAt(i).isWhitespace) { i += 1 }
      if (i < text.length && text.charAt(i) == '#') {
        while (i < text.length && text.charAt(i) != '\n') { i += 1 }
      } else {
        go = false
      }
    }
  }

  private def peek: Char = if (i < text.length) { text.charAt(i) } else { '\u0000' }

  private def ident(): String = {
    skip()
    val start = i
    while (i < text.length && (text.charAt(i).isLetterOrDigit || text.charAt(i) == '_')) { i += 1 }
    if (start == i) { throw new IllegalArgumentException(s"identifier expected at $i: ${text.slice(i, i + 20)}") }
    text.substring(start, i)
  }

  def grammar(): Grammar = {
    val rules = Vector.newBuilder[(String, Expr)]
    skip()
    while (i < text.length) {
      val name = ident()
      skip()
      if (text.startsWith("<-", i)) { i += 2 } else if (peek == '=') { i += 1 } else {
        throw new IllegalArgumentException(s"`=` expected after $name at $i")
      }
      val e = choice()
      skip()
      if (peek == ';') { i += 1 }
      rules += ((name, e))
      skip()
    }
    new Grammar(rules.result())
  }

  private def choice(): Expr = {
    val alts = Vector.newBuilder[Expr]
    alts += sequence()
    skip()
    while (peek == '/') {
      i += 1
      alts += sequence()
      skip()
    }
    Expr.choice(alts.result()*)
  }

  private def atRuleStart: Boolean = {
    // an identifier followed by `=` or `<-` starts the next rule
    val save = i
    val ok = try {
      if (!(peek.isLetter || peek == '_')) { false } else {
        ident(); skip()
        peek == '=' || text.startsWith("<-", i)
      }
    } catch { case _: IllegalArgumentException => false }
    i = save
    ok
  }

  private def sequence(): Expr = {
    val parts = Vector.newBuilder[Expr]
    var go = true
    while (go) {
      skip()
      if (i >= text.length || peek == '/' || peek == ')' || peek == ';' || atRuleStart) { go = false } else { parts += prefix() }
    }
    Expr.seq(parts.result()*)
  }

  private def prefix(): Expr = {
    skip()
    peek match {
      case '!' => i += 1; Expr.Not(prefix())
      case '&' => i += 1; Expr.And(prefix())
      case _ => suffix()
    }
  }

  private def suffix(): Expr = {
    var e = primary()
    var go = true
    while (go) {
      skip()
      peek match {
        case '*' => i += 1; e = Expr.Star(e)
        case '+' => i += 1; e = Expr.seq(e, Expr.Star(e))
        case '?' => i += 1; e = Expr.choice(e, Expr.empty)
        case _ => go = false
      }
    }
    e
  }

  private def quoted(q: Char): String = {
    i += 1
    val sb = new StringBuilder
    while (peek != q) {
      if (i >= text.length) { throw new IllegalArgumentException("unterminated literal") }
      if (peek == '\\') {
        i += 1
        sb += (peek match { case 'n' => '\n'; case 't' => '\t'; case c => c })
      } else {
        sb += peek
      }
      i += 1
    }
    i += 1
    sb.toString
  }

  private def primary(): Expr = {
    skip()
    peek match {
      case '(' =>
        i += 1
        val e = choice()
        skip()
        if (peek != ')') { throw new IllegalArgumentException(s"`)` expected at $i") }
        i += 1
        e
      case '.' => i += 1; Expr.AnyChar
      case '"' => val s = quoted('"'); if (s.isEmpty) { Expr.empty } else { Expr.Lit(s) }
      case '\'' => val s = quoted('\''); if (s.isEmpty) { Expr.empty } else { Expr.Lit(s) }
      case '[' =>
        i += 1
        val start = i
        while (peek != ']') { i += 1 }
        val cs = text.substring(start, i)
        i += 1
        Expr.Cls(cs)
      case _ => Expr.Ref(ident())
    }
  }
}
