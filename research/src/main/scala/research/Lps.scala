package research

import scala.collection.mutable

/** Slot-scheduled pointer machines: the same cells, registers and expressions as `Lpm`, but every
  * tick runs a fixed *schedule* of slots, and each slot has its own small step. A layer only emits
  * rules for the registers its slot writes; every other register is referred to by the name of its
  * last writer. Cells of a given tag are allocated only by the slots that name that tag, so a
  * dereference dispatches only over those layers, and a register that can only point to cells of
  * one layer needs no layer bits at all.
  */
object Lps {
  import Lpm.{E, C, TE, Step, RCell, State, unitStep}
  import Lpm.E.*
  import Expr.*

  sealed trait K
  case object KInp extends K
  final case class KTag(tag: Int) extends K

  final case class Machine(
    alphabet: String,
    regs: Vector[(String, Set[K])],
    bits: Vector[String],
    initTrue: Set[String],
    accept: String,
    schema: Map[Int, Vector[Set[K]]],       // tag -> kinds of p1, p2, p3, p4
    schedule: Vector[String],
    slots: Map[String, Step]) {
    val budget: Int = schedule.length
    def slotAt(t: Int): Step = slots(schedule(t - 1))
    /** tags a step may allocate */
    def tagsOf(step: Step): Set[Int] = step.cell.map(c => tagsOfTE(c.tag)).getOrElse(Set())
    private def tagsOfTE(te: TE): Set[Int] = te match { case TE.Const(t) => Set(t); case TE.Ite(_, a, b) => tagsOfTE(a) ++ tagsOfTE(b) }
    val layersOfTag: Map[Int, Vector[Int]] = {
      val m = mutable.HashMap.empty[Int, Vector[Int]]
      for (t <- 1 to budget; tag <- tagsOf(slotAt(t))) { m(tag) = m.getOrElse(tag, Vector()) :+ t }
      m.toMap
    }
    /** the layers whose cells a value of these kinds may point to */
    def layersOf(kinds: Set[K]): Vector[Int] = kinds.collect { case KTag(t) => layersOfTag.getOrElse(t, Vector()) }.flatten.toVector.distinct.sorted
    def fieldKinds(tag: Int, f: Int): Set[K] = schema.getOrElse(tag, Vector.fill(4)(Set.empty[K]))(f - 1)
  }

  final class Run(val outputs: Vector[Boolean])

  def run(m: Machine, w: String): Run = {
    val regNames = m.regs.map(_._1)
    var st = State(Map.empty, m.initTrue)
    var prevSep: RCell = null
    val outs = Vector.newBuilder[Boolean]
    for (i <- (w.length - 1) to 0 by -1) {
      val in = new RCell(m.alphabet.indexOf(w.charAt(i)), prevSep, null, null, null, -1)
      val sep = new RCell(m.alphabet.length, in, null, null, null, -2)
      for (t <- 1 to m.budget) { st = unitStep(m.slotAt(t), regNames, m.bits, st, in, sep, t == 1, t) }
      outs += st.bits(m.accept)
      prevSep = sep
    }
    new Run(outs.result())
  }

  // ---- compiler ------------------------------------------------------------------------------

  final class Compiler(m: Machine) {
    private val B = m.budget
    private val rules = Vector.newBuilder[(String, Expr)]
    private val memoE = mutable.HashMap.empty[(E, Int), P]
    private val memoC = mutable.HashMap.empty[(C, Int), Expr]
    private var fresh = 0
    private val layerBits: Int = { var b = 1; while ((1 << b) <= B) { b += 1 }; b }
    private val sepTag = m.alphabet.length
    private val regKinds: Map[String, Set[K]] = m.regs.toMap
    private val TrueE: Expr = Seq(Vector())
    private val FalseE: Expr = Not(TrueE)
    private def and(xs: Expr*): Expr = Seq(xs.toVector)
    private def or(xs: Expr*): Expr = if (xs.isEmpty) { FalseE } else { Choice(xs.toVector) }
    private def look(e: Expr): Expr = And(e)
    private val Here: Expr = And(AnyChar)
    private def bitName(x: String, k: Int) = s"${x}_y$k"
    private def constBits(l: Int): Vector[Expr] = (0 until layerBits).map(k => if (((l >> k) & 1) == 1) { TrueE } else { FalseE }).toVector

    /** A compiled pointer: extent, letter/separator tests, layer bits, and the static kind set. */
    private final case class P(ext: Expr, isLetter: Expr, isSep: Expr, bits: Vector[Expr], kinds: Set[K]) {
      def layers: Vector[Int] = m.layersOf(kinds)
      def layer(l: Int): Expr =
        if (layers == Vector(l)) { TrueE }
        else if (!layers.contains(l)) { FalseE }
        else { and((0 until layerBits).map { k => if (((l >> k) & 1) == 1) { bits(k) } else { Not(bits(k)) } }*) }
    }

