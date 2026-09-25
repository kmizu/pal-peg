package research

/** The pools plan (`POOLS.md` §2): the overlap recognizer of `OverlapPal` with per-*role* register sets instead of
  * per-bank ones. At every tick ≥ 3W₀/2 one bank is young (building, replaying, catching up to its first answer) and
  * the other old (answering). `OverlapPal` gives each bank its own registers and its own copies of the universal step,
  * so the schedule carries the sum of the two banks' step budgets; here the young bank always lives in `y_*` and the
  * old one in `o_*`, and at every birth (the young bank's first answering tick) the young registers are copied into the
  * old ones and the young ones are reset for the newborn bank. The schedule is
  *
  *   CT, IN, YU × ky, OU × ko, OUT
  *
  * with `YU` the full `GapKmp` step over `y_` and `OU` its match-only form over `o_` (the old bank has finished its
  * build: `OverlapProfile`'s strict check). Both roles use one tag set, so the old registers can hold the cells the
  * young steps allocated. The controller, the anchors, the replay and the fallback are `OverlapPal`'s. Behaviourally,
  * `machine(ky, ko, W₀)` is `OverlapPal.machine(ky, ky, W₀, GapCore, oldLimit = ko)` (the same steps per tick for the
  * same bank); `RolePalSuite` checks that equality tick by tick.
  */
object RolePal {
  import Lpm.{C, Prog}
  import Lpm.E.*
  import Lpm.C.*
  import Lpm.TE.Const
  import Lps.*
  import LpsPal.Mod

  val T: GapKmp.Tags = GapKmp.bankTags(0)
  def tagsOf(o: Options): GapKmp.Tags = if (o.rearInP3) { GapKmp.roleTagsP3 } else { T }
  private def not(c: C): C = Not(c)
  /** a bank is born this tick (CT's b0 or b1): the handover tick */
  val birth: C = Or(Bit("b0"), Bit("b1"))
  /** size options (POOLS.md §4): `youngReport = false` drops the young role's `acc` (never read: the old role's push
    * clears it on the handover tick); `mergeTmp` merges `rv`/`ks`; `fuseIn` composes the first old and young steps into IN */
  final case class Options(youngReport: Boolean = true, mergeTmp: Boolean = false, fuseIn: Boolean = false, compact: Boolean = false,
                           sharedRK: Boolean = false, endAsEntry: Boolean = false, endOnce: Boolean = false, rearInP3: Boolean = false) {
    require(!endOnce || compact, "endOnce needs the compact states")
  }
  /** the registers and bits the handover copies from the young role to the old one */
  def copiedRegs(o: Options = Options()): Vector[String] =
    Vector("cur", "L", "K", "R") ++ (if (o.mergeTmp) { Vector("tmp") } else { Vector("rv", "ks") }) ++ Vector("end", "pendF", "pendB", "pendN")
  def copiedBits(o: Options = Options()): Vector[String] = (if (o.youngReport) { Vector("acc") } else { Vector() }) ++ Vector("rev") ++
    (if (o.compact) { Vector("q0", "q1") } else { Vector("p0", "p1", "p2", "s0", "s1") })

  /** one role: module `px` (3 phase bits, 2 sub bits) over the one tag set; the old role is match-only */
  final class Role(val px: String, val young: Boolean, opts: Options = Options()) {
    val M = if (opts.compact) { new Mod(px, 0, 0, True) } else { new Mod(px, 3, 2, True) }
    import M.{r, B}
    def n(x: String): String = px + x
    val report: Boolean = !young || opts.youngReport
    val tags: GapKmp.Tags = tagsOf(opts)
    private def mk(endStep: Boolean) = new GapKmp.Core(M, tags, GapKmp.Feed(n("src"), n("rc"), n("pendF"), n("pendB"), n("pendN"), n("rev")),
      matchOnly = !young, report = report, mergeTmp = opts.mergeTmp, compact = opts.compact, sharedRK = opts.sharedRK,
      endAsEntry = opts.endAsEntry, endStep = endStep)
    val core = mk(endStep = true)
    /** `endOnce`: the steps after the first young step of a tick leave the end of the build to the next tick's first step */
    val coreRest = if (young && opts.endOnce) { mk(endStep = false) } else { core }
    val regs: Vector[(String, Set[K])] = core.regs ++
      (if (young) { Vector(n("src") -> Set[K](KInp), n("rc") -> Set[K](KInp)) } else { Vector() }) ++
      Vector("pendF", "pendB", "pendN").map(q => n(q) -> Set[K](KTag(tags.IQC)))
    val bits: Vector[String] = core.bits ++ M.ctlBits ++ Vector(n("rev"))
    val queueEmpty: C = all(IsNull(r("pendF")), IsNull(r("pendB")), IsNull(r("pendN")), not(B("rev")))
    val notIdle: C = core.born
    /** this tick's letter joins the FIFO: directly at the front only in MATCH/MPOP with an empty queue, otherwise at the
      * back (as `OverlapPal.Bank.in`); one shared allocation site for the three exclusive pushes */
    def pushes(p: Prog, g0: C): Unit = {
      val gF = all(g0, queueEmpty, core.matchSide)
      val gB = all(g0, not(gF), notIdle, not(B("rev")))
      val gN = all(g0, not(gF), not(gB), notIdle)
      val q = p.site(Const(tags.IQC), In, Ite(gB, r("pendB"), Ite(gN, r("pendN"), Null)), Null, Null)
      p.when(gF) { u => u.set(n("pendF"), q); if (report) { u.bit(n("acc"), False) } }
      p.when(gB) { u => u.set(n("pendB"), q); if (report) { u.bit(n("acc"), False) } }
      p.when(gN) { u => u.set(n("pendN"), q); if (report) { u.bit(n("acc"), False) } }
    }
  }

