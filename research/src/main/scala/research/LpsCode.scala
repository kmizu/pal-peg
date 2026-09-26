package research

import scala.collection.mutable

/** The slot compiler of `Lps` with the site channel of plan §3: a pointer is `(address extent, site-code
  * extent)`. The code of a cell allocated at site `s` is the helper `Code_{colour(s)}`, which ends
  * `colour(s)` letters before the end of the input; site tests are `&(P_code Tail_i)`. One code rule per
  * pointer (`<name>_c`) replaces the log₂(sites) site-bit rules of `Lps`. Registers copy codes, conditional
  * chains select them with the same exclusive masks, the previous tick's code is `. &. last_c`
  * (consuming), and a field of a pointer is chosen by the code of its base pointer.
  *
  * Precondition (documented, not proved for arbitrary machines): every reachable machine cell is born at a
  * reading tick >= its colour, so that its code is representable. `LpsWarmup` provides it for PAL3 by
  * disabling the stages of width below `W0 = nextPow2(colours)`; `LpsWarmup.checkReachable` checks it in
  * the debug interpreter. Colours are injective on every set of sites a pointer may point into
  * (`SiteCode.color`, asserted after the final compile). A pointer never has both an input and a machine
  * kind (asserted).
  */
object LpsCode {
  import Lpm.{E, C, TE, Step}
  import Lpm.E.*
  import Lps.{Machine, K, KInp, KTag, Inp, L, Sc, BothInp, RuleMeta}
  import Expr.*
  import SiteCode.Site

  sealed trait Dispatch
  case object Direct extends Dispatch
  case object Trie extends Dispatch
  /** `fallbackBelow`: the PEG answers the lengths below it with the bounded palindrome grammar, the machine (warmed
    * up: no machine cell born before tick `minBirth`, which must be >= the colour count) from that length on.
    * PAL3 with warmup W0: fallbackBelow = 2 W0, minBirth = W0; OverlapPal with minAnchor W0: 3 W0 / 2 and W0. */
  /** `mirrorFallback`: the fallback by mirrored pairs (`SiteCode.fallbackMirror`) instead of the `Pal_k` family */
  /** `recolour` (STRATEGY.md §3e): after a first compilation, renumber the colours by the number of references to their
    * `Tail_c` (the most referenced colour gets the shortest code), then compile again */
  final case class Options(fallbackBelow: Option[Int] = None, minBirth: Int = 0, dispatch: Dispatch = Direct, codeByStar: Boolean = false,
                           mirrorFallback: Boolean = false, recolour: Boolean = false)
  final case class Result(grammar: Grammar, meta: Map[String, RuleMeta], coloring: SiteCode.Coloring, domains: Vector[Set[Site]])

  final class Compiler(m: Machine, hints: Map[String, Inp], colour: Site => Int, colourCount: Int, opts: Options) {
    private val inpOutB = Map.newBuilder[String, Inp]
    def inpOut: Map[String, Inp] = inpOutB.result()
    private val domainsB = mutable.LinkedHashSet.empty[Set[Site]]
    def domains: Vector[Set[Site]] = domainsB.toVector
    /** every set of sites a pointer may point into passes through here (the colouring must be injective on it) */
    private def domain(kinds: Set[K]): Vector[Site] = { val ss = m.sitesOf(kinds); domainsB += ss.toSet; ss }
    private def hint(name: String, kinds: Set[K]): Inp = if (kinds(KInp)) { hints.getOrElse(name, Set.empty) } else { Set.empty }
    private def letterOf(name: String, inp: Inp): Expr = if (inp == BothInp) { Ref(s"${name}_kL") } else if (inp == Set(L)) { TrueE } else { FalseE }
    private def sepOf(name: String, inp: Inp): Expr = if (inp == BothInp) { Not(Ref(s"${name}_kL")) } else if (inp == Set(Sc)) { TrueE } else { FalseE }
    private def codeOf(name: String, kinds: Set[K]): Expr = if (domain(kinds).nonEmpty) { Ref(s"${name}_c") } else { FalseE }
    private val B = m.budget
    private val rules = Vector.newBuilder[(String, Expr)]
    private var curLayer = 0
    private var curSlot = "-"
    private val metaB = Map.newBuilder[String, RuleMeta]
    private def emit(r: (String, Expr)): Unit = { rules += r; metaB += r._1 -> RuleMeta(curLayer, curSlot) }
    def meta: Map[String, RuleMeta] = metaB.result()
    private val memoE = mutable.HashMap.empty[(E, Int), P]
    private val memoC = mutable.HashMap.empty[(C, Int), Expr]
    private var fresh = 0
    private def nbits(n: Int): Int = SiteCode.nbits(n)
    private val sepTag = m.alphabet.length
    private val regKinds: Map[String, Set[K]] = m.regs.toMap
    private val TrueE: Expr = Seq(Vector())
    private val FalseE: Expr = Not(TrueE)
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

