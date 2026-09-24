package research

/** PAL in slot form (DESIGN.md §5–§6): a two-stage dyadic window controller, one window-KMP matcher
  * per stage, and one shared flags worker computing the palindrome flags of each half-window job.
  *
  * Time: tick n reads w[n-1]. A stage is born at tick W = 2^j (j >= 1) with half S = W/2; its
  * matcher starts on the pattern (w.take W)^R read backwards from the birth cell and matches the text
  * w[W..]. Boundaries at W + iS raise the interval i (1..6); intervals 1..4 release the flags job r =
  * i-1 on y = w[W, W+(r+1)S); intervals 2..5 answer, consuming result r = i-2 one flag per tick; the
  * stage dies at interval 6. |w| < 4 is decided by the controller itself.
  */
object LpsPal {
  import Lpm.{E, C, Prog, Step}
  import Lpm.E.*
  import Lpm.C.*
  import Lps.*

  // input tags: a = 0, b = 1, separator = 2. Cell tags are per module (so that a pointer's possible
  // allocation layers are the module's own).
  final class Tags(base: Int) {
    val IQC = base        // queue / forward-view cell: p1 = input cell, p2 = below
    val UNARY = base + 1  // p2 = below
    val ENTRY = base + 2  // p1 = letter cell, p2 = fail entry, p3 = gap root (i - pi_i), p4 = previous entry
    val ENTRY0 = base + 3
    val END = base + 4
    val EQC = base + 5    // entry queue cell: p2 = below, p3 = entry
    val GQC = base + 6    // gap stack cell: p2 = below, p3 = gap root
    val ITER0 = base + 7  // flags iterator: p2 = gap stack, p3 = countdown; ITER1 = currently at a palindrome length
    val ITER1 = base + 8
    val entryKinds: Set[K] = Set(KTag(ENTRY), KTag(ENTRY0), KTag(END))
    val iterKinds: Set[K] = Set(KTag(ITER0), KTag(ITER1))
    val schema: Map[Int, Vector[Set[K]]] = Map(
      IQC -> Vector(Set(KInp), Set(KTag(IQC)), Set(), Set()),
      UNARY -> Vector(Set(), Set(KTag(UNARY)), Set(), Set()),
      ENTRY -> Vector(Set(KInp), entryKinds, Set(KTag(UNARY)), entryKinds),
      ENTRY0 -> Vector(Set(KInp), entryKinds, Set(KTag(UNARY)), entryKinds),
      END -> Vector(Set(KInp), entryKinds, Set(KTag(UNARY)), entryKinds),
      EQC -> Vector(Set(), Set(KTag(EQC)), entryKinds, Set()),
      GQC -> Vector(Set(), Set(KTag(GQC)), Set(KTag(UNARY)), Set()),
      ITER0 -> Vector(Set(), Set(KTag(GQC)), Set(KTag(UNARY)), Set()),
      ITER1 -> Vector(Set(), Set(KTag(GQC)), Set(KTag(UNARY)), Set()))
  }
  val mTags: Vector[Tags] = Vector(new Tags(3), new Tags(12))
  val fTags: Tags = new Tags(21)

  private def nextOf(e: E): E = Fld(e, 1)
  private def below(e: E): E = Fld(e, 2)
  /** the previous input cell (p1 of an input cell is the separator before it, whose p1 is the previous letter) */
  private def prevIn(e: E): E = Fld(Fld(e, 1), 1)
  private def not(c: C): C = Not(c)
  private def bitOf(k: Int, i: Int): C = if (((k >> i) & 1) == 1) { True } else { False }
  private def Ite2(g: C, a: C, b: C): C = Or(And(g, a), And(Not(g), b))