  /** on a birth: the old role takes the young role's state (the retiring old bank is dropped) */
  def handover(y: Role, o: Role, opts: Options = Options()): Prog = {
    val p = new Prog(0, 0, "", exclusive = true)
    p.when(birth) { u =>
      for (x <- copiedRegs(opts)) { u.set(o.n(x), Reg(y.n(x))) }
      for (b <- copiedBits(opts)) { u.bit(o.n(b), Bit(y.n(b))) }
      if (!opts.youngReport) { u.bit(o.n("acc"), False) }
    }
    p
  }

  /** on a birth the young role is reset for the newborn bank (`OverlapPal.Bank.in`: the birth cell is the pattern
    * source, the replay countdown the saved dyadic pointer — the half clock for family A, `pw` for family B);
    * otherwise this tick's letter joins its FIFO */
  def youngIn(y: Role): Prog = {
    val p = y.M.prog()
    p.when(birth) { u =>
      y.core.reset(u); u.set(y.n("src"), In); u.set(y.n("rc"), Ite(Bit("b0"), Reg("clk"), Reg("pw")))
      u.set(y.n("pendF"), Null); u.set(y.n("pendB"), Null); u.set(y.n("pendN"), Null); u.bit(y.n("rev"), False)
    }
    y.pushes(p, not(birth))
    p
  }

  def oldIn(o: Role): Prog = { val p = o.M.prog(); o.pushes(p, True); p }

  def machine(ky: Int, ko: Int, minAnchor: Int = 2, opts: Options = Options()): Lps.Machine = {
    require(ky >= 1 && ko >= 1 && minAnchor >= 2 && ((minAnchor & (minAnchor - 1)) == 0 || OverlapPal.base3(minAnchor)),
      "pools >= 1, minAnchor a power of two >= 2 or 3 * 2^k (k >= 2)")
    val y = new Role("y_", young = true, opts)
    val o = new Role("o_", young = false, opts)
    val regs = OverlapPal.ctlRegs ++ y.regs ++ o.regs
    val bits = OverlapPal.ctlBits(minAnchor) ++ y.bits ++ o.bits
    val names = regs.map(_._1)
    // IN = handover, then the young reset/push, then the old push (sequential: each reads the previous one's updates)
    val in = Lpm.fuse(Lpm.fuse(handover(y, o, opts).build(names, bits), youngIn(y).build(names, bits)), oldIn(o).build(names, bits))
    val yu = y.core.universal(_ => ()).build(names, bits)
    val yuRest = y.coreRest.universal(_ => ()).build(names, bits)
    val ou = o.core.universal(_ => ()).build(names, bits)
    // fuseIn: IN, then the first old step, then the first young step, in one layer (the two roles are disjoint, so the
    // order of an old and a young step does not matter)
    val in1 = if (opts.fuseIn) { Lpm.fuse(Lpm.fuse(in, ou), yu) } else { in }
    // with endOnce the first young step of a tick (fused into IN, or the slot YUF) is the only one that ends a build
    val slots = Map("CT" -> OverlapPal.ct(minAnchor).build(names, bits), "IN" -> in1, "YUF" -> yu, "YU" -> yuRest, "OU" -> ou,
      "OUT" -> OverlapPal.outOf(Bit("o_acc")).build(names, bits))
    val youngSlots = if (opts.fuseIn) { Vector.fill(ky - 1)("YU") } else if (opts.endOnce) { Vector("YUF") ++ Vector.fill(ky - 1)("YU") } else { Vector.fill(ky)("YU") }
    val no = if (opts.fuseIn) { ko - 1 } else { ko }
    val schedule = Vector("CT", "IN") ++ youngSlots ++ Vector.fill(no)("OU") ++ Vector("OUT")
    Lps.Machine("ab", regs, bits, if (opts.compact) { Set("out") } else { Set("out", "y_p0", "y_p2", "o_p0", "o_p2") }, "out", tagsOf(opts).schema,
      schedule, slots.filter { case (k, _) => schedule.contains(k) })
  }
}