    /** A compiled pointer: extent, letter/separator tests, site code (consuming), static kinds, input kind. */
    private final case class P(ext: Expr, isLetter: Expr, isSep: Expr, code: Expr, kinds: Set[K], inp: Inp) {
      def sites: Vector[Site] = domain(kinds)
    }

    /** the zero-width test "the pointer's site has colour c" */
    private def isColour(p: P, c: Int): Expr = look(and(p.code, Ref(SiteCode.tail(c))))
    /** the ordered choice over the sites `ss` of `p`: `leaf(site)` is the alternative taken at that site.
      * Direct: one arm per site guarded by its colour; Trie: a decision trie on the colour bits (`Mask_k`). */
    private def dispatch(p: P, ss: Vector[Site], leaf: Site => Expr): Expr = opts.dispatch match {
      case Direct => or(ss.map(x => and(isColour(p, colour(x)), leaf(x)))*)
      case Trie =>
        def bit(k: Int): Expr = look(and(p.code, Ref(SiteCode.mask(k))))
        def go(k: Int, xs: Vector[Site]): Expr =
          if (xs.isEmpty) { FalseE }
          else if (xs.size == 1) { leaf(xs.head) }
          else {
            val (ones, zeros) = xs.partition(x => ((colour(x) >> k) & 1) == 1)
            if (ones.isEmpty || zeros.isEmpty) { go(k - 1, xs) }
            else { or(and(Not(bit(k)), go(k - 1, zeros)), and(bit(k), go(k - 1, ones))) }
          }
        go(nbits(colourCount) - 1, ss)
    }

    private def defAt(x: String, t: Int, writes: Int => Boolean): String = {
      var l = t
      while (l > 0 && !writes(l)) { l -= 1 }
      s"${x}_$l"
    }
    private def regWrites(r: String)(l: Int): Boolean = m.slotAt(l).regs.contains(r)
    private def bitWrites(b: String)(l: Int): Boolean = m.slotAt(l).bits.contains(b)
    private def regName(r: String, t: Int): String = defAt(r, t, regWrites(r))
    private def bitRef(b: String, t: Int): Expr = Ref(defAt(b, t, bitWrites(b)))

    private def ruleP(name: String, kinds: Set[K]): P = {
      require(!(kinds(KInp) && kinds.exists(_ != KInp)), s"$name: a pointer with both an input and a machine kind is not supported")
      val inp = hint(name, kinds)
      P(Ref(name), letterOf(name, inp), sepOf(name, inp), codeOf(name, kinds), kinds, inp)
    }
    private def regP(r: String, t: Int): P = ruleP(regName(r, t), regKinds(r))
    private def fieldKindsAt(l: Int, i: Int, f: Int): Set[K] = m.tagsAt(l, i).flatMap(tag => m.fieldKinds(tag, f)).toSet
    private def fieldName(l: Int, i: Int, f: Int): String = s"C_${l}_${i}_p$f"
    private def cellFieldP(l: Int, i: Int, f: Int): P = ruleP(fieldName(l, i, f), fieldKindsAt(l, i, f))

    private def named(p: P, t: Int): P = {
      fresh += 1
      val x = s"X${fresh}_$t"
      emit((x, p.ext))
      if (p.inp == BothInp) { emit((s"${x}_kL", p.isLetter)) }
      if (p.sites.nonEmpty) { emit((s"${x}_c", p.code)) }
      P(Ref(x), letterOf(x, p.inp), sepOf(x, p.inp), if (p.sites.nonEmpty) { Ref(s"${x}_c") } else { FalseE }, p.kinds, p.inp)
    }

    private def compE(e: E, t: Int): P = e match {
      case Fld(_, _) | Ite(_, _, _) => memoE.getOrElseUpdate((e, t), named(compE0(e, t), t))
      case _ => compE0(e, t)
    }

