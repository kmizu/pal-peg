package research

/** PAL on universal local steps: the controller of `LpsPal`, but every worker is `n` copies of one
  * universal step per tick (`Kmp2`: the matcher core of `LpsKmp2` over a module's registers, with
  * the module's own letter sources and extra phases as further branches of the same step). */
object LpsPal2 {
  import Lpm.{E, C, TE, Prog, Step}
  import Lpm.E.*
  import Lpm.C.*
  import Lpm.TE.Const
  import Lps.*
  import LpsPal.{Mod, Tags, mTags, fTags, ctlRegs, ctlBits}

  val BUILD = 0; val BACK = 1; val REV = 2; val MATCH = 3; val MBACK = 4
  private def not(c: C): C = Not(c)
  private def below(e: E): E = Fld(e, 2)
  private def entryOf(q: E): E = Fld(q, 3)
  private def prevIn(e: E): E = Fld(Fld(e, 1), 1)
  private def bitOf(k: Int, i: Int): C = if (((k >> i) & 1) == 1) { True } else { False }
  private def Ite2(g: C, a: C, b: C): C = Or(And(g, a), And(Not(g), b))
  private def selectE(cases: Seq[(C, E)], default: E): E = cases.foldRight(default) { case ((g, v), acc) => Ite(g, v, acc) }