    /** last layer (< t, or 0) that writes register/bit x, as a name */
    private def defAt(x: String, t: Int, writes: Int => Boolean): String = {
      var l = t
      while (l > 0 && !writes(l)) { l -= 1 }
      s"${x}_$l"
    }
    private def regWrites(r: String)(l: Int): Boolean = m.slotAt(l).regs.contains(r)
    private def bitWrites(b: String)(l: Int): Boolean = m.slotAt(l).bits.contains(b)
    private def regName(r: String, t: Int): String = defAt(r, t, regWrites(r))
    private def bitRef(b: String, t: Int): Expr = Ref(defAt(b, t, bitWrites(b)))

    private def regP(r: String, t: Int): P = {
      val kinds = regKinds(r)
      val name = regName(r, t)
      val ls = m.layersOf(kinds)
      val bits = if (ls.size >= 2) { (0 until layerBits).map(k => Ref(bitName(name, k))).toVector } else { constBits(ls.headOption.getOrElse(0)) }
      P(Ref(name), if (kinds(KInp)) { Ref(s"${name}_kL") } else { FalseE }, if (kinds(KInp)) { Not(Ref(s"${name}_kL")) } else { FalseE }, bits, kinds)
    }

    private def cellFieldP(l: Int, f: Int): P = {
      val kinds: Set[K] = m.tagsOf(m.slotAt(l)).flatMap(tag => m.fieldKinds(tag, f))
      val name = s"C_${l}_p$f"
      val ls = m.layersOf(kinds)
      val bits = if (ls.size >= 2) { (0 until layerBits).map(k => Ref(bitName(name, k))).toVector } else { constBits(ls.headOption.getOrElse(0)) }
      P(Ref(name), if (kinds(KInp)) { Ref(s"${name}_kL") } else { FalseE }, if (kinds(KInp)) { Not(Ref(s"${name}_kL")) } else { FalseE }, bits, kinds)
    }

    private def named(p: P, t: Int): P = {
      fresh += 1
      val x = s"X${fresh}_$t"
      rules += ((x, p.ext))
      val hasInp = p.kinds(KInp)
      if (hasInp) { rules += ((s"${x}_kL", p.isLetter)) }
      val ls = p.layers
      val bits = if (ls.size >= 2) {
        for (k <- 0 until layerBits) { rules += ((bitName(x, k), p.bits(k))) }
        (0 until layerBits).map(k => Ref(bitName(x, k))).toVector
      } else { constBits(ls.headOption.getOrElse(0)) }
      P(Ref(x), if (hasInp) { Ref(s"${x}_kL") } else { FalseE }, if (hasInp) { Not(Ref(s"${x}_kL")) } else { FalseE }, bits, p.kinds)
    }

    private def compE(e: E, t: Int): P = e match {
      case Fld(_, _) | Ite(_, _, _) => memoE.getOrElseUpdate((e, t), named(compE0(e, t), t))
      case _ => compE0(e, t)
    }

    private def compE0(e: E, t: Int): P = e match {
      case Null => P(FalseE, FalseE, FalseE, constBits(0), Set())
      case Reg(r) => regP(r, t - 1)
      case In => P(Here, TrueE, FalseE, constBits(0), Set(KInp))
      case Sep => P(Here, FalseE, TrueE, constBits(0), Set(KInp))
      case Made => P(Here, FalseE, FalseE, constBits(t), m.tagsOf(m.slotAt(t)).map(KTag.apply))
      case Ite(c, a, b) =>
        val cc = compC(c, t)
        val pa = compE(a, t)
        val pb = compE(b, t)
        def sel(fa: Expr, fb: Expr): Expr = or(and(look(cc), fa), and(Not(cc), fb))
        P(sel(pa.ext, pb.ext), sel(pa.isLetter, pb.isLetter), sel(pa.isSep, pb.isSep), (0 until layerBits).map(k => sel(pa.bits(k), pb.bits(k))).toVector, pa.kinds ++ pb.kinds)
      case Fld(x, f) =>
        val px = compE(x, t)
        val alts = Vector.newBuilder[(Expr, P)]
        if (px.kinds(KInp) && f == 1) {
          alts += ((px.isLetter, P(and(AnyChar, Here), FalseE, TrueE, constBits(0), Set(KInp))))
          alts += ((px.isSep, P(Here, TrueE, FalseE, constBits(0), Set(KInp))))
        }
        for (l <- px.layers) { alts += ((px.layer(l), cellFieldP(l, f))) }
        val as = alts.result()
        def choose(g: P => Expr): Expr = or(as.map { case (guard, fp) => and(look(guard), px.ext, g(fp)) }*)
        // predicates (letter/separator test, layer bits) must be zero-width: look past the extent of x
        def pred(g: P => Expr): Expr = look(choose(p => look(g(p))))
        val kinds: Set[K] = as.flatMap(_._2.kinds).toSet
        val bits = (0 until layerBits).map { k => pred(_.bits(k)) }.toVector
        P(choose(_.ext), pred(_.isLetter), pred(_.isSep), bits, kinds)
    }