    private def compE0(e: E, t: Int): P = e match {
      case Null => P(FalseE, FalseE, FalseE, FalseE, Set(), Set())
      case Reg(r) => regP(r, t - 1)
      case In => P(Here, TrueE, FalseE, FalseE, Set(KInp), Set(L))
      case Sep => P(Here, FalseE, TrueE, FalseE, Set(KInp), Set(Sc))
      case Made | MadeAt(_) =>
        val i = e match { case MadeAt(j) => j; case _ => 0 }
        val kinds: Set[K] = m.tagsAt(t, i).map(KTag.apply).toSet
        // the cell's own position: its code is the helper of its colour, evaluated here (needs colour <= remaining length)
        P(Here, FalseE, FalseE, and(Here, Ref(SiteCode.code(colour((t, i))))), kinds, Set())
      case Ite(_, _, _) =>
        val cases = Vector.newBuilder[(Expr, P)]
        var cur: E = e
        var go = true
        while (go) cur match {
          case Ite(c, a, b) => cases += ((compC(c, t), compE(a, t))); cur = b
          case _ => go = false
        }
        val rs = cases.result()
        val pd = compE(cur, t)
        val kinds = rs.map(_._2.kinds).foldLeft(pd.kinds)(_ ++ _)
        val masks = Vector.newBuilder[Expr]
        var remaining: Vector[Expr] = Vector()
        for ((g, _) <- rs) { masks += and((remaining :+ g)*); remaining = remaining :+ Not(g) }
        val ms = masks.result()
        val rest = and(remaining*)
        def sel(f: P => Expr): Expr = or((ms.zip(rs).map { case (h, (_, pa)) => and(look(h), f(pa)) } :+ and(look(rest), f(pd)))*)
        P(sel(_.ext), sel(_.isLetter), sel(_.isSep), sel(_.code), kinds, rs.map(_._2.inp).foldLeft(pd.inp)(_ ++ _))
      case Fld(MadeAt(i), f) if m.sitesAt(t).indices.contains(i) =>
        if (fieldKindsAt(t, i, f).isEmpty) { P(FalseE, FalseE, FalseE, FalseE, Set(), Set()) } else { cellFieldP(t, i, f) }   // an always-null field
      case Fld(x, f) =>
        val px = compE(x, t)
        val alts = Vector.newBuilder[(Expr, P)]
        if (px.kinds(KInp) && f == 1) {
          val both = px.inp == BothInp
          if (px.inp(L)) { alts += ((and(if (both) { look(px.isLetter) } else { TrueE }, px.ext), P(and(AnyChar, Here), FalseE, TrueE, FalseE, Set(KInp), Set(Sc)))) }
          if (px.inp(Sc)) { alts += ((and(if (both) { look(px.isSep) } else { TrueE }, px.ext), P(Here, TrueE, FalseE, FalseE, Set(KInp), Set(L)))) }
        }
        val pxTags: Set[Int] = px.kinds.collect { case KTag(tag) => tag }
        val siteAlts: Vector[(Site, Expr, P)] = px.sites.flatMap { case x @ (l, i) =>
          val tags = m.tagsAt(l, i).toSet
          // a site whose field f can hold no pointer (empty schema kinds: the field is always null) has no field rule; the
          // read is null there, which is what no matching arm gives (a register domain that unites two tags, e.g. `tmp`)
          if (tags.intersect(pxTags).isEmpty || fieldKindsAt(l, i, f).isEmpty) { None }
          else {
            val atCell = if (tags.size == 1) { TrueE } else { or(tags.intersect(pxTags).toVector.sorted.map(tag => tagTest(l, i, tag))*) }
            Some((x, look(atCell), cellFieldP(l, i, f)))
          }
        }
        val inputAlts = alts.result()
        val kinds: Set[K] = (inputAlts.map(_._2) ++ siteAlts.map(_._3)).flatMap(_.kinds).toSet
        val sas: Map[Site, (Expr, P)] = siteAlts.map { case (x, g, fp) => x -> ((g, fp)) }.toMap
        // the site alternatives: the base's code selects the site, then its extent, the cell's tag, the field
        def choose(g: P => Expr): Expr = or((inputAlts.map { case (guard, fp) => and(guard, g(fp)) } :+
          (if (sas.isEmpty) { FalseE } else { dispatch(px, siteAlts.map(_._1), x => { val (atCell, fp) = sas(x); and(px.ext, atCell, g(fp)) }) }))*)
        // letter/separator tests are zero-width; the code is a consuming extent and is never wrapped
        def pred(g: P => Expr): Expr = look(choose(p => look(g(p))))
        P(choose(_.ext), pred(_.isLetter), pred(_.isSep), choose(_.code), kinds, (inputAlts.map(_._2.inp) ++ siteAlts.map(_._3.inp)).foldLeft(Set.empty[Char])(_ ++ _))
    }

