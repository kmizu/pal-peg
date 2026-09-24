package research


/** Layered pointer machines: the model in which a real-time recognizer is written before it is
  * compiled to a PEG.
  *
  * The machine reads one input letter per *tick* and runs the same `Step` (a set of parallel
  * register updates, one optional cell allocation) `budget` times per tick. Every tick has two
  * input cells (the letter, tag 0..k-1, and a separator, tag k) chained by `p1` toward the start of
  * the word. A machine cell has a tag (>= k), `p1` (an input cell or null) and `p2`, `p3` (machine
  * cells or null). Registers are declared as pointing to input cells (`Inp`) or machine cells
  * (`Mach`). Pointers only ever refer to cells that already exist, so the whole thing is a
  * scaffolding automaton and compiles to a PEG with one position per letter and one layer of
  * nonterminals per unit (`compile`).
  */
object Lpm {

  sealed trait Kind
  case object Inp extends Kind
  case object Mach extends Kind

  sealed trait E
  object E {
    case object Null extends E
    final case class Reg(r: String) extends E
    final case class Fld(e: E, f: Int) extends E          // f = 1 (input cell), 2, 3, 4 (machine cells)
    case object In extends E                               // this tick's letter cell
    case object Sep extends E                              // this tick's separator cell
    case object Made extends E                             // the cell allocated by this unit
    final case class Ite(c: C, a: E, b: E) extends E
  }
  sealed trait C
  object C {
    case object True extends C
    case object False extends C
    final case class Not(c: C) extends C
    final case class And(a: C, b: C) extends C
    final case class Or(a: C, b: C) extends C
    final case class IsNull(e: E) extends C
    final case class TagIs(e: E, tag: Int) extends C      // non-null and tagged `tag`
    final case class SameTag(a: E, b: E) extends C        // both non-null input cells with equal tags
    final case class Bit(b: String) extends C
    case object First extends C                            // the first unit of the tick
    def all(cs: C*): C = cs.reduceLeft(And.apply)
    def any(cs: C*): C = cs.reduceLeft(Or.apply)
  }
  sealed trait TE
  object TE {
    final case class Const(t: Int) extends TE
    final case class Ite(c: C, a: TE, b: TE) extends TE
  }
  final case class Cell(tag: TE, p1: E, p2: E, p3: E, p4: E)
  /** New values for some registers and bits and an optional allocation; the rest is kept. */
  final case class Step(regs: Map[String, E], bits: Map[String, C], cell: Option[Cell])
  final case class Machine(
    alphabet: String,
    regs: Vector[(String, Kind)],
    bits: Vector[String],
    initTrue: Set[String],
    accept: String,
    step: Step)

  // ---- interpreter ------------------------------------------------------------------------

  final class RCell(val tag: Int, val p1: RCell, val p2: RCell, val p3: RCell, val p4: RCell, val layer: Int) {
    def fld(f: Int): RCell = f match { case 1 => p1; case 2 => p2; case 3 => p3; case _ => p4 }
  }
  final case class State(regs: Map[String, RCell], bits: Set[String])

  private def evalTE(t: TE, ctx: Ctx): Int = t match {
    case TE.Const(k) => k
    case TE.Ite(c, a, b) => if (evalC(c, ctx)) { evalTE(a, ctx) } else { evalTE(b, ctx) }
  }
  private final case class Ctx(st: State, in: RCell, sep: RCell, made: RCell, first: Boolean)
  private def evalE(e: E, ctx: Ctx): RCell = e match {
    case E.Null => null
    case E.Reg(r) => ctx.st.regs.getOrElse(r, null)
    case E.Fld(x, f) => val c = evalE(x, ctx); if (c == null) { null } else { c.fld(f) }
    case E.In => ctx.in
    case E.Sep => ctx.sep
    case E.Made => ctx.made
    case E.Ite(c, a, b) => if (evalC(c, ctx)) { evalE(a, ctx) } else { evalE(b, ctx) }
  }
  private def evalC(c: C, ctx: Ctx): Boolean = c match {
    case C.True => true
    case C.False => false
    case C.Not(x) => !evalC(x, ctx)
    case C.And(a, b) => evalC(a, ctx) && evalC(b, ctx)
    case C.Or(a, b) => evalC(a, ctx) || evalC(b, ctx)
    case C.IsNull(e) => evalE(e, ctx) == null
    case C.TagIs(e, t) => val x = evalE(e, ctx); x != null && x.tag == t
    case C.SameTag(a, b) => val x = evalE(a, ctx); val y = evalE(b, ctx); x != null && y != null && x.tag == y.tag
    case C.Bit(b) => ctx.st.bits(b)
    case C.First => ctx.first
  }

