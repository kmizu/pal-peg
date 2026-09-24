package research

import scala.collection.mutable


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

  /** expressions are shared DAGs after fusion: the hash is cached so that hashing and memoisation stay linear */
  sealed trait Hashed extends Product { override val hashCode: Int = scala.util.hashing.MurmurHash3.productHash(this) }
  sealed trait E extends Hashed
  object E {
    case object Null extends E
    final case class Reg(r: String) extends E
    final case class Fld(e: E, f: Int) extends E          // f = 1 (input cell), 2, 3, 4 (machine cells)
    case object In extends E                               // this tick's letter cell
    case object Sep extends E                              // this tick's separator cell
    case object Made extends E                             // the cell allocated by this unit (resolved by Prog to the branch's last allocation)
    final case class MadeAt(site: Int) extends E           // the cell allocated at site `site` (0-based) of this unit
    final case class Ite(c: C, a: E, b: E) extends E
  }
  sealed trait C extends Hashed
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
  sealed trait TE extends Hashed
  object TE {
    final case class Const(t: Int) extends TE
    final case class Ite(c: C, a: TE, b: TE) extends TE
  }
  final case class Cell(tag: TE, p1: E, p2: E, p3: E, p4: E)
  /** New values for some registers and bits and an optional allocation; the rest is kept. */
  /** `allocs`: the guarded allocations the cell was merged from (first guard wins), when built by `Prog` */
  final case class Step(regs: Map[String, E], bits: Map[String, C], cell: Option[Cell], allocs: Vector[(C, Cell)] = Vector())
  final case class Machine(
    alphabet: String,
    regs: Vector[(String, Kind)],
    bits: Vector[String],
    initTrue: Set[String],
    accept: String,
    step: Step)

  /** the allocation sites of a unit merged into one cell (first guard wins); the merged view is used by the
    * layered compiler and by hand-built steps */
  def mergeCell(allocs: Vector[(C, Cell)]): Option[Cell] = if (allocs.isEmpty) { None } else {
    def fold(f: Cell => E): E = allocs.foldRight(E.Null: E) { case ((g, c), acc) => E.Ite(g, f(c), acc) }
    // the default is the last allocation's tag: a site is only read when its guard fired, so the fallback is dead
    def foldTag: TE = allocs.init.foldRight(allocs.last._2.tag) { case ((g, c), acc) => TE.Ite(g, c.tag, acc) }
    Some(Cell(foldTag, fold(_.p1), fold(_.p2), fold(_.p3), fold(_.p4)))
  }

  /** substitution of registers, bits and allocation sites (`made(-1)` resolves a plain `Made`) */
  final class Subst(regs: String => Option[E], bits: String => Option[C], made: Int => E) {
    private val memoE = mutable.HashMap.empty[E, E]
    private val memoC = mutable.HashMap.empty[C, C]
    def e(x: E): E = memoE.getOrElseUpdate(x, e0(x))
    def c(x: C): C = memoC.getOrElseUpdate(x, c0(x))
    private def e0(x: E): E = x match {
      case E.Null | E.In | E.Sep => x
      case E.Reg(r) => regs(r).getOrElse(x)
      case E.Fld(y, f) => E.Fld(e(y), f)
      case E.Made => made(-1)
      case E.MadeAt(i) => made(i)
      case E.Ite(g, a, b) => E.Ite(c(g), e(a), e(b))
    }
    private def c0(x: C): C = x match {
      case C.True | C.False | C.First => x
      case C.Not(y) => C.Not(c(y))
      case C.And(a, b) => C.And(c(a), c(b))
      case C.Or(a, b) => C.Or(c(a), c(b))
      case C.IsNull(y) => C.IsNull(e(y))
      case C.TagIs(y, t) => C.TagIs(e(y), t)
      case C.SameTag(a, b) => C.SameTag(e(a), e(b))
      case C.Bit(b) => bits(b).getOrElse(x)
    }
    def te(x: TE): TE = x match { case TE.Const(_) => x; case TE.Ite(g, a, b) => TE.Ite(c(g), te(a), te(b)) }
  }

  /** the sites of a step (a hand-built step with a merged cell is one site) */
  def sitesOf(step: Step): Vector[(C, Cell)] = if (step.allocs.nonEmpty) { step.allocs } else { step.cell.map(c => (C.True, c)).toVector }

  /** sequential composition: `b` after `a` in the same unit (b reads a's updates; a's sites come first) */
  def fuse(a: Step, b: Step): Step = {
    val sa = sitesOf(a)
    val s = new Subst(a.regs.get, a.bits.get, i => E.MadeAt((if (i < 0) { 0 } else { i }) + sa.size))
    val regs = a.regs ++ b.regs.map { case (r, x) => r -> s.e(x) }
    val bits = a.bits ++ b.bits.map { case (x, y) => x -> s.c(y) }
    val allocs = sa ++ sitesOf(b).map { case (g, cl) => (s.c(g), Cell(s.te(cl.tag), s.e(cl.p1), s.e(cl.p2), s.e(cl.p3), s.e(cl.p4))) }
    Step(regs, bits, mergeCell(allocs), allocs)
  }

  /** Guarded parallel updates; the first guard that holds wins. */
  final class Prog(phaseBits: Int, subBits: Int, px: String = "", exclusive: Boolean = false) {
    private val regUpd = mutable.HashMap.empty[String, Vector[(C, E)]]
    private var seen = Vector.empty[C]
    private val bitUpd = mutable.HashMap.empty[String, Vector[(C, C)]]
    private var cellUpd = Vector.empty[(C, Cell)]
    final class Upd(g: C) {
      private var last = -1
      private def fix: Subst = new Subst(_ => None, _ => None, i => if (i < 0) { require(last >= 0, "Made before any alloc"); E.MadeAt(last) } else { E.MadeAt(i) })
      def set(r: String, v: E): Unit = { regUpd(r) = regUpd.getOrElse(r, Vector()) :+ ((g, fix.e(v))) }
      def bit(b: String, v: C): Unit = { bitUpd(b) = bitUpd.getOrElse(b, Vector()) :+ ((g, fix.c(v))) }
      /** a new allocation site of this unit; returns the pointer to it */
      def alloc(tag: TE, p1: E = E.Null, p2: E = E.Null, p3: E = E.Null, p4: E = E.Null): E = {
        val i = cellUpd.size
        cellUpd = cellUpd :+ ((g, Cell(fix.te(tag), fix.e(p1), fix.e(p2), fix.e(p3), fix.e(p4))))
        last = i
        E.MadeAt(i)
      }
      def alloc(tag: Int, p1: E, p2: E, p3: E, p4: E): E = alloc(TE.Const(tag), p1, p2, p3, p4)
      def phase(k: Int): Unit = { for (i <- 0 until phaseBits) { bit(s"${px}p$i", if (((k >> i) & 1) == 1) { C.True } else { C.False }) }; sub(0) }
      def sub(k: Int): Unit = { for (i <- 0 until subBits) { bit(s"${px}s$i", if (((k >> i) & 1) == 1) { C.True } else { C.False }) } }
    }
    /** the guard wins per register/bit: the first `when` in order that holds and updates it. With `exclusive`,
      * every guard is conjoined with the negation of all earlier guards, so at most one branch acts. */
    /** an unconditional allocation site shared by the branches (its fields select by the branch guards) */
    def site(tag: TE, p1: E = E.Null, p2: E = E.Null, p3: E = E.Null, p4: E = E.Null): E = {
      val i = cellUpd.size
      cellUpd = cellUpd :+ ((C.True, Cell(tag, p1, p2, p3, p4)))
      E.MadeAt(i)
    }
    def when(g: C)(f: Upd => Unit): Unit = {
      val g2 = if (exclusive && seen.nonEmpty) { C.all((seen.map(C.Not.apply) :+ g)*) } else { g }
      seen = seen :+ g
      f(new Upd(g2))
    }
    def build(regs: Vector[String], bits: Vector[String]): Step = {
      val rs = regs.flatMap { r => regUpd.get(r).map { us => r -> us.foldRight(E.Reg(r): E) { case ((g, v), acc) => E.Ite(g, v, acc) } } }.toMap
      val bs = bits.flatMap { b => bitUpd.get(b).map { us => b -> us.foldRight(C.Bit(b): C) { case ((g, v), acc) => Ite2(g, v, acc) } } }.toMap
      Step(rs, bs, mergeCell(cellUpd), cellUpd)
    }
    private def Ite2(g: C, a: C, b: C): C = C.Or(C.And(g, a), C.And(C.Not(g), b))
  }


  // ---- interpreter ------------------------------------------------------------------------

  final class RCell(val tag: Int, val p1: RCell, val p2: RCell, val p3: RCell, val p4: RCell, val layer: Int) {
    def fld(f: Int): RCell = f match { case 1 => p1; case 2 => p2; case 3 => p3; case _ => p4 }
  }
  final case class State(regs: Map[String, RCell], bits: Set[String])

  private def evalTE(t: TE, ctx: Ctx): Int = t match {
    case TE.Const(k) => k
    case TE.Ite(c, a, b) => if (evalC(c, ctx)) { evalTE(a, ctx) } else { evalTE(b, ctx) }
  }
  private final case class Ctx(st: State, in: RCell, sep: RCell, made: Vector[RCell], first: Boolean) {
    val memoE = mutable.HashMap.empty[E, RCell]
    val memoC = mutable.HashMap.empty[C, Boolean]
  }
  private def evalE(e: E, ctx: Ctx): RCell = e match {
    case E.Fld(_, _) | E.Ite(_, _, _) => ctx.memoE.getOrElseUpdate(e, evalE0(e, ctx))
    case _ => evalE0(e, ctx)
  }
  private def evalE0(e: E, ctx: Ctx): RCell = e match {
    case E.Null => null
    case E.Reg(r) => ctx.st.regs.getOrElse(r, null)
    case E.Fld(x, f) => val c = evalE(x, ctx); if (c == null) { null } else { c.fld(f) }
    case E.In => ctx.in
    case E.Sep => ctx.sep
    case E.Made => ctx.made(0)
    case E.MadeAt(i) => ctx.made(i)
    case E.Ite(c, a, b) => if (evalC(c, ctx)) { evalE(a, ctx) } else { evalE(b, ctx) }
  }
  private def evalC(c: C, ctx: Ctx): Boolean = c match {
    case C.And(_, _) | C.Or(_, _) | C.Not(_) | C.IsNull(_) | C.TagIs(_, _) | C.SameTag(_, _) => ctx.memoC.getOrElseUpdate(c, evalC0(c, ctx))
    case _ => evalC0(c, ctx)
  }
  private def evalC0(c: C, ctx: Ctx): Boolean = c match {
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

  /** One unit of `step` over the registers `regs` and bits `bits`. */
  def unitStep(step: Step, regs: Vector[String], bits: Vector[String], st: State, in: RCell, sep: RCell, first: Boolean, layer: Int): State = {
    // the sites are created in order; a site's fields may refer to the earlier sites of the same unit
    var made = Vector.empty[RCell]
    for ((_, cl) <- sitesOf(step)) {
      val pre = Ctx(st, in, sep, made, first)
      made = made :+ new RCell(evalTE(cl.tag, pre), evalE(cl.p1, pre), evalE(cl.p2, pre), evalE(cl.p3, pre), evalE(cl.p4, pre), layer)
    }
    val ctx = Ctx(st, in, sep, made, first)
    val rs = regs.map { r => r -> (step.regs.get(r) match { case Some(e) => evalE(e, ctx); case None => st.regs.getOrElse(r, null) }) }.toMap
    val bs = bits.filter { b => step.bits.get(b) match { case Some(c) => evalC(c, ctx); case None => st.bits(b) } }.toSet
    State(rs, bs)
  }

  /** One unit. Returns the new state; `cells` counts allocations. */
  def unit(m: Machine, st: State, in: RCell, sep: RCell, first: Boolean, layer: Int): State = {
    unitStep(m.step, m.regs.map(_._1), m.bits, st, in, sep, first, layer)
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
    private final case class P(ext: Expr, isLetter: Expr, isSep: Expr, layer: Int => Expr, kinds: Set[Kind], bits: Option[Vector[Expr]] = None) {
      /** bit k of the layer index, as an expression */
      def bitRef(k: Int): Expr = bits.map(_(k)).getOrElse(FalseE)
    }

    private def regP(r: String, t: Int): P = kindOf(r) match {
      case Inp => P(Ref(s"${r}_$t"), Ref(s"${r}_${t}_kL"), Not(Ref(s"${r}_${t}_kL")), _ => FalseE, Set(Inp))
      case Mach => P(Ref(s"${r}_$t"), FalseE, FalseE, l => layerTest(s"${r}_$t", l), Set(Mach), Some((0 until layerBits).map(k => Ref(bitName(s"${r}_$t", k))).toVector))
    }
    private def layerTest(x: String, l: Int): Expr =
      and((0 until layerBits).map { k => if (((l >> k) & 1) == 1) { Ref(bitName(x, k)) } else { Not(Ref(bitName(x, k))) } }*)

    private def fieldOfCell(t: Int, f: Int): P =
      f match {
        case 1 => P(Ref(s"C_${t}_p1"), Ref(s"C_${t}_p1_kL"), Not(Ref(s"C_${t}_p1_kL")), _ => FalseE, Set(Inp))
        case _ => P(Ref(s"C_${t}_p$f"), FalseE, FalseE, l => layerTest(s"C_${t}_p$f", l), Set(Mach), Some((0 until layerBits).map(k => Ref(bitName(s"C_${t}_p$f", k))).toVector))
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
          rules += ((bitName(x, k), p.bits.map(_(k)).getOrElse { val ls = (1 to budget).filter { l => ((l >> k) & 1) == 1 }; or(ls.map(p.layer)*) }))
        }
      }
      P(Ref(x),
        if (hasInp) { Ref(s"${x}_kL") } else { FalseE },
        if (hasInp) { Not(Ref(s"${x}_kL")) } else { FalseE },
        l => if (hasMach) { layerTest(x, l) } else { FalseE },
        p.kinds,
        if (hasMach) { Some((0 until layerBits).map(k => Ref(bitName(x, k))).toVector) } else { None })
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
      case E.Made | E.MadeAt(_) => P(Here, FalseE, FalseE, l => if (l == t) { TrueE } else { FalseE }, Set(Mach), Some((0 until layerBits).map(k => if (((t >> k) & 1) == 1) { TrueE } else { FalseE }).toVector))
      case E.Ite(c, a, b) =>
        val cc = compC(c, t)
        val pa = compE(a, t)
        val pb = compE(b, t)
        def sel(fa: Expr, fb: Expr): Expr = or(and(look(cc), fa), and(Not(cc), fb))
        P(sel(pa.ext, pb.ext), sel(pa.isLetter, pb.isLetter), sel(pa.isSep, pb.isSep), l => sel(pa.layer(l), pb.layer(l)), pa.kinds ++ pb.kinds,
          Some((0 until layerBits).map(k => sel(pa.bitRef(k), pb.bitRef(k))).toVector))
      case E.Fld(x, f) =>
        val px = compE(x, t)
        // alternatives by the kind of x; every component of the result dispatches once on x's kind
        val alts = Vector.newBuilder[(Expr, P)]   // (guard on x, field pointer relative to x's node)
        if (px.kinds(Inp) && f == 1) {
          alts += ((px.isLetter, P(and(AnyChar, Here), FalseE, TrueE, _ => FalseE, Set(Inp))))   // prev of a letter: the separator of the older node
          alts += ((px.isSep, P(Here, TrueE, FalseE, _ => FalseE, Set(Inp))))                     // prev of a separator: its own letter
        }
        if (px.kinds(Mach)) { for (l <- 1 to budget) { alts += ((px.layer(l), fieldOfCell(l, f))) } }
        val as = alts.result()
        def choose(g: P => Expr): Expr = or(as.map { case (guard, fp) => and(look(guard), px.ext, g(fp)) }*)
        val kinds: Set[Kind] = as.flatMap(_._2.kinds).toSet
        // the layer bits of the result: bit k holds iff the field's bit k holds at the target (one dispatch per bit)
        val bitExprs: Vector[Expr] = (0 until layerBits).map { k =>
          or(as.map { case (guard, fp) => and(look(guard), px.ext, look(fp.bitRef(k))) }*)
        }.toVector
        P(choose(_.ext), choose(p => look(p.isLetter)), choose(p => look(p.isSep)),
          l => and((0 until layerBits).map { k => if (((l >> k) & 1) == 1) { bitExprs(k) } else { Not(bitExprs(k)) } }*),
          kinds, Some(bitExprs))
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
            rules += ((bitName(name, k), p.bits.map(_(k)).getOrElse { val ls = (1 to budget).filter { l => ((l >> k) & 1) == 1 }; or(ls.map(p.layer)*) }))
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