  /** the registers, bits and phase/sub encodings of one module (name prefix `px`); `enable` gates every step */
  final class Mod(val px: String, val phaseBits: Int, val subBits: Int, val enable: C) {
    def r(x: String): E = Reg(px + x)
    def B(x: String): C = Bit(px + x)
    def n(x: String): String = px + x
    def phaseIs(k: Int): C = all((0 until phaseBits).map(i => if (((k >> i) & 1) == 1) { B(s"p$i") } else { Not(B(s"p$i")) })*)
    def subIs(k: Int): C = all((0 until subBits).map(i => if (((k >> i) & 1) == 1) { B(s"s$i") } else { Not(B(s"s$i")) })*)
    def at(k: Int, s: Int = -1): C = if (s < 0) { And(enable, phaseIs(k)) } else { all(enable, phaseIs(k), subIs(s)) }
    def prog(): Prog = new Prog(phaseBits, subBits, px, exclusive = true)
    def setPhaseIf(u: Prog#Upd, g: C, ifTrue: Int, ifFalse: Int): Unit =
      for (i <- 0 until phaseBits) { u.bit(n(s"p$i"), Ite2(g, bitOf(ifTrue, i), bitOf(ifFalse, i))) }
    def setSubIf(u: Prog#Upd, g: C, ifTrue: Int, ifFalse: Int): Unit =
      for (i <- 0 until subBits) { u.bit(n(s"s$i"), Ite2(g, bitOf(ifTrue, i), bitOf(ifFalse, i))) }
    val ctlBits: Vector[String] = (0 until phaseBits).map(i => n(s"p$i")).toVector ++ (0 until subBits).map(i => n(s"s$i")).toVector
  }

  val BUILD = 0; val BACK = 1; val REV = 2; val MATCH = 3; val MBACK = 4

  /** The KMP table builder and matcher of `LpsKmp`, over the registers of module `M`. The module's own
    * letter-source slot puts the next pattern letter into `c` (or sets `eop` at the end of the pattern) and
    * the next text letter into `c`, each time moving sub 0 -> 1. `atFrontier` is the condition under which
    * a full match is reported in `acc`. With `keepFull`, a full match is kept as the state END (the flags
    * job, whose text ends there) instead of being backed up. */
  final class Core(M: Mod, T: Tags, atFrontier: C, keepFull: Boolean) {
    import M.*
    import T.*
    val regNames: Vector[String] = Vector("c", "ent", "cur", "pFail", "front", "rear", "gap", "gw", "pRoot")
    val regs: Vector[(String, Set[K])] = Vector(n("c") -> Set(KInp), n("ent") -> entryKinds, n("cur") -> entryKinds,
      n("pFail") -> entryKinds, n("front") -> Set(KTag(EQC)), n("rear") -> Set(KTag(EQC)), n("gap") -> Set(KTag(UNARY)),
      n("gw") -> Set(KTag(UNARY)), n("pRoot") -> Set(KTag(UNARY)))
    val bits: Vector[String] = Vector(n("acc"), n("erev"), n("eop"), n("again"))
    def reset(u: Prog#Upd): Unit = { regNames.foreach(x => u.set(n(x), Null)); bits.foreach(b => u.bit(b, False)); u.phase(BUILD) }

    def fin: Prog = {
      val p = prog()
      p.when(all(at(BUILD, 1), not(B("eop")), IsNull(r("ent")))) { u => u.set(n("ent"), u.alloc(ENTRY0, r("c"), Null, Null, Null)); u.set(n("cur"), Made); u.sub(2) }
      p.when(all(at(BUILD, 1), not(B("eop")), not(IsNull(r("ent"))))) { u => u.set(n("ent"), u.alloc(ENTRY, r("c"), r("pFail"), r("pRoot"), r("ent"))); u.sub(2) }
      p.when(And(at(BUILD, 1), B("eop"))) { u =>
        u.set(n("ent"), u.alloc(END, Null, r("pFail"), r("pRoot"), r("ent"))); u.set(n("cur"), Made); u.set(n("front"), Null); u.phase(REV)
      }
      p
    }
    def gap: Prog = {
      val p = prog()
      p.when(And(at(BUILD, 2), IsNull(r("gap")))) { u => u.set(n("gap"), u.alloc(UNARY, Null, Null, Null, Null)); u.set(n("pFail"), r("cur")); u.set(n("pRoot"), Made); u.sub(0) }
      p
    }
    def enq: Prog = {
      val p = prog()
      p.when(And(at(BUILD, 2), not(IsNull(r("gap"))))) { u => u.set(n("rear"), u.alloc(EQC, Null, r("rear"), r("ent"), Null)); u.sub(3) }
      p
    }
    def step: Prog = {
      val p = prog()
      val same = SameTag(r("c"), nextOf(r("cur")))
      p.when(all(at(BUILD, 3), same, not(B("erev")), not(IsNull(r("front"))))) { u =>
        u.set(n("cur"), Fld(r("front"), 3)); u.set(n("front"), below(r("front"))); u.set(n("pFail"), Fld(r("front"), 3)); u.set(n("pRoot"), r("gap")); u.sub(0)
      }
      p.when(all(at(BUILD, 3), same, Or(B("erev"), And(IsNull(r("front")), not(IsNull(r("rear"))))))) { u =>
        val last = IsNull(below(r("rear")))
        u.set(n("front"), u.alloc(EQC, Null, r("front"), Fld(r("rear"), 3), Null)); u.set(n("rear"), below(r("rear"))); u.bit(n("erev"), not(last))
      }
      p.when(all(at(BUILD, 3), not(same), TagIs(r("cur"), ENTRY0))) { u =>
        u.set(n("gap"), u.alloc(UNARY, Null, r("gap"), Null, Null)); u.set(n("pFail"), r("cur")); u.set(n("pRoot"), Made); u.sub(0)
      }
      p.when(all(at(BUILD, 3), not(same), not(TagIs(r("cur"), ENTRY0)))) { u => u.set(n("gw"), Fld(r("cur"), 3)); u.phase(BACK) }
      p.when(And(at(REV), IsNull(r("cur")))) { u => u.set(n("cur"), Fld(r("front"), 3)); u.set(n("front"), below(r("front"))); u.phase(MATCH) }
      p.when(all(at(MATCH, 1), same)) { u =>
        val nxt = Fld(r("front"), 3)
        val full = TagIs(nxt, END)
        u.set(n("cur"), nxt); u.set(n("front"), below(r("front")))
        u.bit(n("acc"), Or(B("acc"), And(full, atFrontier)))
        if (keepFull) { u.phase(MATCH) } else { setPhaseIf(u, full, MBACK, MATCH); u.sub(0) }
        u.set(n("gw"), Fld(nxt, 3)); u.bit(n("again"), False)
      }
      p.when(all(at(MATCH, 1), not(same), TagIs(r("cur"), ENTRY0))) { u => u.sub(0) }
      p.when(all(at(MATCH, 1), not(same), not(TagIs(r("cur"), ENTRY0)))) { u => u.set(n("gw"), Fld(r("cur"), 3)); u.bit(n("again"), True); u.phase(MBACK) }
      p
    }
    def ba: Prog = {
      val p = prog()
      p.when(at(BACK, 0)) { u => u.set(n("front"), u.alloc(EQC, Null, r("front"), r("cur"), Null)); u.set(n("cur"), Fld(r("cur"), 4)); u.sub(1) }
      p.when(And(at(REV), not(IsNull(r("cur"))))) { u => u.set(n("front"), u.alloc(EQC, Null, r("front"), r("cur"), Null)); u.set(n("cur"), Fld(r("cur"), 4)) }
      p.when(at(MBACK)) { u =>
        u.set(n("front"), u.alloc(EQC, Null, r("front"), r("cur"), Null)); u.set(n("cur"), Fld(r("cur"), 4)); u.set(n("gw"), below(r("gw")))
        val done = IsNull(below(r("gw")))
        setPhaseIf(u, done, MATCH, MBACK)
        setSubIf(u, And(done, B("again")), 1, 0)
        u.bit(n("again"), Ite2(done, False, B("again")))
      }
      p
    }
    def bb: Prog = {
      val p = prog()
      p.when(at(BACK, 1)) { u =>
        u.set(n("gap"), u.alloc(UNARY, Null, r("gap"), Null, Null)); u.set(n("gw"), below(r("gw")))
        setPhaseIf(u, IsNull(below(r("gw"))), BUILD, BACK); setSubIf(u, IsNull(below(r("gw"))), 3, 0)
      }
      p
    }
    /** the slot names of one group of the schedule (the module's letter source is `pfx + "CONS"`) */
    def group(pfx: String): Vector[String] = Vector("CONS", "FIN", "GAP", "ENQ", "STEP", "BA", "BB", "STEP", "BA", "BB").map(pfx + _)
    def slots(pfx: String, allRegs: Vector[String], allBits: Vector[String]): Map[String, Step] = Map(
      pfx + "FIN" -> fin.build(allRegs, allBits), pfx + "GAP" -> gap.build(allRegs, allBits), pfx + "ENQ" -> enq.build(allRegs, allBits),
      pfx + "STEP" -> step.build(allRegs, allBits), pfx + "BA" -> ba.build(allRegs, allBits), pfx + "BB" -> bb.build(allRegs, allBits))
  }

  // ---- controller -----------------------------------------------------------------------------

  val ctlRegs: Vector[(String, Set[K])] = Vector("pw" -> Set(KInp), "nb" -> Set(KInp),
    "half0" -> Set(KInp), "clk0" -> Set(KInp), "half1" -> Set(KInp), "clk1" -> Set(KInp),
    "itG" -> Set(KTag(fTags.GQC)), "itC" -> Set(KTag(fTags.UNARY)))
  val ctlBits: Vector[String] = Vector("ready", "slot", "sm0", "sm1", "sm2", "first", "birth", "b0", "b1",
    "alive0", "iv0_0", "iv0_1", "iv0_2", "bnd0", "rel0", "alive1", "iv1_0", "iv1_1", "iv1_2", "bnd1", "rel1", "pf", "out")

  private def ivIs(s: Int, k: Int): C = all((0 until 3).map(i => if (((k >> i) & 1) == 1) { Bit(s"iv${s}_$i") } else { Not(Bit(s"iv${s}_$i")) })*)
  private def smIs(k: Int): C = all((0 until 3).map(i => if (((k >> i) & 1) == 1) { Bit(s"sm$i") } else { Not(Bit(s"sm$i")) })*)
  /** the first case whose guard holds, else the default (one expression: a bit may be updated only once per guard) */
  private def select(cases: Seq[(C, C)], default: C): C = cases.foldRight(default) { case ((g, v), acc) => Ite2(g, v, acc) }
  def answering(s: Int): C = And(Bit(s"alive$s"), any((2 to 5).map(k => ivIs(s, k))*))

  /** the clocks: births, half-window boundaries, intervals, releases; the short-word counter */
  /** `gate` enables a stage's birth (default: always); `warm` are the birth-counter bits wb_1, wb_2, … maintained here:
    * wb_1 is set by the first birth, wb_{i+1} by a birth that finds wb_i set (the birth at tick 2^j sets wb_j) */
  def ct1(gate: C = True, warm: Vector[String] = Vector()): Prog = {
    val p = new Prog(0, 0, "", exclusive = true)
    val nbNext = prevIn(Reg("nb"))
    val birth = And(Bit("ready"), IsNull(nbNext))
    p.when(True) { u =>
      for ((wb, i) <- warm.zipWithIndex) { u.bit(wb, Or(Bit(wb), if (i == 0) { birth } else { And(Bit(warm(i - 1)), birth) })) }
      // |w| counter saturating at 4, and the first letter
      for (i <- 0 until 3) { u.bit(s"sm$i", select((0 to 3).map(k => (smIs(k), bitOf(k + 1, i))), Bit(s"sm$i"))) }
      u.bit("first", Ite2(smIs(0), TagIs(In, 1), Bit("first")))
      u.bit("ready", True)
      u.set("pw", Ite(Or(not(Bit("ready")), birth), In, Reg("pw")))
      u.set("nb", Ite(Or(not(Bit("ready")), birth), In, nbNext))
      u.bit("birth", birth)
      u.bit("slot", Ite2(birth, not(Bit("slot")), Bit("slot")))
      for (s <- 0 to 1) {
        val born = all(birth, if (s == 0) { not(Bit("slot")) } else { Bit("slot") }, gate)
        val alive = Bit(s"alive$s")
        val clkNext = prevIn(Reg(s"clk$s"))
        val bnd = all(not(born), alive, IsNull(clkNext))
        u.bit(s"b$s", born)
        u.set(s"half$s", Ite(born, Reg("pw"), Reg(s"half$s")))
        u.set(s"clk$s", Ite(born, Reg("pw"), Ite(bnd, Reg(s"half$s"), Ite(alive, clkNext, Reg(s"clk$s")))))
        u.bit(s"bnd$s", bnd)
        u.bit(s"rel$s", And(bnd, any((0 to 3).map(k => ivIs(s, k))*)))
        for (i <- 0 until 3) { u.bit(s"iv${s}_$i", select(((born, bitOf(0, i))) +: (0 to 5).map(k => (And(bnd, ivIs(s, k)), bitOf(k + 1, i))), Bit(s"iv${s}_$i"))) }
        u.bit(s"alive$s", Or(born, And(alive, not(And(bnd, ivIs(s, 5))))))
      }
    }
    p
  }

  /** advance the flags iterator (itG, itC, pf) by one length */
  private def advance(u: Prog#Upd, G: String, Cn: String, pf: String): Unit = {
    val g = below(Fld(Reg(G), 3))
    val c = below(Reg(Cn))
    val arrived = Bit(pf)
    u.set(Cn, Ite(arrived, Ite(IsNull(Reg(G)), Null, g), c))
    u.set(G, Ite(arrived, below(Reg(G)), Reg(G)))
    u.bit(pf, Ite2(arrived, And(not(IsNull(Reg(G))), IsNull(g)), And(not(IsNull(Reg(Cn))), IsNull(c))))
  }

  /** the answer: load the result iterator at an answering boundary, else advance it; combine with the matcher */
  def ct2: Prog = {
    val p = new Prog(0, 0, "", exclusive = true)
    for (s <- 0 to 1; k <- 2 to 5) {
      val it = Reg(s"res${s}_${k - 2}")
      p.when(all(Bit(s"bnd$s"), Bit(s"alive$s"), ivIs(s, k))) { u =>
        u.set("itG", below(it)); u.set("itC", Fld(it, 3)); u.bit("pf", TagIs(it, fTags.ITER1))
      }
    }
    p.when(Or(answering(0), answering(1))) { u => advance(u, "itG", "itC", "pf") }
    p
  }
  def ct3: Prog = {
    val p = new Prog(0, 0, "", exclusive = true)
    val short = Or(smIs(1), And(Or(smIs(2), smIs(3)), Ite2(TagIs(In, 1), Bit("first"), not(Bit("first")))))
    val ordinary = Or(And(answering(0), Bit("m0_acc")), And(answering(1), Bit("m1_acc")))
    p.when(True) { u => u.bit("out", Ite2(smIs(4), And(ordinary, Bit("pf")), short)) }
    p
  }

  // ---- stage matchers ---------------------------------------------------------------------------

  final class Matcher(val s: Int) {
    val M = new Mod(s"m${s}_", 3, 2, And(Bit(s"alive$s"), not(Bit(s"m${s}_wait"))))
    val T: Tags = mTags(s)
    import M.*
    import T.{IQC}
    val queueEmpty: C = all(IsNull(r("pendF")), IsNull(r("pendB")), IsNull(r("pendN")), not(B("rev")))
    val core = new Core(M, T, queueEmpty, keepFull = false)
    val regs: Vector[(String, Set[K])] = core.regs ++ Vector(n("src") -> Set(KInp), n("pendF") -> Set(KTag(IQC)), n("pendB") -> Set(KTag(IQC)), n("pendN") -> Set(KTag(IQC)))
    val bits: Vector[String] = core.bits ++ M.ctlBits ++ Vector(n("wait"), n("rev"))
    /** birth: reset and take the birth cell as the pattern source; otherwise queue the letter as text */
    def in: Prog = {
      val p = prog()
      p.when(Bit(s"b$s")) { u =>
        core.reset(u); u.set(n("src"), In); u.set(n("pendF"), Null); u.set(n("pendB"), Null); u.set(n("pendN"), Null); u.bit(n("wait"), False); u.bit(n("rev"), False)
      }
      p.when(And(Bit(s"alive$s"), queueEmpty)) { u => u.set(n("pendF"), u.alloc(IQC, In, Null, Null, Null)); u.bit(n("wait"), False); u.bit(n("acc"), False) }
      p.when(all(Bit(s"alive$s"), not(queueEmpty), not(B("rev")))) { u => u.set(n("pendB"), u.alloc(IQC, In, r("pendB"), Null, Null)); u.bit(n("wait"), False); u.bit(n("acc"), False) }
      p.when(all(Bit(s"alive$s"), not(queueEmpty), B("rev"))) { u => u.set(n("pendN"), u.alloc(IQC, In, r("pendN"), Null, Null)); u.bit(n("wait"), False); u.bit(n("acc"), False) }
      p
    }
    def cons: Prog = {
      val p = prog()
      p.when(And(at(BUILD, 0), not(IsNull(r("src"))))) { u => u.set(n("c"), r("src")); u.set(n("src"), prevIn(r("src"))); u.sub(1) }
      p.when(And(at(BUILD, 0), IsNull(r("src")))) { u => u.bit(n("eop"), True); u.sub(1) }
      p.when(all(at(MATCH, 0), not(B("rev")), not(IsNull(r("pendF"))))) { u => u.set(n("c"), nextOf(r("pendF"))); u.set(n("pendF"), below(r("pendF"))); u.sub(1) }
      p.when(all(at(MATCH, 0), Or(B("rev"), And(IsNull(r("pendF")), not(IsNull(r("pendB"))))))) { u =>
        val last = IsNull(below(r("pendB")))
        u.set(n("pendF"), u.alloc(IQC, nextOf(r("pendB")), r("pendF"), Null, Null))
        u.set(n("pendB"), Ite(last, r("pendN"), below(r("pendB"))))
        u.set(n("pendN"), Ite(last, Null, r("pendN")))
        u.bit(n("rev"), not(last))
      }
      p.when(all(at(MATCH, 0), not(B("rev")), IsNull(r("pendF")), IsNull(r("pendB")))) { u => u.bit(n("wait"), True) }
      p
    }
    def slots(allRegs: Vector[String], allBits: Vector[String]): Map[String, Step] =
      core.slots(s"M$s", allRegs, allBits) ++ Map(s"M${s}IN" -> in.build(allRegs, allBits), s"M${s}CONS" -> cons.build(allRegs, allBits))
    def schedule(groups: Int): Vector[String] = Vector(s"M${s}IN") ++ Vector.fill(groups)(core.group(s"M$s")).flatten
  }

  // ---- shared flags worker ------------------------------------------------------------------

  val IDLE = 8; val FWD = 9; val CHAIN = 10; val SKIP = 11
  object Flags {
    val M = new Mod("f_", 4, 2, True)
    val T: Tags = fTags
    import M.*
    import T.{IQC, UNARY, ENTRY0, GQC, ITER0, ITER1, iterKinds}
    val core = new Core(M, T, True, keepFull = true)
    val regs: Vector[(String, Set[K])] = core.regs ++ Vector(n("rc") -> Set(KInp), n("rc0") -> Set(KInp), n("u") -> Set(KInp), n("jhalf") -> Set(KInp),
      n("fs") -> Set(KTag(IQC)), n("G") -> Set(KTag(GQC)), n("cnt") -> Set(KTag(UNARY)),
      "pc0" -> Set(KInp), "pc1" -> Set(KInp)) ++ (for (s <- 0 to 1; k <- 0 to 3) yield s"res${s}_$k" -> iterKinds)
    val bits: Vector[String] = core.bits ++ M.ctlBits ++ Vector(n("js"), n("jr0"), n("jr1"), n("rd0"), n("rd1"), n("pf"),
      "pp0", "pr0_0", "pr0_1", "pp1", "pr1_0", "pr1_1")
    private def jrIs(k: Int): C = And(if ((k & 1) == 1) { B("jr0") } else { not(B("jr0")) }, if ((k & 2) == 2) { B("jr1") } else { not(B("jr1")) })
    private def rdIs(k: Int): C = And(if ((k & 1) == 1) { B("rd0") } else { not(B("rd0")) }, if ((k & 2) == 2) { B("rd1") } else { not(B("rd1")) })
    /** rounds := rounds - 1 (a round is one walk of `u` from the half to the start) */
    private def decRd(u: Prog#Upd): Unit = { for (i <- 0 until 2) { u.bit(n(s"rd$i"), any((1 to 3).map(k => And(rdIs(k), bitOf(k - 1, i)))*)) } }

    /** requests: at a release, remember the release cell and the job index (set by the controller's clocks) */
    def req: Prog = {
      val p = prog()
      p.when(True) { u =>
        for (s <- 0 to 1) {
          u.set(s"pc$s", Ite(Bit(s"rel$s"), In, Reg(s"pc$s")))
          u.bit(s"pp$s", Or(Bit(s"rel$s"), Bit(s"pp$s")))
          // the job index is the interval before the boundary: iv - 1 after ct1
          for (i <- 0 until 2) { u.bit(s"pr${s}_$i", Ite2(Bit(s"rel$s"), any((1 to 4).map(k => And(ivIs(s, k), bitOf(k - 1, i)))*), Bit(s"pr${s}_$i"))) }
        }
      }
      p
    }
    /** IDLE: take the older stage's request first (the stage that will be reborn next is the older one) */
    def aux: Prog = {
      val p = prog()
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
      // FWD: push the letters of y^R onto the forward-view stack; then start the table on y
      p.when(And(at(FWD), not(IsNull(r("u"))))) { u => u.set(n("fs"), u.alloc(IQC, r("rc"), r("fs"), Null, Null)); u.set(n("rc"), prevIn(r("rc"))); u.set(n("u"), prevIn(r("u"))) }
      p.when(all(at(FWD), IsNull(r("u")), not(rdIs(0)))) { u => decRd(u); u.set(n("u"), r("jhalf")) }
      p.when(all(at(FWD), IsNull(r("u")), rdIs(0))) { u =>
        core.reset(u); u.set(n("rc"), r("rc0")); u.set(n("u"), r("jhalf")); u.bit(n("rd0"), B("jr0")); u.bit(n("rd1"), B("jr1"))
      }
      // CHAIN: push the gap roots of the failure chain of the final state; then skip to lo = r*S
      p.when(And(at(CHAIN), not(TagIs(r("cur"), ENTRY0)))) { u => u.set(n("G"), u.alloc(GQC, Null, r("G"), Fld(r("cur"), 3), Null)); u.set(n("cur"), Fld(r("cur"), 2)) }
      p.when(And(at(CHAIN), TagIs(r("cur"), ENTRY0))) { u => u.bit(n("pf"), True); u.set(n("cnt"), Null); u.set(n("u"), Null); u.bit(n("rd0"), B("jr0")); u.bit(n("rd1"), B("jr1")); u.phase(SKIP) }
      p.when(And(at(SKIP), not(IsNull(r("u"))))) { u => advance(u, n("G"), n("cnt"), n("pf")); u.set(n("u"), prevIn(r("u"))) }
      p.when(all(at(SKIP), IsNull(r("u")), not(rdIs(0)))) { u => decRd(u); u.set(n("u"), r("jhalf")) }
      for (s <- 0 to 1; k <- 0 to 3) {
        p.when(all(at(SKIP), IsNull(r("u")), rdIs(0), if (s == 0) { not(B("js")) } else { B("js") }, jrIs(k))) { u =>
          u.set(s"res${s}_$k", u.alloc(Lpm.TE.Ite(B("pf"), Lpm.TE.Const(ITER1), Lpm.TE.Const(ITER0)), Null, r("G"), r("cnt"), Null)); u.phase(IDLE)
        }
      }
      p
    }
    /** letters: the pattern y from the forward-view stack, the text y^R by walking back from the release cell */
    def cons: Prog = {
      val p = prog()
      p.when(And(at(BUILD, 0), not(IsNull(r("fs"))))) { u => u.set(n("c"), nextOf(r("fs"))); u.set(n("fs"), below(r("fs"))); u.sub(1) }
      p.when(And(at(BUILD, 0), IsNull(r("fs")))) { u => u.bit(n("eop"), True); u.sub(1) }
      p.when(And(at(MATCH, 0), not(IsNull(r("u"))))) { u => u.set(n("c"), r("rc")); u.set(n("rc"), prevIn(r("rc"))); u.set(n("u"), prevIn(r("u"))); u.sub(1) }
      p.when(all(at(MATCH, 0), IsNull(r("u")), not(rdIs(0)))) { u => decRd(u); u.set(n("u"), r("jhalf")) }
      p.when(all(at(MATCH, 0), IsNull(r("u")), rdIs(0))) { u => u.set(n("G"), Null); u.phase(CHAIN) }
      p
    }
    def slots(allRegs: Vector[String], allBits: Vector[String]): Map[String, Step] =
      core.slots("F", allRegs, allBits) ++ Map("FREQ" -> req.build(allRegs, allBits), "FAUX" -> aux.build(allRegs, allBits), "FCONS" -> cons.build(allRegs, allBits))
    /** one group: the auxiliary step, then the letter step */
    def schedule(groups: Int): Vector[String] = Vector("FREQ") ++ Vector.fill(groups)(Vector("FAUX", "FAUX") ++ core.group("F")).flatten
  }

  // ---- the machine ----------------------------------------------------------------------------

  /** `fusion` 0: one slot per micro-step; 1: each group of a worker (letter step + two back-up steps) is one slot;
    * 2: additionally the controller's slots and a worker's IN/REQ are fused into the first group */
  def machine(mGroups: Int = 2, fGroups: Int = 3, fusion: Int = 0): Lps.Machine = {
    val ms = Vector(new Matcher(0), new Matcher(1))
    val regs = ctlRegs ++ ms.flatMap(_.regs) ++ Flags.regs
    val bits = ctlBits ++ ms.flatMap(_.bits) ++ Flags.bits
    val names = regs.map(_._1)
    val slots = Map("CT1" -> ct1().build(names, bits), "CT2" -> ct2.build(names, bits), "CT3" -> ct3.build(names, bits)) ++
      ms.flatMap(_.slots(names, bits)) ++ Flags.slots(names, bits)
    val groups: Vector[Vector[String]] = Vector(Vector("CT1")) ++
      ms.flatMap(mt => Vector(Vector(s"M${mt.s}IN")) ++ Vector.fill(mGroups)(mt.core.group(s"M${mt.s}"))) ++
      Vector(Vector("FREQ")) ++ Vector.fill(fGroups)(Vector("FAUX", "FAUX") ++ Flags.core.group("F")) ++ Vector(Vector("CT2"), Vector("CT3"))
    val schema = (mTags :+ fTags).map(_.schema).reduce(_ ++ _)
    val m = Lps.Machine("ab", regs, bits, Set("out", "f_p3"), "out", schema, groups.flatten, slots)
    fusion match {
      case 0 => m
      case 1 => Lps.fused(m, groups)
      case _ =>
        // merge the single-slot groups into their neighbours: CT1+M0IN+group, M1IN+group, FREQ+group, and CT2+CT3 after the last
        val merged = groups.foldLeft(Vector.empty[Vector[String]]) { (acc, g) =>
          if (g.size == 1 && g.head != "CT3") { acc :+ g } // a lone slot waits for the next group
          else if (acc.nonEmpty && acc.last.size == 1 && acc.last.head != "CT3") { acc.init :+ (acc.last ++ g) }
          else { acc :+ g }
        }
        val merged2 = merged.foldLeft(Vector.empty[Vector[String]]) { (acc, g) => if (acc.nonEmpty && acc.last.size == 1) { acc.init :+ (acc.last ++ g) } else { acc :+ g } }
        Lps.fused(m, merged2)
    }
  }

  def isPal(w: String): Boolean = w == w.reverse
}