  /** One unit. Returns the new state; `cells` counts allocations. */
  def unit(m: Machine, st: State, in: RCell, sep: RCell, first: Boolean, layer: Int): State = {
    val pre = Ctx(st, in, sep, null, first)
    val made = m.step.cell.map { cl =>
      // the cell's fields are computed from the old state (they may not refer to the cell itself)
      new RCell(evalTE(cl.tag, pre), evalE(cl.p1, pre), evalE(cl.p2, pre), evalE(cl.p3, pre), evalE(cl.p4, pre), layer)
    }.orNull
    val ctx = Ctx(st, in, sep, made, first)
    val regs = m.regs.map { case (r, _) => r -> (m.step.regs.get(r) match { case Some(e) => evalE(e, ctx); case None => st.regs.getOrElse(r, null) }) }.toMap
    val bits = m.bits.filter { b => m.step.bits.get(b) match { case Some(c) => evalC(c, ctx); case None => st.bits(b) } }.toSet
    State(regs, bits)
  }

  final class Run(val outputs: Vector[Boolean], val unitsNeeded: Vector[Int])

  /** Feed `w` reversed (the last letter first, as the PEG sees it), `budget` units per tick.
    * `unitsNeeded(t)` is the number of units of tick `t` before the state stopped changing (`budget`
    * if it never did). */
  def run(m: Machine, w: String, budget: Int): Run = {
    val sepTag = m.alphabet.length
    var st = State(Map.empty, m.initTrue)
    var prevSep: RCell = null
    val outs = Vector.newBuilder[Boolean]
    val needed = Vector.newBuilder[Int]
    for (i <- (w.length - 1) to 0 by -1) {
      val in = new RCell(m.alphabet.indexOf(w.charAt(i)), prevSep, null, null, null, -1)
      val sep = new RCell(sepTag, in, null, null, null, -2)
      var u = 0
      var last = 0
      var go = true
      while (go && u < budget) {
        val next = unit(m, st, in, sep, u == 0, u + 1)
        val same = next.bits == st.bits && m.regs.forall { case (r, _) => next.regs(r) eq st.regs.getOrElse(r, null) }
        u += 1
        if (!same) { last = u }
        st = next
        if (same && u > 1) { go = false }   // quiescent
      }
      needed += last
      outs += st.bits(m.accept)
      prevSep = sep
    }
    new Run(outs.result(), needed.result())
  }

  // ---- compiler ---------------------------------------------------------------------------

  /** PEG with one layer per unit. Pointer values are extents (null = failure); a register of kind
    * `Inp` also has a bit telling letter (true) from separator, a register of kind `Mach` has
    * `layerBits` bits naming the unit that allocated the cell. */
  final class Compiler(m: Machine, budget: Int) {
    import Expr.*
    private val rules = Vector.newBuilder[(String, Expr)]
    private val memoE = scala.collection.mutable.HashMap.empty[(E, Int), P]
    private val memoC = scala.collection.mutable.HashMap.empty[(C, Int), Expr]
    private var fresh = 0
    private val layerBits: Int = { var b = 1; while ((1 << b) <= budget) { b += 1 }; b }
    private val sepTag = m.alphabet.length
    private val kindOf: Map[String, Kind] = m.regs.toMap
    private val TrueE: Expr = Seq(Vector())
    private val FalseE: Expr = Not(TrueE)
    private def and(xs: Expr*): Expr = Seq(xs.toVector)
    private def or(xs: Expr*): Expr = Choice(xs.toVector)
    private def look(e: Expr): Expr = And(e)
    private val Here: Expr = And(AnyChar)                  // extent 0 at an existing node

    private def bitName(x: String, k: Int) = s"${x}_y$k"

    /** A compiled pointer: its extent and the kind information. */
    private final case class P(ext: Expr, isLetter: Expr, isSep: Expr, layer: Int => Expr, kinds: Set[Kind])

    private def regP(r: String, t: Int): P = kindOf(r) match {
      case Inp => P(Ref(s"${r}_$t"), Ref(s"${r}_${t}_kL"), Not(Ref(s"${r}_${t}_kL")), _ => FalseE, Set(Inp))
      case Mach => P(Ref(s"${r}_$t"), FalseE, FalseE, l => layerTest(s"${r}_$t", l), Set(Mach))
    }
    private def layerTest(x: String, l: Int): Expr =
      and((0 until layerBits).map { k => if (((l >> k) & 1) == 1) { Ref(bitName(x, k)) } else { Not(Ref(bitName(x, k))) } }*)

    private def fieldOfCell(t: Int, f: Int): P =
      f match {
        case 1 => P(Ref(s"C_${t}_p1"), Ref(s"C_${t}_p1_kL"), Not(Ref(s"C_${t}_p1_kL")), _ => FalseE, Set(Inp))
        case _ => P(Ref(s"C_${t}_p$f"), FalseE, FalseE, l => layerTest(s"C_${t}_p$f", l), Set(Mach))
      }