    private def compC(c: C, t: Int): Expr = c match {
      case C.And(_, _) | C.Or(_, _) | C.IsNull(_) | C.TagIs(_, _) | C.SameTag(_, _) =>
        memoC.getOrElseUpdate((c, t), { val x = compC0(c, t); fresh += 1; val n = s"K${fresh}_$t"; rules += ((n, x)); Ref(n) })
      case _ => compC0(c, t)
    }

    private val tagBits = 4
    private def tagTest(l: Int, tag: Int): Expr = {
      val tags = m.tagsOf(m.slotAt(l))
      if (!tags(tag)) { FalseE }
      else if (tags.size == 1) { TrueE }
      else { and((0 until tagBits).map { k => if (((tag >> k) & 1) == 1) { Ref(s"C_${l}_g$k") } else { Not(Ref(s"C_${l}_g$k")) } }*) }
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
        else { or(p.layers.map { l => and(look(p.layer(l)), look(and(p.ext, tagTest(l, tag)))) }*) }
      case C.SameTag(a, b) =>
        val pa = compE(a, t)
        val pb = compE(b, t)
        val letters = m.alphabet.map { ch => and(look(pa.isLetter), look(pb.isLetter), look(and(pa.ext, Lit(ch.toString))), look(and(pb.ext, Lit(ch.toString)))) }
        or((letters :+ and(look(pa.isSep), look(pb.isSep), look(pa.ext), look(pb.ext)))*)
      case C.Bit(b) => bitRef(b, t - 1)
      case C.First => if (t == 1) { TrueE } else { FalseE }
    }

    private def compTE(te: TE, t: Int, k: Int): Expr = te match {
      case TE.Const(v) => if (((v >> k) & 1) == 1) { TrueE } else { FalseE }
      case TE.Ite(c, a, b) => val cc = compC(c, t); or(and(look(cc), compTE(a, t, k)), and(Not(cc), compTE(b, t, k)))
    }

    private def emitPointer(name: String, p: P, kinds: Set[K]): Unit = {
      rules += ((name, p.ext))
      if (kinds(KInp)) { rules += ((s"${name}_kL", p.isLetter)) }
      if (m.layersOf(kinds).size >= 2) { for (k <- 0 until layerBits) { rules += ((bitName(name, k), p.bits(k))) } }
    }

    def grammar(): Grammar = {
      val empty: Expr = if (m.initTrue(m.accept)) { Not(AnyChar) } else { FalseE }
      rules += (("S", or(empty, and(look(bitRef(m.accept, B)), Star(AnyChar)))))
      // layer 0: the previous node's last definitions
      for ((r, kinds) <- m.regs) {
        val last = regName(r, B)
        rules += ((s"${r}_0", and(AnyChar, Ref(last))))
        if (kinds(KInp)) { rules += ((s"${r}_0_kL", look(and(AnyChar, Ref(s"${last}_kL"))))) }
        if (m.layersOf(kinds).size >= 2) { for (k <- 0 until layerBits) { rules += ((bitName(s"${r}_0", k), look(and(AnyChar, Ref(bitName(last, k)))))) } }
      }
      for (b <- m.bits) {
        val carried = look(and(AnyChar, bitRef(b, B)))
        rules += ((s"${b}_0", if (m.initTrue(b)) { or(carried, look(and(AnyChar, Not(AnyChar)))) } else { carried }))
      }
      for (t <- 1 to B) {
        val step = m.slotAt(t)
        step.cell.foreach { cl =>
          val tags = m.tagsOf(step)
          if (tags.size >= 2) { for (k <- 0 until tagBits) { rules += ((s"C_${t}_g$k", compTE(cl.tag, t, k))) } }
          for (f <- 1 to 4) {
            val kinds: Set[K] = tags.flatMap(tag => m.fieldKinds(tag, f))
            val src = f match { case 1 => cl.p1; case 2 => cl.p2; case 3 => cl.p3; case _ => cl.p4 }
            if (kinds.nonEmpty) { emitPointer(s"C_${t}_p$f", compE(src, t), kinds) }
          }
        }
        for ((r, kinds) <- m.regs) { step.regs.get(r).foreach { e => emitPointer(s"${r}_$t", compE(e, t), kinds) } }
        for (b <- m.bits) { step.bits.get(b).foreach { c => rules += ((s"${b}_$t", compC(c, t))) } }
      }
      new Grammar(rules.result())
    }
  }

  def compile(m: Machine): Grammar = new Compiler(m).grammar()

  def check(m: Machine, maxLen: Int, oracle: String => Boolean): Option[String] = {
    val g = compile(m)
    val it = Exhaust.words(maxLen, m.alphabet)
    var bad: Option[String] = None
    while (bad.isEmpty && it.hasNext) {
      val w = it.next()
      val want = oracle(w)
      val sim = if (w.isEmpty) { m.initTrue(m.accept) } else { run(m, w).outputs.last }
      val peg = g.accepts(w)
      if (sim != want) { bad = Some(s"interpreter disagrees on '$w': got $sim, want $want") }
      else if (peg != want) { bad = Some(s"PEG disagrees on '$w': got $peg, want $want") }
    }
    bad
  }
}
