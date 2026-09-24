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
    def tagsOfTE(te: TE): Set[Int] = te match { case TE.Const(t) => Set(t); case TE.Ite(_, a, b) => tagsOfTE(a) ++ tagsOfTE(b) }
    /** the allocation sites of slot `t` */
    def sitesAt(t: Int): Vector[(C, Lpm.Cell)] = Lpm.sitesOf(slotAt(t))
    /** the tags site `i` of slot `t` may allocate, sorted */
    def tagsAt(t: Int, i: Int): Vector[Int] = tagsOfTE(sitesAt(t)(i)._2.tag).toVector.sorted
    /** allocation site = (layer, index within the layer) */
    val sitesOfTag: Map[Int, Vector[(Int, Int)]] = {
      val m = mutable.HashMap.empty[Int, Vector[(Int, Int)]]
      for (t <- 1 to budget; i <- sitesAt(t).indices; tag <- tagsAt(t, i)) { m(tag) = m.getOrElse(tag, Vector()) :+ ((t, i)) }
      m.toMap
    }
    /** the sites whose cells a value of these kinds may point to */
    def sitesOf(kinds: Set[K]): Vector[(Int, Int)] = kinds.collect { case KTag(t) => sitesOfTag.getOrElse(t, Vector()) }.flatten.toVector.distinct.sorted
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

  /** run with a callback after every slot: (tick, slot name, state) */
  def traceSlots(m: Machine, w: String)(f: (Int, String, State) => Unit): Unit = {
    val regNames = m.regs.map(_._1)
    var st = State(Map.empty, m.initTrue)
    var prevSep: RCell = null
    for (i <- (w.length - 1) to 0 by -1) {
      val in = new RCell(m.alphabet.indexOf(w.charAt(i)), prevSep, null, null, null, -1)
      val sep = new RCell(m.alphabet.length, in, null, null, null, -2)
      for (t <- 1 to m.budget) { st = unitStep(m.slotAt(t), regNames, m.bits, st, in, sep, t == 1, t); f(w.length - i, m.schedule(t - 1), st) }
      prevSep = sep
    }
  }

  /** a cell's tag agrees with the declared kinds (input cells: KInp; machine cells: their tag) */
  private def kindOk(m: Machine, c: RCell, kinds: Set[K]): Boolean = c == null || (if (c.tag <= m.alphabet.length) { kinds(KInp) } else { kinds(KTag(c.tag)) })

  def run(m: Machine, w: String): Run = {
    val regNames = m.regs.map(_._1)
    var st = State(Map.empty, m.initTrue)
    var prevSep: RCell = null
    val outs = Vector.newBuilder[Boolean]
    for (i <- (w.length - 1) to 0 by -1) {
      val in = new RCell(m.alphabet.indexOf(w.charAt(i)), prevSep, null, null, null, -1)
      val sep = new RCell(m.alphabet.length, in, null, null, null, -2)
      for (t <- 1 to m.budget) {
        st = unitStep(m.slotAt(t), regNames, m.bits, st, in, sep, t == 1, t)
        // the compiler trusts the declared kinds: check them here, register by register and field by field
        for ((r, kinds) <- m.regs; c = st.regs.getOrElse(r, null) if !kindOk(m, c, kinds)) {
          throw new IllegalStateException(s"register $r holds a cell of tag ${c.tag} (declared kinds $kinds) after slot $t of tick ${w.length - i}")
        }
        for ((r, _) <- m.regs; c = st.regs.getOrElse(r, null) if c != null && c.tag > m.alphabet.length; f <- 1 to 4; x = c.fld(f) if !kindOk(m, x, m.fieldKinds(c.tag, f))) {
          throw new IllegalStateException(s"field $f of a cell of tag ${c.tag} (in $r) holds tag ${x.tag}, declared ${m.fieldKinds(c.tag, f)}")
        }
      }
      outs += st.bits(m.accept)
      prevSep = sep
    }
    new Run(outs.result())
  }

  // ---- compiler ------------------------------------------------------------------------------

  /** abstract input kind of a pointer: the letters it can be ('L'), the separator ('S'); empty = never an input cell */
  type Inp = Set[Char]
  val L: Char = 'L'; val Sc: Char = 'S'
  val BothInp: Inp = Set(L, Sc)

  /** `hints`: the input kind of every register/field rule from the previous compile (a fixpoint from below) */
  final class Compiler(m: Machine, hints: Map[String, Inp] = Map.empty) {
    private val inpOutB = Map.newBuilder[String, Inp]
    def inpOut: Map[String, Inp] = inpOutB.result()
    private def hint(name: String, kinds: Set[K]): Inp = if (kinds(KInp)) { hints.getOrElse(name, Set.empty) } else { Set.empty }
    /** the letter/separator predicates of a rule-backed pointer of input kind `inp` */
    private def letterOf(name: String, inp: Inp): Expr = if (inp == BothInp) { Ref(s"${name}_kL") } else if (inp == Set(L)) { TrueE } else { FalseE }
    private def sepOf(name: String, inp: Inp): Expr = if (inp == BothInp) { Not(Ref(s"${name}_kL")) } else if (inp == Set(Sc)) { TrueE } else { FalseE }
    private val B = m.budget
    private val rules = Vector.newBuilder[(String, Expr)]
    /** generation metadata: the layer and the slot name of every emitted rule (S and the layer-0 aliases: layer 0, "-") */
    private var curLayer = 0
    private val metaB = Map.newBuilder[String, RuleMeta]
    private def emit(r: (String, Expr)): Unit = { rules += r; metaB += r._1 -> RuleMeta(curLayer, if (curLayer == 0) { "-" } else { m.schedule(curLayer - 1) }) }
    def meta: Map[String, RuleMeta] = metaB.result()
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
    type Site = (Int, Int)
    /** the site bits of site `x` among the possible sites `ss`: the binary index of `x` in `ss` */
    private def constBits(ss: Vector[Site], x: Site): Vector[Expr] = {
      val i = math.max(ss.indexOf(x), 0)
      (0 until nbits(ss.size)).map(k => if (((i >> k) & 1) == 1) { TrueE } else { FalseE }).toVector
    }
    private def bitRefs(name: String, ss: Vector[Site]): Vector[Expr] = (0 until nbits(ss.size)).map(k => Ref(bitName(name, k))).toVector

    /** A compiled pointer: extent, letter/separator tests, site bits (relative to `sites`), and the static kind set. */
    private final case class P(ext: Expr, isLetter: Expr, isSep: Expr, bits: Vector[Expr], kinds: Set[K], inp: Inp) {
      def sites: Vector[Site] = m.sitesOf(kinds)
      def site(x: Site): Expr = {
        val ss = sites
        val i = ss.indexOf(x)
        if (ss == Vector(x)) { TrueE }
        else if (i < 0) { FalseE }
        else { and((0 until nbits(ss.size)).map { k => if (((i >> k) & 1) == 1) { bits(k) } else { Not(bits(k)) } }*) }
      }
      /** the same pointer with bits relative to the kind set `kinds2` */
      def recode(kinds2: Set[K]): P = {
        val ss2 = m.sitesOf(kinds2)
        if (ss2 == sites) { copy(kinds = kinds2) }
        else if (kinds2.subsetOf(kinds) && ss2.size <= 1) { P(ext, isLetter, isSep, Vector(), kinds2, inp) }
        else {
          val bits2 = (0 until nbits(ss2.size)).map { k => or(sites.filter(x => ((ss2.indexOf(x) >> k) & 1) == 1).map(x => site(x))*) }.toVector
          P(ext, isLetter, isSep, bits2, kinds2, inp)
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
      val bits = bitRefs(name, m.sitesOf(kinds))
      val inp = hint(name, kinds)
      P(Ref(name), letterOf(name, inp), sepOf(name, inp), bits, kinds, inp)
    }

    /** field `f` of the cell allocated at site (l, i) */
    private def fieldKindsAt(l: Int, i: Int, f: Int): Set[K] = m.tagsAt(l, i).flatMap(tag => m.fieldKinds(tag, f)).toSet
    private def fieldName(l: Int, i: Int, f: Int): String = s"C_${l}_${i}_p$f"
    private def cellFieldP(l: Int, i: Int, f: Int): P = {
      val kinds = fieldKindsAt(l, i, f)
      val name = fieldName(l, i, f)
      val bits = bitRefs(name, m.sitesOf(kinds))
      val inp = hint(name, kinds)
      P(Ref(name), letterOf(name, inp), sepOf(name, inp), bits, kinds, inp)
    }

    private def named(p: P, t: Int): P = {
      fresh += 1
      val x = s"X${fresh}_$t"
      emit((x, p.ext))
      if (p.inp == BothInp) { emit((s"${x}_kL", p.isLetter)) }
      val ss = p.sites
      for (k <- 0 until nbits(ss.size)) { emit((bitName(x, k), p.bits(k))) }
      val bits = bitRefs(x, ss)
      P(Ref(x), letterOf(x, p.inp), sepOf(x, p.inp), bits, p.kinds, p.inp)
    }

    private def compE(e: E, t: Int): P = e match {
      case Fld(_, _) | Ite(_, _, _) => memoE.getOrElseUpdate((e, t), named(compE0(e, t), t))
      case _ => compE0(e, t)
    }

    private def compE0(e: E, t: Int): P = e match {
      case Null => P(FalseE, FalseE, FalseE, Vector(), Set(), Set())
      case Reg(r) => regP(r, t - 1)
      case In => P(Here, TrueE, FalseE, Vector(), Set(KInp), Set(L))
      case Sep => P(Here, FalseE, TrueE, Vector(), Set(KInp), Set(Sc))
      case Made | MadeAt(_) =>
        val i = e match { case MadeAt(j) => j; case _ => 0 }
        val kinds: Set[K] = m.tagsAt(t, i).map(KTag.apply).toSet
        P(Here, FalseE, FalseE, constBits(m.sitesOf(kinds), (t, i)), kinds, Set())
      case Ite(_, _, _) =>
        // a chain of conditionals is one ordered choice: the first condition that holds selects its value
        val cases = Vector.newBuilder[(Expr, P)]
        var cur: E = e
        var go = true
        while (go) cur match {
          case Ite(c, a, b) => cases += ((compC(c, t), compE(a, t))); cur = b
          case _ => go = false
        }
        val cs = cases.result()
        val pd = compE(cur, t)
        val kinds = cs.map(_._2.kinds).foldLeft(pd.kinds)(_ ++ _)
        val rs = cs.map { case (g, pa) => (g, pa.recode(kinds)) }
        val pdr = pd.recode(kinds)
        // exclusive masks: the alternative of the first condition that holds is the only one tried, so a
        // selected value that fails (null, a false bit, a failed test) never falls through to a later one
        val masks = Vector.newBuilder[Expr]
        var remaining: Vector[Expr] = Vector()
        for ((g, _) <- rs) { masks += and((remaining :+ g)*); remaining = remaining :+ Not(g) }
        val ms = masks.result()
        val rest = and(remaining*)
        def sel(f: P => Expr): Expr = or((ms.zip(rs).map { case (h, (_, pa)) => and(look(h), f(pa)) } :+ and(look(rest), f(pdr)))*)
        P(sel(_.ext), sel(_.isLetter), sel(_.isSep), (0 until nbits(m.sitesOf(kinds).size)).map(k => sel(_.bits(k))).toVector, kinds, rs.map(_._2.inp).foldLeft(pdr.inp)(_ ++ _))
      case Fld(MadeAt(i), f) if m.sitesAt(t).indices.contains(i) =>
        // a field of a cell allocated in this slot: the field's own rule, no dispatch
        cellFieldP(t, i, f)
      case Fld(x, f) =>
        val px = compE(x, t)
        val alts = Vector.newBuilder[(Expr, P)]
        if (px.kinds(KInp) && f == 1) {
          // the previous cell of a letter is its separator and of a separator its letter; the static input
          // kind decides which alternatives can apply, and a single one needs no guard
          val both = px.inp == BothInp
          if (px.inp(L)) { alts += ((and(if (both) { look(px.isLetter) } else { TrueE }, px.ext), P(and(AnyChar, Here), FalseE, TrueE, Vector(), Set(KInp), Set(Sc)))) }
          if (px.inp(Sc)) { alts += ((and(if (both) { look(px.isSep) } else { TrueE }, px.ext), P(Here, TrueE, FalseE, Vector(), Set(KInp), Set(L)))) }
        }
        val pxTags: Set[Int] = px.kinds.collect { case KTag(tag) => tag }
        for (x @ (l, i) <- px.sites) {
          val tags = m.tagsAt(l, i).toSet
          if (tags.intersect(pxTags).nonEmpty) {
            // a site with several possible tags is narrowed by the cell's tag
            val atCell = if (tags.size == 1) { TrueE } else { or(tags.intersect(pxTags).toVector.sorted.map(tag => tagTest(l, i, tag))*) }
            alts += ((and(look(px.site(x)), px.ext, look(atCell)), cellFieldP(l, i, f)))
          }
        }
        val kinds: Set[K] = alts.result().flatMap(_._2.kinds).toSet
        val as = alts.result().map { case (g, fp) => (g, fp.recode(kinds)) }
        def choose(g: P => Expr): Expr = or(as.map { case (guard, fp) => and(guard, g(fp)) }*)
        // predicates (letter/separator test, layer bits) must be zero-width: look past the extent of x
        def pred(g: P => Expr): Expr = look(choose(p => look(g(p))))
        val bits = (0 until nbits(m.sitesOf(kinds).size)).map { k => pred(_.bits(k)) }.toVector
        P(choose(_.ext), pred(_.isLetter), pred(_.isSep), bits, kinds, as.map(_._2.inp).foldLeft(Set.empty[Char])(_ ++ _))
    }

    /** how often each condition occurs in the slot being compiled (an And/Or is named only when shared) */
    private val uses = mutable.HashMap.empty[C, Int]
    private val seenE = mutable.HashSet.empty[E]
    /** parent references per node (a DAG node is entered once) */
    private def countC(c: C): Unit = {
      uses(c) = uses.getOrElse(c, 0) + 1
      if (uses(c) == 1) c match {
        case C.Not(x) => countC(x)
        case C.And(a, b) => countC(a); countC(b)
        case C.Or(a, b) => countC(a); countC(b)
        case C.IsNull(e) => countE(e)
        case C.TagIs(e, _) => countE(e)
        case C.SameTag(a, b) => countE(a); countE(b)
        case _ => ()
      }
    }
    private def countE(e: E): Unit = if (seenE.add(e)) e match {
      case Fld(x, _) => countE(x)
      case Ite(c, a, b) => countC(c); countE(a); countE(b)
      case _ => ()
    }
    private def countTE(te: TE): Unit = te match { case TE.Ite(c, a, b) => countC(c); countTE(a); countTE(b); case _ => () }
    private def countStep(step: Step): Unit = {
      uses.clear(); seenE.clear()
      step.regs.values.foreach(countE)
      step.bits.values.foreach(countC)
      step.allocs.foreach { case (g, c) => countC(g); countTE(c.tag); countE(c.p1); countE(c.p2); countE(c.p3); countE(c.p4) }
      if (step.allocs.isEmpty) { step.cell.foreach { c => countTE(c.tag); countE(c.p1); countE(c.p2); countE(c.p3); countE(c.p4) } }
    }

    private def compC(c: C, t: Int): Expr = c match {
      case C.And(_, _) | C.Or(_, _) if uses.getOrElse(c, 0) <= 1 => memoC.getOrElseUpdate((c, t), compC0(c, t))
      case C.And(_, _) | C.Or(_, _) | C.IsNull(_) | C.TagIs(_, _) | C.SameTag(_, _) =>
        memoC.getOrElseUpdate((c, t), { val x = compC0(c, t); fresh += 1; val n = s"K${fresh}_$t"; emit((n, x)); Ref(n) })
      case C.Not(_) => memoC.getOrElseUpdate((c, t), compC0(c, t))
      case _ => compC0(c, t)
    }

    /** a site's cell tag is stored as its index among the site's possible tags */
    private def tagTest(l: Int, i: Int, tag: Int): Expr = {
      val tags = m.tagsAt(l, i)
      val j = tags.indexOf(tag)
      if (j < 0) { FalseE }
      else if (tags.size == 1) { TrueE }
      else { and((0 until nbits(tags.size)).map { k => if (((j >> k) & 1) == 1) { Ref(s"C_${l}_${i}_g$k") } else { Not(Ref(s"C_${l}_${i}_g$k")) } }*) }
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
        else { or(p.sites.map { case x @ (l, i) => and(look(p.site(x)), look(and(p.ext, tagTest(l, i, tag)))) }*) }
      case C.SameTag(a, b) =>
        val pa = compE(a, t)
        val pb = compE(b, t)
        val letters = m.alphabet.map { ch => and(look(pa.isLetter), look(pb.isLetter), look(and(pa.ext, Lit(ch.toString))), look(and(pb.ext, Lit(ch.toString)))) }
        or((letters :+ and(look(pa.isSep), look(pb.isSep), look(pa.ext), look(pb.ext)))*)
      case C.Bit(b) => bitRef(b, t - 1)
      case C.First => if (t == 1) { TrueE } else { FalseE }
    }

    private def compTE(te: TE, t: Int, i: Int, k: Int): Expr = te match {
      case TE.Const(v) => if (((m.tagsAt(t, i).indexOf(v) >> k) & 1) == 1) { TrueE } else { FalseE }
      case TE.Ite(c, a, b) => val cc = compC(c, t); or(and(look(cc), compTE(a, t, i, k)), and(Not(cc), compTE(b, t, i, k)))
    }

    /** a register/field rule: the top-level dereference or conditional is emitted under the rule's own name
      * (not as a separate named `X`), and later uses of the same expression alias to it */
    private def emitPointer(name: String, e: E, t: Int, kinds: Set[K]): Unit = {
      val p0 = e match {
        case Fld(_, _) | Ite(_, _, _) if !memoE.contains((e, t)) => compE0(e, t)
        case _ => compE(e, t)
      }
      emitPointerP(name, p0, kinds)
      e match {
        case Fld(_, _) | Ite(_, _, _) if !memoE.contains((e, t)) =>
          val ss = m.sitesOf(kinds)
          val inp = hint(name, kinds)
          memoE((e, t)) = P(Ref(name), letterOf(name, inp), sepOf(name, inp), bitRefs(name, ss), kinds, inp)
        case _ => ()
      }
    }
    private def emitPointerP(name: String, p0: P, kinds: Set[K]): Unit = {
      // the static kinds of the value may be wider than the declared kinds (e.g. `Made` in a slot that allocates
      // several tags); the declaration is trusted, and layers outside it are dropped by the recoding
      val p = p0.recode(kinds)
      emit((name, p.ext))
      if (kinds(KInp)) { inpOutB += name -> p.inp }
      if (kinds(KInp) && p.inp == BothInp) { emit((s"${name}_kL", p.isLetter)) }
      for (k <- 0 until nbits(m.sitesOf(kinds).size)) { emit((bitName(name, k), p.bits(k))) }
    }

    def grammar(): Grammar = {
      val empty: Expr = if (m.initTrue(m.accept)) { Not(AnyChar) } else { FalseE }
      // the accept bit is read only when there is a letter (its rules at the end node would be a phantom tick)
      emit(("S", or(empty, and(look(AnyChar), look(bitRef(m.accept, B)), Star(AnyChar)))))
      // layer 0: the previous node's last definitions. The node after the last letter has no tick: its
      // rules would still evaluate (a phantom tick), so the initial state is taken there instead.
      val prev: Expr = and(AnyChar, look(AnyChar))
      for ((r, kinds) <- m.regs) {
        val last = regName(r, B)
        emit((s"${r}_0", and(prev, Ref(last))))
        if (kinds(KInp)) { inpOutB += s"${r}_0" -> hint(last, kinds) }
        if (kinds(KInp) && hint(last, kinds) == BothInp) { emit((s"${r}_0_kL", look(and(prev, Ref(s"${last}_kL"))))) }
        for (k <- 0 until nbits(m.sitesOf(kinds).size)) { emit((bitName(s"${r}_0", k), look(and(prev, Ref(bitName(last, k)))))) }
      }
      for (b <- m.bits) {
        val carried = look(and(prev, bitRef(b, B)))
        emit((s"${b}_0", if (m.initTrue(b)) { or(carried, look(and(AnyChar, Not(AnyChar)))) } else { carried }))
      }
      for (t <- 1 to B) {
        curLayer = t
        val step = m.slotAt(t)
        countStep(step)
        for (((_, cl), i) <- m.sitesAt(t).zipWithIndex) {
          for (k <- 0 until nbits(m.tagsAt(t, i).size)) { emit((s"C_${t}_${i}_g$k", compTE(cl.tag, t, i, k))) }
          for (f <- 1 to 4; kinds = fieldKindsAt(t, i, f) if kinds.nonEmpty) {
            val src: E = f match { case 1 => cl.p1; case 2 => cl.p2; case 3 => cl.p3; case _ => cl.p4 }
            emitPointer(fieldName(t, i, f), src, t, kinds)
          }
        }
        for ((r, kinds) <- m.regs) { step.regs.get(r).foreach { e => emitPointer(s"${r}_$t", e, t, kinds) } }
        for (b <- m.bits) { step.bits.get(b).foreach { c => emit((s"${b}_$t", compC(c, t))) } }
      }
      new Grammar(rules.result())
    }
  }

  final case class RuleMeta(layer: Int, slot: String)
  def compile(m: Machine): Grammar = compileMeta(m)._1
  /** the grammar with the layer and slot of every rule. The static input kinds of the registers and fields
    * are a fixpoint from below over compiles: each compile reads the previous one's kinds. */
  def compileMeta(m: Machine): (Grammar, Map[String, RuleMeta]) = {
    var hints: Map[String, Inp] = Map.empty
    var result: Option[(Grammar, Map[String, RuleMeta])] = None
    while (result.isEmpty) {
      val c = new Compiler(m, hints)
      val g = c.grammar()
      val next = (hints.keySet ++ c.inpOut.keySet).map(k => k -> (hints.getOrElse(k, Set.empty[Char]) ++ c.inpOut.getOrElse(k, Set.empty[Char]))).toMap
      if (next == hints) { result = Some((g, c.meta)) } else { hints = next }
    }
    result.get
  }

  /** the same machine with each group of consecutive slots fused into one slot (exact: sequential composition) */
  def fused(m: Machine, groups: Vector[Vector[String]]): Machine = {
    require(groups.flatten == m.schedule, "the groups must partition the schedule in order")
    val slots = groups.map { g => g.mkString("+") -> g.map(m.slots).reduceLeft(Lpm.fuse) }.toMap
    m.copy(schedule = groups.map(_.mkString("+")), slots = slots)
  }

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
