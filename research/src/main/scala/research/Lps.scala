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
    def tagsOfTE(te: TE): Set[Int] = te match { case TE.Const(t) => Set(t); case TE.Ite(_, a, b) => tagsOfTE(a) ++ tagsOfTE(b) }
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

  /** run with a callback after every tick (1-based, in reading order: the word is read from its end) */
  def trace(m: Machine, w: String)(f: (Int, State) => Unit): Unit = {
    val regNames = m.regs.map(_._1)
    var st = State(Map.empty, m.initTrue)
    var prevSep: RCell = null
    for (i <- (w.length - 1) to 0 by -1) {
      val in = new RCell(m.alphabet.indexOf(w.charAt(i)), prevSep, null, null, null, -1)
      val sep = new RCell(m.alphabet.length, in, null, null, null, -2)
      for (t <- 1 to m.budget) { st = unitStep(m.slotAt(t), regNames, m.bits, st, in, sep, t == 1, t) }
      f(w.length - i, st)
      prevSep = sep
    }
  }

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
    /** bits needed to index n alternatives (0 when n <= 1) */
    private def nbits(n: Int): Int = { var b = 0; while ((1 << b) < n) { b += 1 }; b }
    private val sepTag = m.alphabet.length
    private val regKinds: Map[String, Set[K]] = m.regs.toMap
    private val TrueE: Expr = Seq(Vector())
    private val FalseE: Expr = Not(TrueE)
    // constant folding: TrueE is the empty sequence, FalseE never matches
    private def and(xs: Expr*): Expr = {
      val ys = xs.toVector.flatMap { case Seq(ps) => ps; case x => Vector(x) }
      if (ys.contains(FalseE)) { FalseE } else if (ys.size == 1) { ys.head } else { Seq(ys) }
    }
    private def or(xs: Expr*): Expr = {
      val ys0 = xs.toVector.flatMap { case Choice(as) => as; case x => Vector(x) }.filter(_ != FalseE)
      val ys = ys0.indexOf(TrueE) match { case -1 => ys0; case i => ys0.take(i + 1) }
      if (ys.isEmpty) { FalseE } else if (ys.size == 1) { ys.head } else { Choice(ys) }
    }
    private def look(e: Expr): Expr = e match {
      case Seq(ps) if ps.isEmpty => TrueE
      case And(_) | Not(_) => e
      case _ => And(e)
    }
    private val Here: Expr = And(AnyChar)
    private def bitName(x: String, k: Int) = s"${x}_y$k"
    /** the layer bits of layer `l` among the possible layers `ls`: the binary index of `l` in `ls` */
    private def constBits(ls: Vector[Int], l: Int): Vector[Expr] = {
      val i = math.max(ls.indexOf(l), 0)
      (0 until nbits(ls.size)).map(k => if (((i >> k) & 1) == 1) { TrueE } else { FalseE }).toVector
    }
    private def bitRefs(name: String, ls: Vector[Int]): Vector[Expr] = (0 until nbits(ls.size)).map(k => Ref(bitName(name, k))).toVector

    /** A compiled pointer: extent, letter/separator tests, layer bits (relative to `layers`), and the static kind set. */
    private final case class P(ext: Expr, isLetter: Expr, isSep: Expr, bits: Vector[Expr], kinds: Set[K]) {
      def layers: Vector[Int] = m.layersOf(kinds)
      def layer(l: Int): Expr = {
        val ls = layers
        val i = ls.indexOf(l)
        if (ls == Vector(l)) { TrueE }
        else if (i < 0) { FalseE }
        else { and((0 until nbits(ls.size)).map { k => if (((i >> k) & 1) == 1) { bits(k) } else { Not(bits(k)) } }*) }
      }
      /** the same pointer with bits relative to the (larger) kind set `kinds2` */
      def recode(kinds2: Set[K]): P = {
        val ls2 = m.layersOf(kinds2)
        if (ls2 == layers) { copy(kinds = kinds2) }
        else if (kinds2.subsetOf(kinds) && ls2.size <= 1) { P(ext, isLetter, isSep, Vector(), kinds2) }
        else {
          val bits2 = (0 until nbits(ls2.size)).map { k => or(layers.filter(l => ((ls2.indexOf(l) >> k) & 1) == 1).map(l => layer(l))*) }.toVector
          P(ext, isLetter, isSep, bits2, kinds2)
        }
      }
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
      val bits = bitRefs(name, ls)
      P(Ref(name), if (kinds(KInp)) { Ref(s"${name}_kL") } else { FalseE }, if (kinds(KInp)) { Not(Ref(s"${name}_kL")) } else { FalseE }, bits, kinds)
    }

    /** field `f` of the cells allocated at layer `l`, one rule per group of tags with the same field kinds */
    private def fieldGroups(l: Int, f: Int): Vector[(Set[Int], Set[K])] =
      tagsAt(l).groupBy(tag => m.fieldKinds(tag, f)).toVector.map { case (kinds, tags) => (tags.toSet, kinds) }.sortBy(_._1.min)
    private def fieldName(l: Int, f: Int, gi: Int): String = if (fieldGroups(l, f).size == 1) { s"C_${l}_p$f" } else { s"C_${l}_p${f}k$gi" }
    private def cellFieldP(l: Int, f: Int, gi: Int): P = {
      val kinds = fieldGroups(l, f)(gi)._2
      val name = fieldName(l, f, gi)
      val ls = m.layersOf(kinds)
      val bits = bitRefs(name, ls)
      P(Ref(name), if (kinds(KInp)) { Ref(s"${name}_kL") } else { FalseE }, if (kinds(KInp)) { Not(Ref(s"${name}_kL")) } else { FalseE }, bits, kinds)
    }

    private def named(p: P, t: Int): P = {
      fresh += 1
      val x = s"X${fresh}_$t"
      rules += ((x, p.ext))
      val hasInp = p.kinds(KInp)
      if (hasInp) { rules += ((s"${x}_kL", p.isLetter)) }
      val ls = p.layers
      for (k <- 0 until nbits(ls.size)) { rules += ((bitName(x, k), p.bits(k))) }
      val bits = bitRefs(x, ls)
      P(Ref(x), if (hasInp) { Ref(s"${x}_kL") } else { FalseE }, if (hasInp) { Not(Ref(s"${x}_kL")) } else { FalseE }, bits, p.kinds)
    }

    private def compE(e: E, t: Int): P = e match {
      case Fld(_, _) | Ite(_, _, _) => memoE.getOrElseUpdate((e, t), named(compE0(e, t), t))
      case _ => compE0(e, t)
    }

    private def compE0(e: E, t: Int): P = e match {
      case Null => P(FalseE, FalseE, FalseE, Vector(), Set())
      case Reg(r) => regP(r, t - 1)
      case In => P(Here, TrueE, FalseE, Vector(), Set(KInp))
      case Sep => P(Here, FalseE, TrueE, Vector(), Set(KInp))
      case Made =>
        val kinds: Set[K] = m.tagsOf(m.slotAt(t)).map(KTag.apply)
        P(Here, FalseE, FalseE, constBits(m.layersOf(kinds), t), kinds)
      case Ite(c, a, b) =>
        val cc = compC(c, t)
        val kinds = compE(a, t).kinds ++ compE(b, t).kinds
        val pa = compE(a, t).recode(kinds)
        val pb = compE(b, t).recode(kinds)
        def sel(fa: Expr, fb: Expr): Expr = or(and(look(cc), fa), and(Not(cc), fb))
        P(sel(pa.ext, pb.ext), sel(pa.isLetter, pb.isLetter), sel(pa.isSep, pb.isSep), (0 until nbits(m.layersOf(kinds).size)).map(k => sel(pa.bits(k), pb.bits(k))).toVector, kinds)
      case Fld(x, f) =>
        val px = compE(x, t)
        val alts = Vector.newBuilder[(Expr, P)]
        if (px.kinds(KInp) && f == 1) {
          alts += ((and(look(px.isLetter), px.ext), P(and(AnyChar, Here), FalseE, TrueE, Vector(), Set(KInp))))
          alts += ((and(look(px.isSep), px.ext), P(Here, TrueE, FalseE, Vector(), Set(KInp))))
        }
        val pxTags: Set[Int] = px.kinds.collect { case KTag(tag) => tag }
        for (l <- px.layers) {
          val groups = fieldGroups(l, f)
          for (((tags, _), gi) <- groups.zipWithIndex if tags.intersect(pxTags).nonEmpty) {
            // when the layer's cells split into several groups, the group is recognised by the cell's tag
            val atCell = if (groups.size == 1) { TrueE } else { or(tags.intersect(pxTags).toVector.sorted.map(tag => tagTest(l, tag))*) }
            alts += ((and(look(px.layer(l)), px.ext, look(atCell)), cellFieldP(l, f, gi)))
          }
        }
        val kinds: Set[K] = alts.result().flatMap(_._2.kinds).toSet
        val as = alts.result().map { case (g, fp) => (g, fp.recode(kinds)) }
        def choose(g: P => Expr): Expr = or(as.map { case (guard, fp) => and(guard, g(fp)) }*)
        // predicates (letter/separator test, layer bits) must be zero-width: look past the extent of x
        def pred(g: P => Expr): Expr = look(choose(p => look(g(p))))
        val bits = (0 until nbits(m.layersOf(kinds).size)).map { k => pred(_.bits(k)) }.toVector
        P(choose(_.ext), pred(_.isLetter), pred(_.isSep), bits, kinds)
    }

    /** how often each condition occurs in the slot being compiled (an And/Or is named only when shared) */
    private val uses = mutable.HashMap.empty[C, Int]
    private def countC(c: C): Unit = {
      uses(c) = uses.getOrElse(c, 0) + 1
      c match {
        case C.Not(x) => countC(x)
        case C.And(a, b) => countC(a); countC(b)
        case C.Or(a, b) => countC(a); countC(b)
        case C.IsNull(e) => countE(e)
        case C.TagIs(e, _) => countE(e)
        case C.SameTag(a, b) => countE(a); countE(b)
        case _ => ()
      }
    }
    private def countE(e: E): Unit = e match {
      case Fld(x, _) => countE(x)
      case Ite(c, a, b) => countC(c); countE(a); countE(b)
      case _ => ()
    }
    private def countTE(te: TE): Unit = te match { case TE.Ite(c, a, b) => countC(c); countTE(a); countTE(b); case _ => () }
    private def countStep(step: Step): Unit = {
      uses.clear()
      step.regs.values.foreach(countE)
      step.bits.values.foreach(countC)
      step.allocs.foreach { case (g, c) => countC(g); countTE(c.tag); countE(c.p1); countE(c.p2); countE(c.p3); countE(c.p4) }
      if (step.allocs.isEmpty) { step.cell.foreach { c => countTE(c.tag); countE(c.p1); countE(c.p2); countE(c.p3); countE(c.p4) } }
    }

    private def compC(c: C, t: Int): Expr = c match {
      case C.And(_, _) | C.Or(_, _) if uses.getOrElse(c, 0) <= 1 => compC0(c, t)
      case C.And(_, _) | C.Or(_, _) | C.IsNull(_) | C.TagIs(_, _) | C.SameTag(_, _) =>
        memoC.getOrElseUpdate((c, t), { val x = compC0(c, t); fresh += 1; val n = s"K${fresh}_$t"; rules += ((n, x)); Ref(n) })
      case _ => compC0(c, t)
    }

    /** the tags a slot can allocate, sorted; a cell's tag is stored as its index among them */
    private def tagsAt(l: Int): Vector[Int] = m.tagsOf(m.slotAt(l)).toVector.sorted
    private def tagTest(l: Int, tag: Int): Expr = {
      val tags = tagsAt(l)
      val i = tags.indexOf(tag)
      if (i < 0) { FalseE }
      else if (tags.size == 1) { TrueE }
      else { and((0 until nbits(tags.size)).map { k => if (((i >> k) & 1) == 1) { Ref(s"C_${l}_g$k") } else { Not(Ref(s"C_${l}_g$k")) } }*) }
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
      case TE.Const(v) => if (((tagsAt(t).indexOf(v) >> k) & 1) == 1) { TrueE } else { FalseE }
      case TE.Ite(c, a, b) => val cc = compC(c, t); or(and(look(cc), compTE(a, t, k)), and(Not(cc), compTE(b, t, k)))
    }

    private def emitPointer(name: String, p0: P, kinds: Set[K]): Unit = {
      // the static kinds of the value may be wider than the declared kinds (e.g. `Made` in a slot that allocates
      // several tags); the declaration is trusted, and layers outside it are dropped by the recoding
      val p = p0.recode(kinds)
      rules += ((name, p.ext))
      if (kinds(KInp)) { rules += ((s"${name}_kL", p.isLetter)) }
      for (k <- 0 until nbits(m.layersOf(kinds).size)) { rules += ((bitName(name, k), p.bits(k))) }
    }

    def grammar(): Grammar = {
      val empty: Expr = if (m.initTrue(m.accept)) { Not(AnyChar) } else { FalseE }
      rules += (("S", or(empty, and(look(bitRef(m.accept, B)), Star(AnyChar)))))
      // layer 0: the previous node's last definitions. The node after the last letter has no tick: its
      // rules would still evaluate (a phantom tick), so the initial state is taken there instead.
      val prev: Expr = and(AnyChar, look(AnyChar))
      for ((r, kinds) <- m.regs) {
        val last = regName(r, B)
        rules += ((s"${r}_0", and(prev, Ref(last))))
        if (kinds(KInp)) { rules += ((s"${r}_0_kL", look(and(prev, Ref(s"${last}_kL"))))) }
        for (k <- 0 until nbits(m.layersOf(kinds).size)) { rules += ((bitName(s"${r}_0", k), look(and(prev, Ref(bitName(last, k)))))) }
      }
      for (b <- m.bits) {
        val carried = look(and(prev, bitRef(b, B)))
        rules += ((s"${b}_0", if (m.initTrue(b)) { or(carried, look(and(AnyChar, Not(AnyChar)))) } else { carried }))
      }
      for (t <- 1 to B) {
        val step = m.slotAt(t)
        countStep(step)
        step.cell.foreach { cl =>
          val tags = m.tagsOf(step)
          for (k <- 0 until nbits(tags.size)) { rules += ((s"C_${t}_g$k", compTE(cl.tag, t, k))) }
          for (f <- 1 to 4; ((gtags, kinds), gi) <- fieldGroups(t, f).zipWithIndex if kinds.nonEmpty) {
            def fieldOf(c: Lpm.Cell): E = f match { case 1 => c.p1; case 2 => c.p2; case 3 => c.p3; case _ => c.p4 }
            // the field restricted to the allocations whose tag may be in this group (first guard wins)
            val mine = step.allocs.filter { case (_, c) => m.tagsOfTE(c.tag).intersect(gtags).nonEmpty }
            val src: E = if (step.allocs.isEmpty) { fieldOf(cl) } else { mine.foldRight(Null: E) { case ((g, c), acc) => Ite(g, fieldOf(c), acc) } }
            emitPointer(fieldName(t, f, gi), compE(src, t), kinds)
          }
        }
        for ((r, kinds) <- m.regs) { step.regs.get(r).foreach { e => emitPointer(s"${r}_$t", compE(e, t), kinds) } }
        for (b <- m.bits) { step.bits.get(b).foreach { c => rules += ((s"${b}_$t", compC(c, t))) } }
      }
      new Grammar(rules.result())
    }
  }

  def compile(m: Machine): Grammar = new Compiler(m).grammar()

  /** the compiled PEG must agree with the interpreter on every word, whatever the machine computes */
  def checkSim(m: Machine, maxLen: Int): Option[String] = {
    val g = compile(m)
    val it = Exhaust.words(maxLen, m.alphabet)
    var bad: Option[String] = None
    while (bad.isEmpty && it.hasNext) {
      val w = it.next()
      val sim = if (w.isEmpty) { m.initTrue(m.accept) } else { run(m, w).outputs.last }
      val peg = g.accepts(w)
      if (peg != sim) { bad = Some(s"PEG disagrees with the interpreter on '$w': PEG $peg, interpreter $sim") }
    }
    bad
  }

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