  /** The universal KMP step over module `M`. The module adds its letter-loading branches (`load`) between
    * the back-up branches and the letter-processing branches, and its own phases after them (`extra`).
    * A load branch sets `c` and `have`, or `eop` and `have` at the end of the pattern. */
  /** `Hooks` (all optional): `matchStart` runs when REV ends and MATCH begins; `lose` runs at every step that
    * shortens the match by one without consuming a letter or consumes a letter without growing it (a root
    * mismatch, a back-up unit in MATCH); `unaryAlt` = (guard, below) adds a use of the UNARY site. */
  final case class Hooks(matchStart: Prog#Upd => Unit = _ => (), lose: Prog#Upd => Unit = _ => (), unaryAlt: Option[(C, E)] = None)

  final class Kmp2(M: Mod, T: Tags, atFrontier: C, keepFull: Boolean, hooks: Hooks = Hooks()) {
    import M.*
    import T.*
    val regNames: Vector[String] = Vector("c", "ent", "cur", "pFail", "front", "rear", "gap", "gw", "pRoot")
    val regs: Vector[(String, Set[K])] = Vector(n("c") -> Set(KInp), n("ent") -> entryKinds, n("cur") -> entryKinds,
      n("pFail") -> entryKinds, n("front") -> Set(KTag(EQC)), n("rear") -> Set(KTag(EQC)), n("gap") -> Set(KTag(UNARY)),
      n("gw") -> Set(KTag(UNARY)), n("pRoot") -> Set(KTag(UNARY)))
    val bits: Vector[String] = Vector(n("have"), n("cmp"), n("erev"), n("acc"), n("again"), n("eop"))
    def reset(u: Prog#Upd): Unit = { regNames.foreach(x => u.set(n(x), Null)); bits.foreach(b => u.bit(b, False)); u.phase(BUILD) }
    def setPhase(u: Prog#Upd, g: C, ifTrue: Int, ifFalse: Int): Unit =
      for (i <- 0 until phaseBits) { u.bit(n(s"p$i"), Ite2(g, bitOf(ifTrue, i), bitOf(ifFalse, i))) }

    def universal(load: Prog => Unit, extra: (Prog, E) => Unit): Prog = {
      val c = r("c"); val cur = r("cur"); val front = r("front"); val rear = r("rear"); val gap = r("gap"); val gw = r("gw"); val ent = r("ent")
      val same = SameTag(c, Fld(cur, 1))
      val gwDone = IsNull(below(gw))
      val gBack = at(BACK)
      val gRevPush = And(at(REV), not(IsNull(cur)))
      val gRevEnd = And(at(REV), IsNull(cur))
      val gMBack = at(MBACK)
      val haveB = And(at(BUILD), B("have"))
      val gEnd = all(haveB, not(B("cmp")), B("eop"))
      val gFirst = all(haveB, not(B("cmp")), IsNull(ent))
      val gNew = all(haveB, not(B("cmp")))
      val gCmp = all(haveB, B("cmp"))
      val cmpOK = all(same, not(B("erev")), not(IsNull(front)))
      val cmpRev = all(same, Or(B("erev"), IsNull(front)))
      val cmpGap = all(not(same), TagIs(cur, ENTRY0))
      val cmpBack = all(not(same), not(TagIs(cur, ENTRY0)))
      val haveM = And(at(MATCH), B("have"))
      val gMSame = all(haveM, same)
      val gMGap = all(haveM, not(same), TagIs(cur, ENTRY0))
      val gMBackStart = all(haveM, not(same))
      val p = prog()
      val pushing = any(gBack, gRevPush, gMBack)
      val e: E = p.site(TE.Ite(gFirst, Const(ENTRY0), TE.Ite(gEnd, Const(END), Const(ENTRY))),
        Ite(gEnd, Null, c), Ite(gFirst, Null, r("pFail")), Ite(gFirst, Null, r("pRoot")), Ite(gFirst, Null, ent))
      val q: E = p.site(Const(EQC), Null, selectE(Vector((pushing, front), (gNew, rear)), front),
        selectE(Vector((pushing, cur), (gNew, e)), entryOf(rear)), Null)
      val gBelow: E = hooks.unaryAlt match { case Some((hg, hb)) => Ite(hg, hb, Ite(gFirst, Null, gap)); case None => Ite(gFirst, Null, gap) }
      val g: E = p.site(Const(UNARY), Null, gBelow, Null, Null)
      p.when(gBack) { u =>
        u.set(n("front"), q); u.set(n("cur"), Fld(cur, 4)); u.set(n("gap"), g); u.set(n("gw"), below(gw))
        setPhase(u, gwDone, BUILD, BACK)
      }
      p.when(gRevPush) { u => u.set(n("front"), q); u.set(n("cur"), Fld(cur, 4)) }
      p.when(gRevEnd) { u => u.set(n("cur"), entryOf(front)); u.set(n("front"), below(front)); u.phase(MATCH); hooks.matchStart(u) }
      p.when(gMBack) { u =>
        u.set(n("front"), q); u.set(n("cur"), Fld(cur, 4)); u.set(n("gw"), below(gw)); hooks.lose(u)
        setPhase(u, gwDone, MATCH, MBACK)
        u.bit(n("again"), Ite2(gwDone, False, B("again")))
      }
      load(p)
      p.when(gEnd) { u => u.set(n("ent"), e); u.set(n("cur"), e); u.set(n("front"), Null); u.bit(n("have"), False); u.phase(REV) }
      p.when(gFirst) { u =>
        u.set(n("ent"), e); u.set(n("cur"), e); u.set(n("pFail"), e); u.set(n("gap"), g); u.set(n("pRoot"), g); u.bit(n("have"), False)
      }
      def compare(u: Prog#Upd, allocating: Boolean): Unit = {
        u.set(n("cur"), Ite(cmpOK, entryOf(front), cur))
        u.set(n("front"), if (allocating) { Ite(cmpOK, below(front), front) } else { selectE(Vector((cmpOK, below(front)), (cmpRev, q)), front) })
        u.set(n("pFail"), selectE(Vector((cmpOK, entryOf(front)), (cmpGap, cur)), r("pFail")))
        u.set(n("pRoot"), selectE(Vector((cmpOK, gap), (cmpGap, g)), r("pRoot")))
        u.set(n("gap"), Ite(cmpGap, g, gap))
        u.set(n("gw"), Ite(cmpBack, Fld(cur, 3), gw))
        u.bit(n("have"), Ite2(Or(cmpOK, cmpGap), False, True))
        u.bit(n("cmp"), Ite2(Or(cmpRev, cmpBack), True, False))
        setPhase(u, cmpBack, BACK, BUILD)
      }
      p.when(gNew) { u => u.set(n("ent"), e); u.set(n("rear"), q); compare(u, allocating = true) }
      p.when(gCmp) { u =>
        val last = IsNull(below(rear))
        u.set(n("rear"), Ite(cmpRev, below(rear), rear)); u.bit(n("erev"), Ite2(cmpRev, not(last), B("erev")))
        compare(u, allocating = false)
      }
      p.when(gMSame) { u =>
        val nxt = entryOf(front)
        val full = TagIs(nxt, END)
        u.set(n("cur"), nxt); u.set(n("front"), below(front))
        u.bit(n("acc"), Or(B("acc"), And(full, atFrontier)))
        u.set(n("gw"), Fld(nxt, 3)); u.bit(n("again"), False); u.bit(n("have"), False)
        if (keepFull) { u.phase(MATCH) } else { setPhase(u, full, MBACK, MATCH) }
      }
      p.when(gMGap) { u => u.bit(n("have"), False); hooks.lose(u) }
      p.when(gMBackStart) { u => u.set(n("gw"), Fld(cur, 3)); u.bit(n("again"), True); u.phase(MBACK) }
      extra(p, g)
      p
    }
  }

  // ---- stage matchers ---------------------------------------------------------------------------

  final class Matcher(val s: Int) {
    val M = new Mod(s"m${s}_", 3, 0, Bit(s"alive$s"))
    val T: Tags = mTags(s)
    import M.*
    import T.IQC
    val queueEmpty: C = all(IsNull(r("pendF")), IsNull(r("pendB")), IsNull(r("pendN")), not(B("rev")))
    val core = new Kmp2(M, T, queueEmpty, keepFull = false)
    val regs: Vector[(String, Set[K])] = core.regs ++ Vector(n("src") -> Set(KInp), n("pendF") -> Set(KTag(IQC)), n("pendB") -> Set(KTag(IQC)), n("pendN") -> Set(KTag(IQC)))
    val bits: Vector[String] = core.bits ++ M.ctlBits ++ Vector(n("rev"))
    def in: Prog = {
      val p = prog()
      p.when(Bit(s"b$s")) { u =>
        core.reset(u); u.set(n("src"), In); u.set(n("pendF"), Null); u.set(n("pendB"), Null); u.set(n("pendN"), Null); u.bit(n("rev"), False)
      }
      p.when(And(Bit(s"alive$s"), queueEmpty)) { u => u.set(n("pendF"), u.alloc(IQC, In, Null, Null, Null)); u.bit(n("acc"), False) }
      p.when(all(Bit(s"alive$s"), not(B("rev")))) { u => u.set(n("pendB"), u.alloc(IQC, In, r("pendB"), Null, Null)); u.bit(n("acc"), False) }
      p.when(Bit(s"alive$s")) { u => u.set(n("pendN"), u.alloc(IQC, In, r("pendN"), Null, Null)); u.bit(n("acc"), False) }
      p
    }
    def load(p: Prog): Unit = {
      val iq: E = p.site(Const(IQC), Fld(r("pendB"), 1), r("pendF"), Null, Null)
      val idleB = And(at(BUILD), not(B("have")))
      val idleM = And(at(MATCH), not(B("have")))
      p.when(And(idleB, not(IsNull(r("src"))))) { u => u.set(n("c"), r("src")); u.set(n("src"), prevIn(r("src"))); u.bit(n("have"), True) }
      p.when(And(idleB, not(B("eop")))) { u => u.bit(n("eop"), True); u.bit(n("have"), True) }
      p.when(all(idleM, not(B("rev")), not(IsNull(r("pendF"))))) { u => u.set(n("c"), Fld(r("pendF"), 1)); u.set(n("pendF"), below(r("pendF"))); u.bit(n("have"), True) }
      p.when(all(idleM, Or(B("rev"), And(IsNull(r("pendF")), not(IsNull(r("pendB"))))))) { u =>
        val last = IsNull(below(r("pendB")))
        u.set(n("pendF"), iq)
        u.set(n("pendB"), Ite(last, r("pendN"), below(r("pendB"))))
        u.set(n("pendN"), Ite(last, Null, r("pendN")))
        u.bit(n("rev"), not(last))
      }
    }
    def slots(allRegs: Vector[String], allBits: Vector[String]): Map[String, Step] =
      Map(s"M${s}IN" -> in.build(allRegs, allBits), s"M${s}U" -> core.universal(load, (_, _) => ()).build(allRegs, allBits))
    def schedule(k: Int): Vector[String] = Vector(s"M${s}IN") ++ Vector.fill(k)(s"M${s}U")
  }

  // ---- shared flags worker ------------------------------------------------------------------

  val IDLE = 8; val FWD = 9; val CHAIN = 10; val SKIP = 11
  object Flags {
    val M = new Mod("f_", 4, 0, True)
    val T: Tags = fTags
    import M.*
    import T.{IQC, UNARY, ENTRY0, GQC, ITER0, ITER1, iterKinds}
    val core = new Kmp2(M, T, True, keepFull = true)
    val regs: Vector[(String, Set[K])] = core.regs ++ Vector(n("rc") -> Set(KInp), n("rc0") -> Set(KInp), n("u") -> Set(KInp), n("jhalf") -> Set(KInp),
      n("fs") -> Set(KTag(IQC)), n("G") -> Set(KTag(GQC)), n("cnt") -> Set(KTag(UNARY)),
      "pc0" -> Set(KInp), "pc1" -> Set(KInp)) ++ (for (s <- 0 to 1; k <- 0 to 3) yield s"res${s}_$k" -> iterKinds)
    val bits: Vector[String] = core.bits ++ M.ctlBits ++ Vector(n("js"), n("jr0"), n("jr1"), n("rd0"), n("rd1"), n("pf"),
      "pp0", "pr0_0", "pr0_1", "pp1", "pr1_0", "pr1_1")
    private def jrIs(k: Int): C = And(if ((k & 1) == 1) { B("jr0") } else { not(B("jr0")) }, if ((k & 2) == 2) { B("jr1") } else { not(B("jr1")) })
    private def rdIs(k: Int): C = And(if ((k & 1) == 1) { B("rd0") } else { not(B("rd0")) }, if ((k & 2) == 2) { B("rd1") } else { not(B("rd1")) })
    private def decRd(u: Prog#Upd): Unit = { for (i <- 0 until 2) { u.bit(n(s"rd$i"), any((1 to 3).map(k => And(rdIs(k), bitOf(k - 1, i)))*)) } }
    private def ivIs(s: Int, k: Int): C = all((0 until 3).map(i => if (((k >> i) & 1) == 1) { Bit(s"iv${s}_$i") } else { Not(Bit(s"iv${s}_$i")) })*)
    /** advance the flags iterator (G, cnt, pf) by one length */
    def advance(u: Prog#Upd, G: String, Cn: String, pf: String): Unit = {
      val g = below(Fld(Reg(G), 3))
      val c = below(Reg(Cn))
      val arrived = Bit(pf)
      u.set(Cn, Ite(arrived, Ite(IsNull(Reg(G)), Null, g), c))
      u.set(G, Ite(arrived, below(Reg(G)), Reg(G)))
      u.bit(pf, Ite2(arrived, And(not(IsNull(Reg(G))), IsNull(g)), And(not(IsNull(Reg(Cn))), IsNull(c))))
    }
    def req: Prog = {
      val p = prog()
      p.when(True) { u =>
        for (s <- 0 to 1) {
          u.set(s"pc$s", Ite(Bit(s"rel$s"), In, Reg(s"pc$s")))
          u.bit(s"pp$s", Or(Bit(s"rel$s"), Bit(s"pp$s")))
          for (i <- 0 until 2) { u.bit(s"pr${s}_$i", Ite2(Bit(s"rel$s"), any((1 to 4).map(k => And(ivIs(s, k), bitOf(k - 1, i)))*), Bit(s"pr${s}_$i"))) }
        }
      }
      p
    }
    def load(p: Prog): Unit = {
      val idleB = And(at(BUILD), not(B("have")))
      val idleM = And(at(MATCH), not(B("have")))
      p.when(And(idleB, not(IsNull(r("fs"))))) { u => u.set(n("c"), Fld(r("fs"), 1)); u.set(n("fs"), below(r("fs"))); u.bit(n("have"), True) }
      p.when(And(idleB, not(B("eop")))) { u => u.bit(n("eop"), True); u.bit(n("have"), True) }
      p.when(And(idleM, not(IsNull(r("u"))))) { u => u.set(n("c"), r("rc")); u.set(n("rc"), prevIn(r("rc"))); u.set(n("u"), prevIn(r("u"))); u.bit(n("have"), True) }
      p.when(all(idleM, not(rdIs(0)))) { u => decRd(u); u.set(n("u"), r("jhalf")) }
      p.when(idleM) { u => u.set(n("G"), Null); u.phase(CHAIN) }
    }
    def extra(p: Prog): Unit = {
      val fq: E = p.site(Const(IQC), r("rc"), r("fs"), Null, Null)
      val gq: E = p.site(Const(GQC), Null, r("G"), Fld(r("cur"), 3), Null)
      val it: E = p.site(TE.Ite(B("pf"), Const(ITER1), Const(ITER0)), Null, r("G"), r("cnt"), Null)
      for (pref <- 0 to 1; s <- 0 to 1) {
        val older = if (s == 0) { not(Bit("slot")) } else { Bit("slot") }
        val cond = if (pref == 0) { And(Bit(s"pp$s"), older) } else { Bit(s"pp$s") }
        p.when(And(at(IDLE), cond)) { u =>
          u.bit(n("js"), if (s == 0) { False } else { True }); u.bit(n("jr0"), Bit(s"pr${s}_0")); u.bit(n("jr1"), Bit(s"pr${s}_1"))
          u.set(n("rc"), Reg(s"pc$s")); u.set(n("rc0"), Reg(s"pc$s")); u.set(n("jhalf"), Reg(s"half$s")); u.set(n("u"), Reg(s"half$s"))
          u.bit(n("rd0"), Bit(s"pr${s}_0")); u.bit(n("rd1"), Bit(s"pr${s}_1"))
          u.set(n("fs"), Null); u.bit(s"pp$s", False); u.phase(FWD)
        }
      }
      p.when(And(at(FWD), not(IsNull(r("u"))))) { u => u.set(n("fs"), fq); u.set(n("rc"), prevIn(r("rc"))); u.set(n("u"), prevIn(r("u"))) }
      p.when(And(at(FWD), not(rdIs(0)))) { u => decRd(u); u.set(n("u"), r("jhalf")) }
      p.when(at(FWD)) { u => core.reset(u); u.set(n("rc"), r("rc0")); u.set(n("u"), r("jhalf")); u.bit(n("rd0"), B("jr0")); u.bit(n("rd1"), B("jr1")) }
      p.when(And(at(CHAIN), not(TagIs(r("cur"), ENTRY0)))) { u => u.set(n("G"), gq); u.set(n("cur"), Fld(r("cur"), 2)) }
      p.when(at(CHAIN)) { u => u.bit(n("pf"), True); u.set(n("cnt"), Null); u.set(n("u"), Null); u.bit(n("rd0"), B("jr0")); u.bit(n("rd1"), B("jr1")); u.phase(SKIP) }
      p.when(And(at(SKIP), not(IsNull(r("u"))))) { u => advance(u, n("G"), n("cnt"), n("pf")); u.set(n("u"), prevIn(r("u"))) }
      p.when(And(at(SKIP), not(rdIs(0)))) { u => decRd(u); u.set(n("u"), r("jhalf")) }
      for (s <- 0 to 1; k <- 0 to 3) {
        p.when(all(at(SKIP), if (s == 0) { not(B("js")) } else { B("js") }, jrIs(k))) { u => u.set(s"res${s}_$k", it); u.phase(IDLE) }
      }
    }
    def slots(allRegs: Vector[String], allBits: Vector[String]): Map[String, Step] =
      Map("FREQ" -> req.build(allRegs, allBits), "FU" -> core.universal(load, (p, _) => extra(p)).build(allRegs, allBits))
    def schedule(k: Int): Vector[String] = Vector("FREQ") ++ Vector.fill(k)("FU")
  }

  def machine(mSteps: Int = 6, fSteps: Int = 24): Lps.Machine = {
    val ms = Vector(new Matcher(0), new Matcher(1))
    val regs = ctlRegs ++ ms.flatMap(_.regs) ++ Flags.regs
    val bits = ctlBits ++ ms.flatMap(_.bits) ++ Flags.bits
    val names = regs.map(_._1)
    val slots = Map("CT1" -> LpsPal.ct1().build(names, bits), "CT2" -> LpsPal.ct2.build(names, bits), "CT3" -> LpsPal.ct3.build(names, bits)) ++
      ms.flatMap(_.slots(names, bits)) ++ Flags.slots(names, bits)
    val schedule = Vector("CT1") ++ ms.flatMap(_.schedule(mSteps)) ++ Flags.schedule(fSteps) ++ Vector("CT2", "CT3")
    val schema = (mTags :+ fTags).map(_.schema).reduce(_ ++ _)
    Lps.Machine("ab", regs, bits, Set("out", "f_p3"), "out", schema, schedule, slots)
  }
}