    /** Emit the pointer as auxiliary rules and return references to them (sharing). */
    private def named(p: P, t: Int): P = {
      fresh += 1
      val x = s"X${fresh}_$t"
      rules += ((x, p.ext))
      val hasInp = p.kinds(Inp)
      val hasMach = p.kinds(Mach)
      if (hasInp) { rules += ((s"${x}_kL", p.isLetter)) }
      if (hasMach) {
        for (k <- 0 until layerBits) {
          val ls = (1 to budget).filter { l => ((l >> k) & 1) == 1 }
          rules += ((bitName(x, k), or(ls.map(p.layer)*)))
        }
      }
      P(Ref(x),
        if (hasInp) { Ref(s"${x}_kL") } else { FalseE },
        if (hasInp) { Not(Ref(s"${x}_kL")) } else { FalseE },
        l => if (hasMach) { layerTest(x, l) } else { FalseE },
        p.kinds)
    }

    private def compE(e: E, t: Int): P = e match {
      case E.Fld(_, _) | E.Ite(_, _, _) => memoE.getOrElseUpdate((e, t), named(compE0(e, t), t))
      case _ => compE0(e, t)
    }

    private def compE0(e: E, t: Int): P = e match {
      case E.Null => P(FalseE, FalseE, FalseE, _ => FalseE, Set())
      case E.Reg(r) => regP(r, t - 1)                   // expressions read the previous layer
      case E.In => P(Here, TrueE, FalseE, _ => FalseE, Set(Inp))
      case E.Sep => P(Here, FalseE, TrueE, _ => FalseE, Set(Inp))
      case E.Made => P(Here, FalseE, FalseE, l => if (l == t) { TrueE } else { FalseE }, Set(Mach))
      case E.Ite(c, a, b) =>
        val cc = compC(c, t)
        val pa = compE(a, t)
        val pb = compE(b, t)
        def sel(fa: Expr, fb: Expr): Expr = or(and(look(cc), fa), and(Not(cc), fb))
        P(sel(pa.ext, pb.ext), sel(pa.isLetter, pb.isLetter), sel(pa.isSep, pb.isSep), l => sel(pa.layer(l), pb.layer(l)), pa.kinds ++ pb.kinds)
      case E.Fld(x, f) =>
        val px = compE(x, t)
        // alternatives by the kind of x
        val alts = Vector.newBuilder[(Expr, P)]   // (guard on x, field pointer relative to x's node)
        if (px.kinds(Inp) && f == 1) {
          alts += ((px.isLetter, P(and(AnyChar, Here), FalseE, TrueE, _ => FalseE, Set(Inp))))   // prev of a letter: the separator of the older node
          alts += ((px.isSep, P(Here, TrueE, FalseE, _ => FalseE, Set(Inp))))                     // prev of a separator: its own letter
        }
        if (px.kinds(Mach)) { for (l <- 1 to budget) { alts += ((px.layer(l), fieldOfCell(l, f))) } }
        val as = alts.result()
        def choose(g: P => Expr): Expr = or(as.map { case (guard, fp) => and(look(guard), px.ext, g(fp)) }*)
        val kinds: Set[Kind] = as.flatMap(_._2.kinds).toSet
        P(choose(_.ext), choose(p => look(p.isLetter)), choose(p => look(p.isSep)), l => choose(p => look(p.layer(l))), kinds)
    }

    private def compC(c: C, t: Int): Expr = c match {
      case C.And(_, _) | C.Or(_, _) | C.IsNull(_) | C.TagIs(_, _) | C.SameTag(_, _) =>
        memoC.getOrElseUpdate((c, t), { val x = compC0(c, t); fresh += 1; val n = s"K${fresh}_$t"; rules += ((n, x)); Ref(n) })
      case _ => compC0(c, t)
    }

    private def compC0(c: C, t: Int): Expr = c match {
      case C.True => TrueE
      case C.False => FalseE
      case C.Not(x) => Not(compC(x, t))
      case C.And(a, b) => and(compC(a, t), compC(b, t))
      case C.Or(a, b) => or(compC(a, t), compC(b, t))
      case C.IsNull(e) => Not(compE(e, t).ext)
      case C.TagIs(e, tag) =>
        val p = compE(e, t)
        if (tag < sepTag) { and(look(p.isLetter), look(and(p.ext, Lit(m.alphabet.charAt(tag).toString)))) }
        else if (tag == sepTag) { and(look(p.isSep), look(p.ext)) }
        else { or((1 to budget).map { l => and(look(p.layer(l)), look(and(p.ext, tagTest(l, tag)))) }*) }
      case C.SameTag(a, b) =>
        val pa = compE(a, t)
        val pb = compE(b, t)
        val letters = m.alphabet.map { ch => and(look(pa.isLetter), look(pb.isLetter), look(and(pa.ext, Lit(ch.toString))), look(and(pb.ext, Lit(ch.toString)))) }
        or((letters :+ and(look(pa.isSep), look(pb.isSep), look(pa.ext), look(pb.ext)))*)
      case C.Bit(b) => Ref(s"${b}_${t - 1}")
      case C.First => if (t == 1) { TrueE } else { FalseE }
    }

