package research

/** STRATEGY.md §3p: the table-free matcher (`TwoWayMachine`, the reference of every branch) as Lpm cores in the pools plan of
  * `RolePal`: the schedule is CT, IN, YU × ky, OU × ko, OUT, the young role runs the whole bank (lists, passes, plan, search,
  * races, then the instances on the replay and the FIFO), the old role runs the instances on the FIFO. The controller, the
  * anchors, the replay countdown and the answer are `OverlapPal`'s; the handover and the pushes are `RolePal`'s.
  *
  * Kinds: NQ (the list N: an input cell, the next node), NPS/NPB (the list N': an input cell, the next node, its N node; tagged by
  * the midpoint), IQC (the FIFO). A read of an input cell is a constant expression in the grammar; the only machine cells are
  * the two lists (one cell a pattern letter each, in the young layers) and the FIFO.
  */
object TwoWayPal {
  import Lpm.{E, C, Prog}
  import Lpm.E.*
  import Lpm.C.*
  import Lpm.TE
  import Lps.*
  import TwoWayMachine.{BN, BNP, PINIT, PASS, PT, UT, UB, RU, SF, SB, SK, MW, XR, RR, MATCH}

  val NQ = 60; val NPS = 61; val NPB = 62; val IQC = 63
  val npKinds: Set[K] = Set(KTag(NPS), KTag(NPB))
  val schema: Map[Int, Vector[Set[K]]] = Map(
    NQ -> Vector(Set(KInp), Set(KTag(NQ)), Set(), Set()),
    NPS -> Vector(Set(KInp), npKinds, Set(KTag(NQ)), Set()),
    NPB -> Vector(Set(KInp), npKinds, Set(KTag(NQ)), Set()),
    IQC -> Vector(Set(KInp), Set(KTag(IQC)), Set(), Set()))

  private def not(c: C): C = Not(c)
  private def nul(e: E): C = IsNull(e)
  private def nn(e: E): C = Not(IsNull(e))
  private def f1(e: E): E = Fld(e, 1)
  private def f2(e: E): E = Fld(e, 2)
  private def f3(e: E): E = Fld(e, 3)
  /** one letter forward in the pattern = one input cell back (the separator between) */
  private def pf(e: E): E = Fld(Fld(e, 1), 1)
  private def same(a: E, b: E): C = SameTag(a, b)
  private def ite2(g: C, a: C, b: C): C = if (a == b) { a } else { Or(And(g, a), And(Not(g), b)) }
  private def bitOf(k: Int, i: Int): C = if (((k >> i) & 1) == 1) { True } else { False }
  val phaseBits = 4

  val inpRegs: Vector[String] = Vector("src", "rc", "p0", "lastP", "lg", "ks", "kc", "e", "pp", "z", "r1k", "r1p", "ksu", "ppu", "a", "b", "c",
    "a1", "k1", "k4", "rue", "rmem", "ui", "ue", "x1", "x2", "s2", "t2", "s3", "s4", "s5", "t3")
  val nqRegs: Vector[String] = Vector("nh", "tx", "w", "mid", "q", "d", "fc", "lcd", "crd", "lc", "cf", "cnt", "a2", "k3", "a4", "tb", "ub",
    "sk", "rskip", "kmu", "ma", "s1", "t1")
  val npRegs: Vector[String] = Vector("nph", "sn", "msn", "jn", "cs", "r1s", "la", "lp", "lu", "a3", "tn", "tE")
  /** the matcher (the old role, and the young role's last phase) */
  val mInp: Vector[String] = Vector("fp", "ce", "bt", "ul", "ule", "umf", "ume", "wfp", "wl", "D2", "cd")
  val mNq: Vector[String] = Vector("bp", "ubn", "wbp")
  val fifo: Vector[String] = Vector("pendF", "pendB", "pendN")
  val mBits: Vector[String] = Vector("bok", "usk", "umem", "wsk", "flag", "dbl", "rev")
  val youngBits: Vector[String] = (0 until phaseBits).map(i => s"ph$i").toVector ++ Vector("pr0", "pr1", "run2", "chg", "crossed", "early", "xo",
    "late", "r1chg", "r1crossed", "uper", "smem", "xpA")

