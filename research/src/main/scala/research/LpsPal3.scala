package research

/** `LpsPal2` with the flags worker truncated to the needed interval (Task 3 of the compression plan).
  *
  * The job on `y` (length `m = (r+1)S`) needs the flags of the lengths `lo = rS .. m-1` only. During the
  * matching of `yᴿ` the worker keeps `rem`, a pointer into the input history of length `max(S - D, 0)`
  * where `D = (letters processed) - (match length)`; every step that loses one unit of match moves it
  * one letter back, and `ovf` records `D > S`. At the end `D = m - L` for the longest palindromic prefix
  * `L`, so `length(rem) = L - lo` when `L >= lo`. The failure chain is then walked one gap unit per step
  * and stopped at `lo`: no gaps below `lo` are collected and no flags are skipped.
  */
object LpsPal3 {
  import Lpm.{E, C, TE, Prog, Step}
  import Lpm.E.*
  import Lpm.C.*
  import Lpm.TE.Const
  import Lps.*
  import LpsPal.{Mod, Tags, mTags, fTags, ctlRegs, ctlBits}
  import LpsPal2.{Kmp2, Hooks, Matcher, BUILD, MATCH}

  private def not(c: C): C = Not(c)
  private def below(e: E): E = Fld(e, 2)
  private def prevIn(e: E): E = Fld(Fld(e, 1), 1)
  private def bitOf(k: Int, i: Int): C = if (((k >> i) & 1) == 1) { True } else { False }
  private def Ite2(g: C, a: C, b: C): C = Or(And(g, a), And(Not(g), b))

  val IDLE = 8; val FWD = 9; val CHAIN = 10; val COMMIT = 11
  object Flags {
    val M = new Mod("f_", 4, 0, True)
    val T: Tags = fTags
    import M.*
    import T.{IQC, UNARY, GQC, ITER0, ITER1, iterKinds}
    val hooks: Hooks = Hooks(
      matchStart = u => { u.set(n("rem"), r("jhalf")); u.bit(n("ovf"), False) },
      lose = u => { u.set(n("rem"), prevIn(r("rem"))); u.bit(n("ovf"), Or(B("ovf"), IsNull(r("rem")))) },
      unaryAlt = Some((at(CHAIN), r("built"))))
    val core = new Kmp2(M, T, True, keepFull = true, hooks)
    val regs: Vector[(String, Set[K])] = core.regs ++ Vector(n("rc") -> Set(KInp), n("rc0") -> Set(KInp), n("u") -> Set(KInp), n("jhalf") -> Set(KInp),
      n("rem") -> Set(KInp), n("fs") -> Set(KTag(IQC)), n("G") -> Set(KTag(GQC)), n("cnt") -> Set(KTag(UNARY)), n("built") -> Set(KTag(UNARY)),
      "pc0" -> Set(KInp), "pc1" -> Set(KInp)) ++ (for (s <- 0 to 1; k <- 0 to 3) yield s"res${s}_$k" -> iterKinds)
    val bits: Vector[String] = core.bits ++ M.ctlBits ++ Vector(n("js"), n("jr0"), n("jr1"), n("rd0"), n("rd1"), n("pf"), n("ovf"),
      "pp0", "pr0_0", "pr0_1", "pp1", "pr1_0", "pr1_1")
    private def jrIs(k: Int): C = And(if ((k & 1) == 1) { B("jr0") } else { not(B("jr0")) }, if ((k & 2) == 2) { B("jr1") } else { not(B("jr1")) })
    private def rdIs(k: Int): C = And(if ((k & 1) == 1) { B("rd0") } else { not(B("rd0")) }, if ((k & 2) == 2) { B("rd1") } else { not(B("rd1")) })
    private def decRd(u: Prog#Upd): Unit = { for (i <- 0 until 2) { u.bit(n(s"rd$i"), any((1 to 3).map(k => And(rdIs(k), bitOf(k - 1, i)))*)) } }
    private def ivIs(s: Int, k: Int): C = all((0 until 3).map(i => if (((k >> i) & 1) == 1) { Bit(s"iv${s}_$i") } else { Not(Bit(s"iv${s}_$i")) })*)
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
      // the text is exhausted: L <= lo gives the result at once (pf = [L = lo]), else the chain is walked down to lo
      p.when(all(idleM, IsNull(r("rem")))) { u => u.set(n("G"), Null); u.set(n("cnt"), Null); u.bit(n("pf"), not(B("ovf"))); u.phase(COMMIT) }
      p.when(idleM) { u => u.set(n("G"), Null); u.set(n("gw"), Fld(r("cur"), 3)); u.set(n("built"), Null); u.phase(CHAIN) }
    }
    def extra(p: Prog, g: E): Unit = {
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
      // one gap unit per step: `rem` and the current gap `gw` both shrink; `built` (the UNARY site `g`, whose
      // below is `built` in this phase) counts the units walked in the current gap
      val rem1 = prevIn(r("rem"))
      val gw1 = below(r("gw"))
      p.when(all(at(CHAIN), IsNull(rem1), not(IsNull(gw1)))) { u => u.set(n("cnt"), g); u.bit(n("pf"), False); u.phase(COMMIT) }
      p.when(all(at(CHAIN), IsNull(rem1))) { u => u.set(n("G"), gq); u.set(n("cnt"), Null); u.bit(n("pf"), True); u.phase(COMMIT) }
      p.when(all(at(CHAIN), IsNull(gw1))) { u =>
        u.set(n("G"), gq); u.set(n("cur"), Fld(r("cur"), 2)); u.set(n("gw"), Fld(Fld(r("cur"), 2), 3)); u.set(n("built"), Null); u.set(n("rem"), rem1)
      }
      p.when(at(CHAIN)) { u => u.set(n("gw"), gw1); u.set(n("built"), g); u.set(n("rem"), rem1) }
      for (s <- 0 to 1; k <- 0 to 3) {
        p.when(all(at(COMMIT), if (s == 0) { not(B("js")) } else { B("js") }, jrIs(k))) { u => u.set(s"res${s}_$k", it); u.phase(IDLE) }
      }
    }
    def slots(allRegs: Vector[String], allBits: Vector[String]): Map[String, Step] =
      Map("FREQ" -> req.build(allRegs, allBits), "FU" -> core.universal(load, extra).build(allRegs, allBits))
    def schedule(k: Int): Vector[String] = Vector("FREQ") ++ Vector.fill(k)("FU")
  }

  def machine(mSteps: Int = 12, fSteps: Int = 40): Lps.Machine = {
    val ms = Vector(new Matcher(0), new Matcher(1))
    val regs = ctlRegs ++ ms.flatMap(_.regs) ++ Flags.regs
    val bits = ctlBits ++ ms.flatMap(_.bits) ++ Flags.bits
    val names = regs.map(_._1)
    val slots = Map("CT1" -> LpsPal.ct1.build(names, bits), "CT2" -> LpsPal.ct2.build(names, bits), "CT3" -> LpsPal.ct3.build(names, bits)) ++
      ms.flatMap(_.slots(names, bits)) ++ Flags.slots(names, bits)
    val schedule = Vector("CT1") ++ ms.flatMap(_.schedule(mSteps)) ++ Flags.schedule(fSteps) ++ Vector("CT2", "CT3")
    val schema = (mTags :+ fTags).map(_.schema).reduce(_ ++ _)
    Lps.Machine("ab", regs, bits, Set("out", "f_p3"), "out", schema, schedule, slots)
  }
}