    private val tagBits = 4
    private def tagTest(l: Int, tag: Int): Expr =
      and((0 until tagBits).map { k => if (((tag >> k) & 1) == 1) { Ref(s"C_${l}_g$k") } else { Not(Ref(s"C_${l}_g$k")) } }*)
    private def compTE(te: TE, t: Int, k: Int): Expr = te match {
      case TE.Const(v) => if (((v >> k) & 1) == 1) { TrueE } else { FalseE }
      case TE.Ite(c, a, b) => val cc = compC(c, t); or(and(look(cc), compTE(a, t, k)), and(Not(cc), compTE(b, t, k)))
    }

    private def emitPointer(name: String, p: P, kind: Kind): Unit = {
      rules += ((name, p.ext))
      kind match {
        case Inp => rules += ((s"${name}_kL", p.isLetter))
        case Mach =>
          // layer bits: bit k of the layer that allocated the target
          for (k <- 0 until layerBits) {
            val ls = (1 to budget).filter { l => ((l >> k) & 1) == 1 }
            rules += ((bitName(name, k), or(ls.map(p.layer)*)))
          }
      }
    }

    def grammar(): Grammar = {
      val B = budget
      // start
      val empty: Expr = if (m.initTrue(m.accept)) { Not(AnyChar) } else { FalseE }
      rules += (("S", or(empty, and(look(Ref(s"${m.accept}_$B")), Star(AnyChar)))))
      // layer 0: the previous node's final layer
      for ((r, kind) <- m.regs) {
        rules += ((s"${r}_0", and(AnyChar, Ref(s"${r}_$B"))))
        kind match {
          case Inp => rules += ((s"${r}_0_kL", look(and(AnyChar, Ref(s"${r}_${B}_kL")))))
          case Mach => for (k <- 0 until layerBits) { rules += ((bitName(s"${r}_0", k), look(and(AnyChar, Ref(bitName(s"${r}_$B", k)))))) }
        }
      }
      for (b <- m.bits) {
        val carried = look(and(AnyChar, Ref(s"${b}_$B")))
        rules += ((s"${b}_0", if (m.initTrue(b)) { or(carried, look(and(AnyChar, Not(AnyChar)))) } else { carried }))
      }
      // layers 1..B
      for (t <- 1 to B) {
        m.step.cell.foreach { cl =>
          for (k <- 0 until tagBits) { rules += ((s"C_${t}_g$k", compTE(cl.tag, t, k))) }
          emitPointer(s"C_${t}_p1", compE(cl.p1, t), Inp)
          emitPointer(s"C_${t}_p2", compE(cl.p2, t), Mach)
          emitPointer(s"C_${t}_p3", compE(cl.p3, t), Mach)
          emitPointer(s"C_${t}_p4", compE(cl.p4, t), Mach)
        }
        for ((r, kind) <- m.regs) {
          m.step.regs.get(r) match {
            case Some(e) => emitPointer(s"${r}_$t", compE(e, t), kind)
            case None => emitPointer(s"${r}_$t", regP(r, t - 1), kind)
          }
        }
        for (b <- m.bits) {
          rules += ((s"${b}_$t", m.step.bits.get(b).map(compC(_, t)).getOrElse(Ref(s"${b}_${t - 1}"))))
        }
      }
      new Grammar(rules.result())
    }
  }

  def compile(m: Machine, budget: Int): Grammar = new Compiler(m, budget).grammar()

  /** Exhaustive comparison of the interpreter, the compiled PEG and an oracle. */
  def check(m: Machine, budget: Int, maxLen: Int, oracle: String => Boolean): Option[String] = {
    val g = compile(m, budget)
    val it = Exhaust.words(maxLen, m.alphabet)
    var bad: Option[String] = None
    while (bad.isEmpty && it.hasNext) {
      val w = it.next()
      val want = oracle(w)
      val sim = if (w.isEmpty) { m.initTrue(m.accept) } else { run(m, w, budget).outputs.last }
      val peg = g.accepts(w)
      if (sim != want) { bad = Some(s"interpreter disagrees on '$w': got $sim, want $want") }
      else if (peg != want) { bad = Some(s"PEG disagrees on '$w': got $peg, want $want") }
    }
    bad
  }
}