  /** a role over the registers with prefix `px` */
  /** `drop`: branch-name prefixes left out of the young step (a size probe only: the machine is then wrong) */
  final class Role(val px: String, val young: Boolean, drop: Set[String] = Set()) {
    def n(x: String): String = px + x
    def r(x: String): E = Reg(px + x)
    def B(x: String): C = Bit(px + x)
    val regs: Vector[(String, Set[K])] =
      (if (young) { inpRegs.map(n(_) -> Set[K](KInp)) ++ nqRegs.map(n(_) -> Set[K](KTag(NQ))) ++ npRegs.map(n(_) -> npKinds) } else { Vector() }) ++
        mInp.map(n(_) -> Set[K](KInp)) ++ mNq.map(n(_) -> Set[K](KTag(NQ))) ++ fifo.map(n(_) -> Set[K](KTag(IQC)))
    val bits: Vector[String] = (if (young) { youngBits.map(n) } else { Vector(n("acc")) }) ++ mBits.map(n)
    def phIs(k: Int): C = all((0 until phaseBits).map(i => if (((k >> i) & 1) == 1) { B(s"ph$i") } else { not(B(s"ph$i")) })*)
    def phase(u: Prog#Upd, k: Int): Unit = for (i <- 0 until phaseBits) { u.bit(n(s"ph$i"), bitOf(k, i)) }
    /** the young role exists from its first birth on (p0 is its birth cell); the old role from its first handover (ul is set) */
    val born: C = if (young) { nn(r("p0")) } else { nn(r("ul")) }
    val matchSide: C = if (young) { And(born, phIs(MATCH)) } else { born }
    val queueEmpty: C = all(nul(r("pendF")), nul(r("pendB")), nul(r("pendN")), not(B("rev")))
    /** the branches of the last `step`, in priority order (for profiles) */
    var branches: Vector[(String, C)] = Vector()

    /** this tick's letter joins the FIFO (`RolePal.Role.pushes`) */
    def pushes(p: Prog, g0: C): Unit = {
      val gF = all(g0, queueEmpty, matchSide)
      val gB = all(g0, not(gF), born, not(B("rev")))
      val gN = all(g0, not(gF), not(gB), born)
      val q = p.site(TE.Const(IQC), In, Ite(gB, r("pendB"), Ite(gN, r("pendN"), Null)), Null, Null)
      p.when(gF) { u => u.set(n("pendF"), q); if (!young) { u.bit(n("acc"), False) } }
      p.when(gB) { u => u.set(n("pendB"), q); if (!young) { u.bit(n("acc"), False) } }
      p.when(gN) { u => u.set(n("pendN"), q); if (!young) { u.bit(n("acc"), False) } }
    }

    /** one letter `c` through the instances and their coupling (`TwoWayMachine.matchLetter`); returns the report */
    private def matchLetter(u: Prog#Upd, c: E): C = {
      val fp = r("fp"); val ce = r("ce"); val bp = r("bp"); val bt = r("bt"); val ubn = r("ubn")
      val usk = B("usk"); val bok = B("bok"); val umem = B("umem")
      // U: wait (the countdown bp), the letter that starts the backward check, or a forward comparison
      val waitB = And(usk, nn(bp)); val startB = And(usk, nul(bp))
      val fwd = not(usk)
      val m = And(fwd, same(fp, c))
      val full = And(m, nul(pf(ce))); val adv = And(m, nn(pf(ce))); val mis = And(fwd, not(same(fp, c)))
      val ru = all(full, nul(bp), bok)
      val back = all(adv, nn(bp), bok)
      val start = Or(startB, mis)
      u.set(n("fp"), Ite(full, Ite(umem, r("umf"), r("ul")), Ite(adv, pf(fp), Ite(mis, r("ul"), fp))))
      u.set(n("ce"), Ite(full, Ite(umem, r("ume"), r("ule")), Ite(adv, pf(ce), Ite(mis, r("ule"), ce))))
      u.bit(n("usk"), ite2(full, not(umem), ite2(startB, False, usk)))
      u.set(n("bp"), Ite(waitB, f2(bp), Ite(start, f2(ubn), Ite(full, Null, Ite(back, f2(bp), bp)))))
      u.set(n("bt"), Ite(start, pf(c), Ite(back, pf(bt), bt)))
      u.bit(n("bok"), ite2(start, Or(nul(ubn), same(f1(ubn), c)), ite2(full, True, ite2(back, same(f1(bp), bt), bok))))
      // W (double plans only): no backward check; a full scan waits one letter
      val dbl = B("dbl")
      val wfp = r("wfp"); val wbp = r("wbp"); val wsk = B("wsk")
      val wwait = And(wsk, nn(wbp)); val wstart = And(wsk, nul(wbp))
      val wfwd = not(wsk)
      val wm = And(wfwd, same(wfp, c))
      val wfull = And(wm, nul(pf(wfp))); val wadv = And(wm, nn(pf(wfp))); val wmis = And(wfwd, not(same(wfp, c)))
      u.set(n("wfp"), Ite(dbl, Ite(Or(wfull, wmis), r("wl"), Ite(wadv, pf(wfp), wfp)), wfp))
      u.bit(n("wsk"), ite2(dbl, ite2(wfull, True, ite2(wstart, False, wsk)), wsk))
      u.set(n("wbp"), Ite(And(dbl, wwait), f2(wbp), wbp))
      // the coupling: B's report counts when the countdown from W's candidate says it is about that candidate
      val cd = r("cd"); val d2 = r("D2"); val flag = B("flag")
      val hit = And(nn(cd), nul(pf(cd)))
      u.set(n("cd"), Ite(dbl, Ite(wmis, pf(d2), Ite(wfull, d2, pf(cd))), cd))
      u.bit(n("flag"), ite2(dbl, ite2(wmis, And(ru, nul(pf(d2))), ite2(wfull, False, Or(flag, And(ru, hit)))), flag))
      ite2(dbl, And(wfull, Or(flag, And(ru, hit))), ru)
    }

    /** the FIFO: the next letter, else a reversal step (`GapKmp`'s gFifoRev), sharing the site `iq` */
    private def fifoBranches(p: Prog, g0: C, iq: E, br: (String, C) => Unit): Unit = {
      val pendF = r("pendF"); val pendB = r("pendB"); val pendN = r("pendN")
      val gLetter = all(g0, not(B("rev")), nn(pendF))
      br(if (young) { "match-fifo" } else { "old-match" }, gLetter)
      p.when(gLetter) { u =>
        u.set(n("pendF"), f2(pendF))
        val rep = matchLetter(u, f1(pendF))
        if (!young) { u.bit(n("acc"), Or(B("acc"), And(rep, all(nul(f2(pendF)), nul(pendB), nul(pendN))))) }
      }
      val gRev = And(g0, Or(B("rev"), nn(pendB)))
      br(if (young) { "fifo" } else { "old-fifo" }, gRev)
      p.when(gRev) { u =>
        val last = nul(f2(pendB))
        u.set(n("pendF"), iq)
        u.set(n("pendB"), Ite(last, pendN, f2(pendB)))
        u.set(n("pendN"), Ite(last, Null, pendN))
        u.bit(n("rev"), not(last))
      }
    }

    /** the old role's step: the instances on the FIFO */
    def oldStep: Prog = {
      require(!young)
      val p = new Prog(0, 0, "", exclusive = true)
      val iq = p.site(TE.Const(IQC), f1(r("pendB")), r("pendF"), Null, Null)
      val bs = Vector.newBuilder[(String, C)]
      fifoBranches(p, True, iq, (s, g) => bs += s -> g)
      branches = bs.result()
      p
    }

    /** the young role's step (`TwoWayMachine.youngStep`, branch by branch in the same order) */
    def youngStep: Prog = {
      require(young)
      val p = new Prog(0, 0, "", exclusive = true)
      val bs = Vector.newBuilder[(String, C)]
      def when(name: String, g: C)(f: Prog#Upd => Unit): Unit = if (!drop.exists(name.startsWith)) { bs += name -> g; p.when(g)(f) }
      val src = r("src"); val nh = r("nh"); val nph = r("nph"); val w = r("w"); val mid = r("mid"); val p0 = r("p0")
      // the sites: an N node (BN), an N' node (BNP), a FIFO cell (the reversal)
      val nq = p.site(TE.Const(NQ), src, nh, Null, Null)
      val np = p.site(TE.Ite(nul(mid), TE.Const(NPS), TE.Const(NPB)), f1(w), nph, w, Null)
      val iq = p.site(TE.Const(IQC), f1(r("pendB")), r("pendF"), Null, Null)
      when("unborn", not(born)) { _ => () }
      // ---- BN, BNP
      def bn(u: Prog#Upd): Unit = { u.set(n("nh"), nq); u.set(n("src"), pf(src)); u.set(n("rc"), pf(r("rc"))); u.set(n("tx"), Ite(nn(r("rc")), nq, r("tx"))) }
      when("BN-last", And(phIs(BN), nul(pf(src)))) { u => bn(u); phase(u, BNP); u.set(n("w"), nq); u.set(n("mid"), f2(nh)); u.set(n("lastP"), src) }
      when("BN", phIs(BN)) { u => bn(u) }
      def bnp(u: Prog#Upd): Unit = { u.set(n("nph"), np); u.set(n("w"), f2(w)); u.set(n("mid"), f2(f2(mid))) }
      when("BNP-last", And(phIs(BNP), nul(f2(w)))) { u => bnp(u); phase(u, PINIT); u.bit(n("run2"), False); u.bit(n("pr0"), False); u.bit(n("pr1"), False) }
      when("BNP", phIs(BNP)) { u => bnp(u) }
      // ---- PINIT: the registers of a pass (pair 0: all of P; 1: the prefix before la; 2: before lp)
      val run2 = B("run2"); val pr0 = B("pr0"); val pr1 = B("pr1")
      when("PINIT", phIs(PINIT)) { u =>
        val lenNP = Ite(pr1, r("lp"), Ite(pr0, r("la"), Null))
        u.set(n("sn"), f2(nph)); u.set(n("lg"), p0); u.set(n("msn"), nph); u.set(n("jn"), nph); u.set(n("ks"), r("lastP")); u.set(n("kc"), r("lastP"))
        u.set(n("q"), nh); u.set(n("d"), f2(nh)); u.set(n("e"), pf(pf(p0))); u.set(n("pp"), pf(p0)); u.set(n("z"), Null)
        u.set(n("fc"), Ite(Or(pr0, pr1), f2(f2(f3(lenNP))), Null))
        u.set(n("lcd"), Ite(And(run2, B("r1chg")), f2(r("lc")), Null))
        u.set(n("crd"), Ite(And(run2, B("r1crossed")), f2(r("cf")), Null))
        for (b <- Seq("chg", "crossed", "early", "xo", "late")) { u.bit(n(b), False) }
        phase(u, PASS)
      }
      // ---- PASS
      locally {
        val sn = r("sn"); val lg = r("lg"); val z = r("z"); val msn = r("msn"); val d = r("d"); val q = r("q"); val kc = r("kc")
        val prefix = Or(pr0, pr1)
        val finished = And(phIs(PASS), Or(And(prefix, nul(r("fc"))), And(not(prefix), nul(sn))))
        when("pass-end1", And(finished, not(run2))) { u =>
          u.set(n("r1s"), msn); u.set(n("r1k"), r("ks")); u.set(n("r1p"), r("pp")); u.bit(n("r1chg"), B("chg")); u.bit(n("r1crossed"), B("crossed"))
          u.bit(n("run2"), True); phase(u, PINIT)
        }
        val w2 = And(B("chg"), B("late"))
        val ws = Ite(w2, msn, r("r1s")); val wk = Ite(w2, r("ks"), r("r1k")); val wp = Ite(w2, r("pp"), r("r1p"))
        val pair0 = And(not(pr0), not(pr1))
        when("pass-end-single", all(finished, pair0, TagIs(ws, NPS))) { u =>
          u.set(n("ul"), f1(ws)); u.set(n("ule"), f1(ws)); u.set(n("ubn"), f2(f3(ws))); u.set(n("umf"), wk); u.set(n("ume"), wk)
          u.set(n("a"), p0); u.set(n("b"), wp); u.set(n("cnt"), f2(f3(ws))); phase(u, PT)
        }
        when("pass-end-cross", And(finished, pair0)) { u =>
          u.set(n("la"), r("cs")); u.bit(n("pr0"), True); u.bit(n("pr1"), False); u.bit(n("run2"), False); phase(u, PINIT)
        }
        when("pass-end-la", And(finished, pr0)) { u =>
          u.set(n("lp"), Ite(And(not(w2), not(B("r1chg"))), f2(nph), ws)); u.bit(n("pr0"), False); u.bit(n("pr1"), True); u.bit(n("run2"), False); phase(u, PINIT)
        }
        when("pass-end-lp", finished) { u =>
          u.set(n("lu"), ws); u.set(n("ksu"), wk); u.set(n("ppu"), wp); u.set(n("a"), p0); u.set(n("b"), wp); u.set(n("cnt"), f2(f3(ws))); phase(u, UT)
        }
        val atF = nul(z)
        val a = f1(sn)
        val inPass = phIs(PASS)
        def advance(u: Prog#Upd): Unit = {
          u.set(n("sn"), f2(sn)); u.set(n("d"), f2(d)); u.set(n("e"), pf(r("e"))); u.set(n("z"), pf(z))
          u.set(n("fc"), Ite(atF, f2(r("fc")), r("fc"))); u.set(n("lcd"), Ite(atF, f2(r("lcd")), r("lcd"))); u.set(n("crd"), Ite(atF, f2(r("crd")), r("crd")))
          u.bit(n("xo"), ite2(atF, False, B("xo")))
        }
        val lt = all(inPass, not(same(a, lg)), ite2(run2, TagIs(a, 1), TagIs(a, 0)))
        when("pass-lt", lt) { u =>
          u.set(n("jn"), sn); u.set(n("kc"), f1(d)); u.set(n("ks"), f1(d)); u.set(n("pp"), r("e")); u.set(n("q"), nh); u.set(n("lg"), f1(msn)); advance(u)
        }
        when("pass-eq", all(inPass, same(a, lg), nn(pf(kc)))) { u => u.set(n("kc"), pf(kc)); u.set(n("q"), f2(q)); u.set(n("lg"), pf(lg)); advance(u) }
        when("pass-eq-block", And(inPass, same(a, lg))) { u =>
          u.set(n("jn"), sn); u.set(n("kc"), r("ks")); u.set(n("q"), nh); u.set(n("lg"), f1(msn)); advance(u)
        }
        when("pass-gt", inPass) { u =>
          val ns = f2(r("jn")); val q1 = f1(q); val fresh = nul(pf(q1))
          u.set(n("z"), pf(pf(q1))); u.set(n("lc"), f3(sn)); u.bit(n("chg"), True); u.bit(n("late"), nul(r("lcd")))
          val first = And(TagIs(ns, NPB), not(B("crossed")))
          val earlyN = ite2(first, Or(not(B("r1crossed")), nn(r("crd"))), B("early"))
          val openN = Or(first, B("xo"))
          u.bit(n("crossed"), Or(B("crossed"), first)); u.set(n("cf"), Ite(first, f3(sn), r("cf"))); u.bit(n("early"), earlyN)
          u.set(n("cs"), Ite(And(openN, Or(not(run2), earlyN)), ns, r("cs")))
          u.set(n("msn"), ns); u.set(n("jn"), ns); u.set(n("ks"), r("lastP")); u.set(n("kc"), r("lastP")); u.set(n("q"), nh); u.set(n("pp"), pf(p0))
          u.set(n("sn"), f2(ns)); u.set(n("lg"), f1(ns)); u.set(n("d"), f2(nh)); u.set(n("e"), pf(pf(p0)))
          u.set(n("fc"), Ite(fresh, f2(r("fc")), r("fc"))); u.set(n("lcd"), Ite(fresh, f2(r("lcd")), r("lcd"))); u.set(n("crd"), Ite(fresh, f2(r("crd")), r("crd")))
          u.bit(n("xo"), ite2(fresh, False, openN))
        }
      }
      // ---- PT: the single plan's periodicity test, then the instance
      def toMatchSingle(u: Prog#Upd, periodic: C): Unit = {
        u.bit(n("umem"), periodic); u.bit(n("dbl"), False)
        u.set(n("fp"), r("ul")); u.set(n("ce"), r("ule")); u.bit(n("usk"), nn(r("ubn"))); u.set(n("bp"), f2(r("ubn"))); u.bit(n("bok"), True); phase(u, MATCH)
      }
      val av = r("a"); val bv = r("b"); val cnt = r("cnt")
      when("pt-yes", And(phIs(PT), nul(cnt))) { u => toMatchSingle(u, True) }
      when("pt-no", And(phIs(PT), not(same(av, bv)))) { u => toMatchSingle(u, False) }
      when("pt", phIs(PT)) { u => u.set(n("a"), pf(av)); u.set(n("b"), pf(bv)); u.set(n("cnt"), f2(cnt)) }
      // ---- UT, UB: u's periodicity test and bound race, then the races for the search
      val lu = r("lu"); val lp = r("lp"); val la = r("la")
      def toRU(u: Prog#Upd, periodic: Boolean): Unit = {
        u.bit(n("uper"), if (periodic) { True } else { False })
        u.set(n("a1"), f1(lu)); u.set(n("k1"), f1(lp)); u.set(n("a2"), f3(lu)); u.set(n("a3"), lp); u.set(n("k3"), f2(f3(lu))); u.set(n("a4"), f3(lp))
        u.set(n("k4"), if (periodic) { r("ksu") } else { Null }); phase(u, RU)
      }
      when("ut-bound", And(phIs(UT), nul(cnt))) { u => u.set(n("c"), f1(lp)); phase(u, UB) }
      when("ut-no", And(phIs(UT), not(same(av, bv)))) { u => toRU(u, periodic = false) }
      when("ut", phIs(UT)) { u => u.set(n("a"), pf(av)); u.set(n("b"), pf(bv)); u.set(n("cnt"), f2(cnt)) }
      when("ub-yes", And(phIs(UB), nul(r("c")))) { u => toRU(u, periodic = true) }
      when("ub-no", And(phIs(UB), nul(bv))) { u => toRU(u, periodic = false) }
      when("ub", phIs(UB)) { u => u.set(n("b"), pf(bv)); u.set(n("c"), pf(r("c"))) }
      // ---- RU: R_ue (then R_skip), the search's first text node, R_mem
      locally {
        val a1 = r("a1"); val k1 = r("k1"); val a2 = r("a2"); val a3 = r("a3"); val k3 = r("k3"); val a4 = r("a4"); val k4 = r("k4")
        when("ru-end", all(phIs(RU), nul(k1), nul(a1), nul(k3), nul(k4))) { u =>
          u.set(n("rskip"), a2); u.set(n("rmem"), f1(a4))
          u.set(n("tn"), a3); u.set(n("ui"), f1(lu)); u.set(n("ue"), r("rue")); u.bit(n("smem"), False); u.set(n("tb"), f2(f3(a3))); phase(u, SF)
        }
        when("ru", phIs(RU)) { u =>
          val stageA = nn(k1); val stageB = And(nul(k1), nn(a1))
          u.set(n("a1"), pf(a1)); u.set(n("k1"), pf(k1)); u.set(n("rue"), Ite(And(stageA, nul(pf(k1))), pf(a1), r("rue")))
          u.set(n("a2"), Ite(stageB, f2(a2), a2))
          u.set(n("a3"), Ite(nn(k3), f2(a3), a3)); u.set(n("k3"), f2(k3))
          u.set(n("a4"), Ite(nn(k4), f2(a4), a4)); u.set(n("k4"), pf(k4))
        }
      }
      // ---- SF, SB, SK: the search for u in X[ℓ' ..]
      locally {
        val tn = r("tn"); val tE = r("tE"); val ui = r("ui"); val ue = r("ue"); val ub = r("ub"); val tb = r("tb"); val sk = r("sk")
        def notFound(u: Prog#Upd): Unit = {
          u.set(n("ul"), f1(lp)); u.set(n("ule"), f1(lp)); u.set(n("ubn"), f2(f3(lp))); u.bit(n("umem"), False); u.bit(n("dbl"), False)
          u.set(n("fp"), f1(lp)); u.set(n("ce"), f1(lp)); u.bit(n("usk"), True); u.set(n("bp"), f2(f2(f3(lp)))); u.bit(n("bok"), True); phase(u, MATCH)
        }
        def found(u: Prog#Upd, t: E): Unit = {
          u.set(n("tE"), t); u.set(n("ma"), Ite(nul(t), nh, f3(t))); u.set(n("cnt"), Ite(nul(t), f2(f3(lp)), f3(lp))); phase(u, MW)
        }
        def fresh(u: Prog#Upd, t: E, b: E): Unit = {
          u.set(n("tn"), t); u.set(n("ui"), f1(lu)); u.set(n("ue"), r("rue")); u.bit(n("smem"), False); u.set(n("tb"), b); phase(u, SF)
        }
        val sf = phIs(SF); val hitU = same(ui, f1(tn))
        when("sf-none", And(sf, nul(tn))) { u => notFound(u) }
        when("sf-found", all(sf, hitU, nul(pf(ue)), B("smem"))) { u => found(u, f2(tn)) }
        when("sf-back", all(sf, hitU, nul(pf(ue)))) { u => u.set(n("tE"), f2(tn)); u.set(n("ub"), f2(f3(lu))); phase(u, SB) }
        when("sf", And(sf, hitU)) { u => u.set(n("ui"), pf(ui)); u.set(n("ue"), pf(ue)); u.set(n("tn"), f2(tn)) }
        when("sf-mis", sf) { u => fresh(u, f2(tn), f3(tn)) }
        val sb = phIs(SB)
        when("sb-found", And(sb, nul(ub))) { u => found(u, tE) }
        when("sb", And(sb, same(f1(ub), f1(tb)))) { u => u.set(n("ub"), f2(ub)); u.set(n("tb"), f2(tb)) }
        when("sb-mem", And(sb, B("uper"))) { u => u.set(n("tn"), tE); u.set(n("ui"), r("rmem")); u.set(n("ue"), r("ksu")); u.bit(n("smem"), True); phase(u, SF) }
        when("sb-skip", sb) { u => u.set(n("tn"), tE); u.set(n("sk"), r("rskip")); phase(u, SK) }
        val skp = phIs(SK)
        when("sk-none", And(skp, nul(tn))) { u => notFound(u) }
        when("sk-end", And(skp, nul(f2(sk)))) { u => fresh(u, f2(tn), f3(tn)) }
        when("sk", skp) { u => u.set(n("tn"), f2(tn)); u.set(n("sk"), f2(sk)) }
      }
      // ---- MW, XR, RR: μ, x', and the second instance's cells
      locally {
        val ma = r("ma"); val tE = r("tE"); val kmu = r("kmu")
        when("mw-end", And(phIs(MW), nul(cnt))) { u => u.set(n("kmu"), ma); u.set(n("x1"), f1(la)); u.set(n("x2"), f1(tE)); phase(u, XR) }
        when("mw", phIs(MW)) { u => u.set(n("ma"), f2(ma)); u.set(n("cnt"), f2(cnt)) }
        def toRR(u: Prog#Upd, xpA: Boolean): Unit = {
          u.bit(n("xpA"), if (xpA) { True } else { False })
          u.set(n("s1"), f3(la)); u.set(n("t1"), if (xpA) { kmu } else { Null })
          u.set(n("s2"), p0); u.set(n("t2"), pf(f1(kmu)))
          u.set(n("s3"), f1(lp)); u.set(n("s4"), f1(f2(f3(la)))); u.set(n("s5"), p0); u.set(n("t3"), if (xpA) { f1(la) } else { f1(tE) })
          phase(u, RR)
        }
        when("xr-mu", And(phIs(XR), nul(r("x2")))) { u => toRR(u, xpA = false) }
        when("xr-la", And(phIs(XR), nul(r("x1")))) { u => toRR(u, xpA = true) }
        when("xr", phIs(XR)) { u => u.set(n("x1"), pf(r("x1"))); u.set(n("x2"), pf(r("x2"))) }
        val s1 = r("s1"); val t1 = r("t1"); val s2 = r("s2"); val t2 = r("t2"); val s3 = r("s3"); val s4 = r("s4"); val s5 = r("s5"); val t3 = r("t3")
        when("rr-end", all(phIs(RR), nul(t1), nul(t2), nul(t3))) { u =>
          u.set(n("ul"), f1(lp)); u.set(n("ule"), s3); u.set(n("ubn"), f2(f3(lp))); u.bit(n("umem"), True)
          u.set(n("umf"), Ite(B("xpA"), f1(s1), f1(lp))); u.set(n("ume"), s2)
          u.set(n("wl"), f1(la)); u.set(n("D2"), s4); u.set(n("cd"), s5); u.bit(n("flag"), False); u.bit(n("dbl"), True)
          u.set(n("fp"), f1(lp)); u.set(n("ce"), s3); u.bit(n("usk"), True); u.set(n("bp"), f2(f2(f3(lp)))); u.bit(n("bok"), True)
          u.set(n("wfp"), f1(la)); u.bit(n("wsk"), True); u.set(n("wbp"), f2(f2(f3(la))))
          phase(u, MATCH)
        }
        when("rr", phIs(RR)) { u =>
          u.set(n("s1"), Ite(nn(t1), f2(s1), s1)); u.set(n("t1"), f2(t1))
          u.set(n("s2"), Ite(nn(t2), pf(s2), s2)); u.set(n("t2"), pf(t2))
          u.set(n("s3"), Ite(nn(t3), pf(s3), s3)); u.set(n("s4"), Ite(nn(t3), pf(s4), s4)); u.set(n("s5"), Ite(nn(t3), pf(s5), s5)); u.set(n("t3"), pf(t3))
        }
      }
      // ---- MATCH: the replay first, then the FIFO
      val tx = r("tx")
      when("match-replay", And(phIs(MATCH), nn(tx))) { u => u.set(n("tx"), f2(tx)); matchLetter(u, f1(tx)) }
      fifoBranches(p, phIs(MATCH), iq, (s, g) => bs += s -> g)
      branches = bs.result()
      p
    }

    /** the young role's reset at a birth (the birth cell is P[0], the replay countdown the saved dyadic pointer) */
    def reset(u: Prog#Upd, rc: E): Unit = {
      require(young)
      // a register set twice in one update keeps the first value, so the three sources are left out of the clearing
      val sources = Set(n("src"), n("p0"), n("rc"))
      for ((x, _) <- regs if !sources(x)) { u.set(x, Null) }
      for (b <- bits) { u.bit(b, False) }
      u.set(n("src"), In); u.set(n("p0"), In); u.set(n("rc"), rc)
    }
  }

  val birth: C = Or(Bit("b0"), Bit("b1"))

  /** on a birth the old role takes the young role's matcher and FIFO */
  def handover(y: Role, o: Role): Prog = {
    val p = new Prog(0, 0, "", exclusive = true)
    p.when(birth) { u =>
      for (x <- mInp ++ mNq ++ fifo) { u.set(o.n(x), Reg(y.n(x))) }
      for (b <- mBits) { u.bit(o.n(b), Bit(y.n(b))) }
      u.bit(o.n("acc"), False)
    }
    p
  }

  def youngIn(y: Role, sc: OverlapPal.Sched): Prog = {
    val p = new Prog(0, 0, "", exclusive = true)
    p.when(birth) { u => y.reset(u, Ite(Bit("b0"), OverlapPal.rcOf(0, sc), OverlapPal.rcOf(1, sc))) }
    y.pushes(p, not(birth))
    p
  }

  def oldIn(o: Role): Prog = { val p = new Prog(0, 0, "", exclusive = true); o.pushes(p, True); p }

  def machine(ky: Int, ko: Int, minAnchor: Int, sc: OverlapPal.Sched, drop: Set[String] = Set()): Lps.Machine = {
    val y = new Role("y_", young = true, drop)
    val o = new Role("o_", young = false)
    val regs = OverlapPal.ctlRegsOf(sc) ++ y.regs ++ o.regs
    val bits = OverlapPal.ctlBitsOf(minAnchor, sc) ++ y.bits ++ o.bits
    val names = regs.map(_._1)
    val in = Lpm.fuse(Lpm.fuse(handover(y, o).build(names, bits), youngIn(y, sc).build(names, bits)), oldIn(o).build(names, bits))
    val slots = Map("CT" -> OverlapPal.ct(minAnchor, sc).build(names, bits), "IN" -> in, "YU" -> y.youngStep.build(names, bits),
      "OU" -> o.oldStep.build(names, bits), "OUT" -> OverlapPal.outOf(Bit("o_acc")).build(names, bits))
    val schedule = Vector("CT", "IN") ++ Vector.fill(ky)("YU") ++ Vector.fill(ko)("OU") ++ Vector("OUT")
    Lps.Machine("ab", regs, bits, Set("out"), "out", schema, schedule, slots)
  }
}