    private val uses = mutable.HashMap.empty[C, Int]
    private val seenE = mutable.HashSet.empty[E]
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
        else { look(dispatch(p, p.sites, { case (l, i) => look(and(p.ext, tagTest(l, i, tag))) })) }
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

    private def emitPointer(name: String, e: E, t: Int, kinds: Set[K]): Unit = {
      val p0 = e match {
        case Fld(_, _) | Ite(_, _, _) if !memoE.contains((e, t)) => compE0(e, t)
        case _ => compE(e, t)
      }
      emitPointerP(name, p0, kinds)
      e match {
        case Fld(_, _) | Ite(_, _, _) if !memoE.contains((e, t)) => memoE((e, t)) = ruleP(name, kinds)
        case _ => ()
      }
    }
    private def emitPointerP(name: String, p: P, kinds: Set[K]): Unit = {
      emit((name, p.ext))
      if (kinds(KInp)) { inpOutB += name -> p.inp }
      if (kinds(KInp) && p.inp == BothInp) { emit((s"${name}_kL", p.isLetter)) }
      if (domain(kinds).nonEmpty) { emit((s"${name}_c", p.code)) }
    }

    def grammar(): Grammar = {
      curLayer = 0; curSlot = "-"
      opts.fallbackBelow match {
        case Some(_) =>
          require(opts.minBirth >= colourCount, s"the earliest machine birth ${opts.minBirth} is below the colour count $colourCount")
          emit(("S", or(Ref("Short"), and(look(Ref("Long")), look(bitRef(m.accept, B)), Star(AnyChar)))))
        case None =>
          val empty: Expr = if (m.initTrue(m.accept)) { Not(AnyChar) } else { FalseE }
          emit(("S", or(empty, and(look(AnyChar), look(bitRef(m.accept, B)), Star(AnyChar)))))
      }
      curSlot = "helper"
      SiteCode.helpers(colourCount, opts.codeByStar).foreach(emit)
      curSlot = "fallback"
      opts.fallbackBelow.foreach(limit => (if (opts.mirrorFallback) { SiteCode.fallbackMirror(limit) } else { SiteCode.fallbackBelow(limit) }).foreach(emit))
      curSlot = "-"
      val prev: Expr = and(AnyChar, look(AnyChar))
      for ((r, kinds) <- m.regs) {
        val last = regName(r, B)
        emit((s"${r}_0", and(prev, Ref(last))))
        if (kinds(KInp)) { inpOutB += s"${r}_0" -> hint(last, kinds) }
        if (kinds(KInp) && hint(last, kinds) == BothInp) { emit((s"${r}_0_kL", look(and(prev, Ref(s"${last}_kL"))))) }
        if (domain(kinds).nonEmpty) { emit((s"${r}_0_c", and(prev, Ref(s"${last}_c")))) }
      }
      for (b <- m.bits) {
        val carried = look(and(prev, bitRef(b, B)))
        emit((s"${b}_0", if (m.initTrue(b)) { or(carried, look(and(AnyChar, Not(AnyChar)))) } else { carried }))
      }
      for (t <- 1 to B) {
        curLayer = t; curSlot = m.schedule(t - 1)
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

  /** all sites of the machine in (layer, index) order */
  private def allSites(m: Machine): Vector[Site] = (1 to m.budget).flatMap(t => m.sitesAt(t).indices.map(i => (t, i))).toVector

  /** the input-kind fixpoint with a trivial colouring, then the colouring of the recorded domains, then the
    * final compile; the domain set must not change and the colouring must be injective on all of it */
  def compileMeta(m: Machine, opts: Options = Options()): Result = {
    val trivial = allSites(m).zipWithIndex.toMap
    val trivialCount = math.max(trivial.size, 1)
    var hints: Map[String, Inp] = Map.empty
    var domains: Vector[Set[Site]] = Vector()
    var done = false
    while (!done) {
      val c = new Compiler(m, hints, trivial, trivialCount, Options(None, 0, opts.dispatch))
      c.grammar()
      val next = (hints.keySet ++ c.inpOut.keySet).map(k => k -> (hints.getOrElse(k, Set.empty[Char]) ++ c.inpOut.getOrElse(k, Set.empty[Char]))).toMap
      if (next == hints) { done = true; domains = c.domains } else { hints = next }
    }
    val coloring0 = SiteCode.color(domains)
    val coloring = if (!opts.recolour) { coloring0 } else {
      val g0 = new Compiler(m, hints, coloring0.apply, math.max(coloring0.count, 1), opts).grammar()
      val refs = g0.rules.flatMap { case (_, e) => PegBudgetInline.occurrences(e).toVector }.groupMapReduce(_._1)(_._2.toLong)(_ + _)
      val byUse = (0 until coloring0.count).sortBy(i => (-refs.getOrElse(SiteCode.tail(i), 0L), i))
      val perm = byUse.zipWithIndex.toMap   // old colour -> new colour
      SiteCode.Coloring(coloring0.colors.map { case (site, col) => site -> perm(col) }, coloring0.count)
    }
    val c = new Compiler(m, hints, coloring.apply, math.max(coloring.count, 1), opts)
    val g = c.grammar()
    require(c.domains.toSet == domains.toSet, "the domains changed with the colouring")
    coloring.requireInjective(c.domains)
    require(c.inpOut.forall { case (k, v) => hints.getOrElse(k, Set.empty[Char]) == v }, "the input kinds changed with the colouring")
    Result(g, c.meta, coloring, domains)
  }
  def compile(m: Machine, opts: Options = Options()): Grammar = compileMeta(m, opts).grammar

  /** PEG vs interpreter on all words (with a fallback: vs the palindrome oracle below 2 W0, where the machine answers false) */
  def checkSim(m: Machine, maxLen: Int, opts: Options = Options()): Option[String] = {
    val g = compile(m, opts)
    val it = Exhaust.words(maxLen, m.alphabet)
    var bad: Option[String] = None
    while (bad.isEmpty && it.hasNext) {
      val w = it.next()
      val sim = if (w.isEmpty) { m.initTrue(m.accept) } else { Lps.run(m, w).outputs.last }
      val want = opts.fallbackBelow match {
        case Some(limit) if w.length < limit => LpsPal.isPal(w)
        case _ => sim
      }
      val peg = g.accepts(w)
      if (peg != want) { bad = Some(s"PEG disagrees on '$w': PEG $peg, expected $want (interpreter $sim)") }
    }
    bad
  }

  /** every register's address and code extents after every tick against the interpreter's cells */
  def checkRules(m: Machine, w: String, opts: Options = Options()): Option[String] = {
    val res = compileMeta(m, opts)
    val mt = res.grammar.matcher(w)
    val n = w.length
    def lastWriter(r: String): Int = { var l = m.budget; while (l > 0 && !m.slotAt(l).regs.contains(r)) { l -= 1 }; l }
    var bad: Option[String] = None
    Lps.trace(m, w) { (tick, st) =>
      val p = n - tick
      for ((r, kinds) <- m.regs if bad.isEmpty) {
        val c = st.regs.getOrElse(r, null)
        val l = lastWriter(r)
        if (l > 0) {
          val name = s"${r}_$l"
          val ext = mt.rule(name, p).map(_ - p)
          val wantExt = if (c == null) { None } else { Some(tick - c.tick) }
          if (ext != wantExt) { bad = Some(s"tick $tick '$w': $name extent $ext, expected $wantExt (cell tick ${if (c == null) "-" else c.tick.toString})") }
          else if (m.sitesOf(kinds).nonEmpty) {
            val code = mt.rule(s"${name}_c", p)
            val wantCode = if (c == null || c.tag <= m.alphabet.length) { None } else { Some(n - res.coloring((c.layer, c.site))) }
            if (code != wantCode) { bad = Some(s"tick $tick '$w': ${name}_c ends at $code, expected $wantCode") }
          }
        }
      }
    }
    bad
  }
}
