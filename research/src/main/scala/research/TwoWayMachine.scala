package research

import scala.collection.mutable

/** STRATEGY.md §3p: the table-free matcher of §3o as a step machine over the pointer machine's operations, one step being one
  * branch of the Lpm core it is written for (`TwoWayPal`). A step reads the old registers and fields (bounded depth), tests nulls,
  * letters and tags, allocates at most one cell per kind, and writes registers in parallel; nothing compares two positions.
  *
  * The young role of a bank (pattern P = the reversed prefix of length h, text from t0 = n0 - h):
  *   BN    the list N (P backward: N(x) = (P[x], N(x-1))), and the replay start `tx` (the first k = 2h - n0 cells are the text's
  *         first letters in forward order, as in `OverlapPal`)
  *   BNP   the list N' (P forward: N'(x) = (P[x], N'(x+1), N(x)), tagged NPS when 2x <= n, NPB otherwise)
  *   PINIT, PASS  the maximal-suffix passes of `TwoWayPtr.Young.pass` (a pair per order, on P, then on the prefixes ℓ_A and ℓ')
  *   PT    the periodicity test of the single plan
  *   UT, UB, RU, SF, SB, SK, MW, XR, RR  u's periodicity test and bound race, the races for the search, the search for u in
  *         X[ℓ' ..], the walk to μ, the race for x' and the races for the second instance's cells
  *   MATCH the instances (U with the backward check, W without it) and their coupling, on the replay and then the FIFO.
  * The old role is MATCH over the FIFO. Registers are named as in the Lpm core; `ph` is the phase (bits there).
  */
object TwoWayMachine {
  import TwoWayPtr.Cell
  val NQ = 3; val NPS = 4; val NPB = 5; val IQC = 6
  def f1(c: Cell): Cell = if (c == null) { null } else { c.f1 }
  def pf(c: Cell): Cell = f1(c)                                   // one letter forward in the pattern / back in the input
  def f2(c: Cell): Cell = if (c == null) { null } else { c.f2 }
  def f3(c: Cell): Cell = if (c == null) { null } else { c.f3 }
  def same(a: Cell, b: Cell): Boolean = a != null && b != null && a.tag == b.tag

  val BN = 0; val BNP = 1; val PINIT = 2; val PASS = 3; val PT = 4; val UT = 5; val UB = 6; val RU = 7; val SF = 8; val SB = 9
  val SK = 10; val MW = 11; val XR = 12; val RR = 13; val MATCH = 14
  val phaseName: Vector[String] = Vector("BN", "BNP", "PINIT", "PASS", "PT", "UT", "UB", "RU", "SF", "SB", "SK", "MW", "XR", "RR", "MATCH")

  /** the registers the handover copies from the young role to the old one (the matcher and the FIFO) */
  val matchRegs: Vector[String] = Vector("fp", "ce", "bp", "bt", "ul", "ule", "ubn", "umf", "ume", "wfp", "wbp", "wl", "D2", "cd",
    "pendF", "pendB", "pendN")
  val matchBits: Vector[String] = Vector("bok", "usk", "umem", "wsk", "flag", "dbl", "rev")

  final class St(val r: Map[String, Cell], val b: Map[String, Boolean], val ph: Int) {
    def reg(n: String): Cell = r.getOrElse(n, null)
    def bit(n: String): Boolean = b.getOrElse(n, false)
  }

  /** one step: reads see `o`, writes are collected and applied together */
  final class Ctx(o: St) {
    def R(n: String): Cell = o.r.getOrElse(n, null)
    def B(n: String): Boolean = o.b.getOrElse(n, false)
    private val nr = mutable.Map[String, Cell](); private val nb = mutable.Map[String, Boolean]()
    var ph: Int = o.ph
    var branch: String = ""
    private val made = mutable.Set[String]()
    def set(n: String, v: Cell): Unit = { require(!nr.contains(n), s"register $n written twice in $branch"); nr(n) = v }
    def bit(n: String, v: Boolean): Unit = { require(!nb.contains(n), s"bit $n written twice in $branch"); nb(n) = v }
    /** a new cell at the site of `kind` (one per kind a step) */
    def alloc(kind: String, tag: Int, a: Cell, b: Cell, c: Cell): Cell = { require(made.add(kind), s"two $kind cells in $branch"); new Cell(tag, a, b, c) }
    def result: St = new St(o.r ++ nr, o.b ++ nb, ph)
  }

  final class Stats {
    val byBranch: mutable.Map[String, Long] = mutable.Map().withDefaultValue(0L)
    var steps = 0L
  }

  /** the young state of a bank born with the birth cell `birth` (P[0]) and the replay countdown `rc` */
  def born(birth: Cell, rc: Cell): St = new St(Map("src" -> birth, "p0" -> birth, "rc" -> rc), Map(), BN)

  private def pairOf(x: Ctx): Int = (if (x.B("pr0")) { 1 } else { 0 }) + (if (x.B("pr1")) { 2 } else { 0 })
  private def setPair(x: Ctx, k: Int): Unit = { x.bit("pr0", (k & 1) == 1); x.bit("pr1", (k & 2) == 2) }

  /** the pushes of this tick's letter cell `c` (`RolePal.Role.pushes`): straight to the front on the match side with an empty
    * queue, else to the back (or, during a reversal, to the stack behind it) */
  def push(s: St, c: Cell, matchSide: Boolean, report: Boolean): St = {
    val x = new Ctx(s); import x.*
    val empty = R("pendF") == null && R("pendB") == null && R("pendN") == null && !B("rev")
    if (empty && matchSide) { set("pendF", alloc("iq", IQC, c, null, null)) }
    else if (!B("rev")) { set("pendB", alloc("iq", IQC, c, R("pendB"), null)) }
    else { set("pendN", alloc("iq", IQC, c, R("pendN"), null)) }
    if (report) { bit("acc", false) }
    x.result
  }

  /** the handover on a birth: the old role takes the young role's matcher and FIFO */
  def handover(y: St): St = new St(matchRegs.map(n => n -> y.reg(n)).toMap, matchBits.map(n => n -> y.bit(n)).toMap + ("acc" -> false), MATCH)

  /** one letter `c` through the instances (U: the pattern with the backward check, W: the first instance of a double plan) and
    * their coupling; returns the report */
  def matchLetter(x: Ctx, c: Cell): Boolean = {
    import x.*
    def startBackfill(): Unit = { val n = R("ubn"); bit("bok", n == null || same(n.f1, c)); set("bp", f2(n)); set("bt", pf(c)) }
    var ru = false
    if (B("usk")) {
      // waiting: the countdown bp, then the letter that starts the backward check (the forward scan starts on the next letter)
      if (R("bp") != null) { set("bp", R("bp").f2) } else { bit("usk", false); startBackfill() }
    } else {
      val m = same(R("fp"), c)
      if (m && pf(R("ce")) == null) {
        ru = R("bp") == null && B("bok")
        if (B("umem")) { set("fp", R("umf")); set("ce", R("ume")) } else { set("fp", R("ul")); set("ce", R("ule")); bit("usk", true) }
        set("bp", null); bit("bok", true)
      } else if (m) {
        set("fp", pf(R("fp"))); set("ce", pf(R("ce")))
        if (R("bp") != null && B("bok")) { bit("bok", same(R("bp").f1, R("bt"))); set("bp", R("bp").f2); set("bt", pf(R("bt"))) }
      } else { set("fp", R("ul")); set("ce", R("ule")); startBackfill() }
    }
    if (!B("dbl")) { return ru }
    var rw = false; var took = 0
    if (B("wsk")) { if (R("wbp") != null) { set("wbp", R("wbp").f2) } else { bit("wsk", false) } }
    else {
      val m = same(R("wfp"), c)
      if (m && pf(R("wfp")) == null) { rw = true; set("wfp", R("wl")); bit("wsk", true); took = 2 }
      else if (m) { set("wfp", pf(R("wfp"))) }
      else { set("wfp", R("wl")); took = 1 }
    }
    val cd = R("cd"); val hit = cd != null && pf(cd) == null
    val res = rw && (B("flag") || (ru && hit))
    took match {
      case 1 => set("cd", pf(R("D2"))); bit("flag", ru && pf(R("D2")) == null)
      case 2 => set("cd", R("D2")); bit("flag", false)
      case _ => set("cd", pf(cd)); bit("flag", B("flag") || (ru && hit))
    }
    res
  }

  /** the FIFO reversal: one cell of the back stack onto the front (`GapKmp`'s gFifoRev) */
  private def fifoRev(x: Ctx): Unit = {
    import x.*
    val last = f2(R("pendB")) == null
    set("pendF", alloc("iq", IQC, R("pendB").f1, R("pendF"), null))
    set("pendB", if (last) { R("pendN") } else { R("pendB").f2 }); set("pendN", if (last) { null } else { R("pendN") }); bit("rev", !last)
  }

  /** one step of the old role: the next FIFO letter, or a reversal step; the report is kept in `acc` when the letter is the newest */
  def oldStep(s: St, st: Stats): St = {
    val x = new Ctx(s); import x.*
    if (!B("rev") && R("pendF") != null) {
      branch = "old-match"; val c = R("pendF").f1
      val atFrontier = f2(R("pendF")) == null && R("pendB") == null && R("pendN") == null
      set("pendF", R("pendF").f2)
      val res = matchLetter(x, c)
      bit("acc", B("acc") || (res && atFrontier))
    } else if (B("rev") || R("pendB") != null) { branch = "old-fifo"; fifoRev(x) }
    else { branch = "old-idle" }
    st.byBranch(branch) += 1; st.steps += 1
    x.result
  }

  /** one step of the young role */
  def youngStep(s: St, st: Stats, canAlloc: Boolean = true): St = {
    // a slot without the list and FIFO sites stalls on the steps that would store a cell (STRATEGY.md §3p)
    if (!canAlloc && (s.ph == BN || s.ph == BNP || (s.ph == MATCH && s.reg("tx") == null && (s.bit("rev") || (s.reg("pendF") == null && s.reg("pendB") != null))))) {
      st.byBranch("stall") += 1; st.steps += 1; return s
    }
    val x = new Ctx(s); import x.*
    def go(name: String)(body: => Unit): Unit = { branch = name; body }
    s.ph match {
      case BN => go("BN") {
        val src = R("src"); val cell = alloc("nq", NQ, src, R("nh"), null)
        set("nh", cell); set("src", pf(src)); set("rc", pf(R("rc")))
        if (R("rc") != null) { set("tx", cell) }
        if (pf(src) == null) { ph = BNP; set("w", cell); set("mid", f2(R("nh"))); set("lastP", src) }
      }
      case BNP => go("BNP") {
        val w = R("w"); val mid = R("mid")
        val cell = alloc("np", if (mid == null) { NPS } else { NPB }, w.f1, R("nph"), w)
        set("nph", cell); set("w", w.f2); set("mid", f2(f2(mid)))
        if (w.f2 == null) { ph = PINIT; bit("run2", false); setPair(x, 0) }
      }
      case PINIT => go("PINIT") {
        val pair = pairOf(x)
        val lenNP = pair match { case 0 => null; case 1 => R("la"); case _ => R("lp") }
        set("sn", f2(R("nph"))); set("lg", R("p0")); set("msn", R("nph")); set("jn", R("nph")); set("ks", R("lastP")); set("kc", R("lastP"))
        set("q", R("nh")); set("d", f2(R("nh"))); set("e", pf(pf(R("p0")))); set("pp", pf(R("p0"))); set("z", null)
        set("fc", if (lenNP == null) { null } else { f2(f2(lenNP.f3)) })
        set("lcd", if (B("run2") && B("r1chg")) { f2(R("lc")) } else { null })
        set("crd", if (B("run2") && B("r1crossed")) { f2(R("cf")) } else { null })
        for (b <- Seq("chg", "crossed", "early", "xo", "late")) { bit(b, false) }
        ph = PASS
      }
      case PASS =>
        val pair = pairOf(x); val prefix = pair != 0
        val finished = if (prefix) { R("fc") == null } else { R("sn") == null }
        if (finished && !B("run2")) go("pass-end1") {
          set("r1s", R("msn")); set("r1k", R("ks")); set("r1p", R("pp")); bit("r1chg", B("chg")); bit("r1crossed", B("crossed"))
          bit("run2", true); ph = PINIT
        } else if (finished) go("pass-end2") {
          val w2 = B("chg") && B("late")
          val ws = if (w2) { R("msn") } else { R("r1s") }; val wk = if (w2) { R("ks") } else { R("r1k") }; val wp = if (w2) { R("pp") } else { R("r1p") }
          val c0 = !w2 && !B("r1chg")
          pair match {
            case 0 =>
              if (ws.tag == NPS) {
                set("ul", ws.f1); set("ule", ws.f1); set("ubn", f2(ws.f3)); set("umf", wk); set("ume", wk)
                set("a", R("p0")); set("b", wp); set("cnt", f2(ws.f3)); ph = PT
              } else { set("la", R("cs")); setPair(x, 1); bit("run2", false); ph = PINIT }
            case 1 => set("lp", if (c0) { f2(R("nph")) } else { ws }); setPair(x, 2); bit("run2", false); ph = PINIT
            case _ =>
              set("lu", ws); set("ksu", wk); set("ppu", wp); set("a", R("p0")); set("b", wp); set("cnt", f2(ws.f3)); ph = UT
          }
        } else {
          val sn = R("sn"); val z = R("z"); val atF = z == null
          val a = sn.f1; val b = R("lg"); val rev = B("run2")
          val lt = !same(a, b) && (if (rev) { a.tag == 1 } else { a.tag == 0 })
          def fa(): Unit = { set("fc", f2(R("fc"))); set("lcd", f2(R("lcd"))); set("crd", f2(R("crd"))); bit("xo", false) }
          def advance(): Unit = { set("sn", sn.f2); set("d", f2(R("d"))); set("e", pf(R("e"))); if (atF) { fa() } else { set("z", pf(z)) } }
          if (lt) go("pass-lt") {
            set("jn", sn); set("kc", R("d").f1); set("ks", R("d").f1); set("pp", R("e")); set("q", R("nh")); set("lg", R("msn").f1); advance()
          } else if (same(a, b)) go("pass-eq") {
            if (pf(R("kc")) != null) { set("kc", pf(R("kc"))); set("q", R("q").f2); set("lg", pf(b)) }
            else { set("jn", sn); set("kc", R("ks")); set("q", R("nh")); set("lg", R("msn").f1) }
            advance()
          } else go("pass-gt") {
            require(atF, "a restart inside a rescan")
            val ns = R("jn").f2; val q1 = R("q").f1; val fresh = pf(q1) == null
            set("z", pf(pf(q1))); set("lc", sn.f3); bit("chg", true); bit("late", R("lcd") == null)
            val first = ns.tag == NPB && !B("crossed")
            val earlyN = if (first) { !B("r1crossed") || R("crd") != null } else { B("early") }
            val openN = first || B("xo")
            if (first) { bit("crossed", true); set("cf", sn.f3); bit("early", earlyN) }
            if (openN && (!rev || earlyN)) { set("cs", ns) }
            set("msn", ns); set("jn", ns); set("ks", R("lastP")); set("kc", R("lastP")); set("q", R("nh")); set("pp", pf(R("p0")))
            set("sn", ns.f2); set("lg", ns.f1); set("d", f2(R("nh"))); set("e", pf(pf(R("p0"))))
            if (fresh) { fa() } else { bit("xo", openN) }
          }
        }
      case PT =>
        def toMatch(periodic: Boolean): Unit = {
          bit("umem", periodic); bit("dbl", false)
          set("fp", R("ul")); set("ce", R("ule")); bit("usk", R("ubn") != null); set("bp", f2(R("ubn"))); bit("bok", true); ph = MATCH
        }
        if (R("cnt") == null) go("pt-yes") { toMatch(true) }
        else if (!same(R("a"), R("b"))) go("pt-no") { toMatch(false) }
        else go("pt") { set("a", pf(R("a"))); set("b", pf(R("b"))); set("cnt", R("cnt").f2) }
      case UT | UB =>
        def toRU(periodic: Boolean): Unit = {
          bit("uper", periodic)
          val lu = R("lu"); val lp = R("lp")
          set("a1", lu.f1); set("k1", lp.f1); set("a2", lu.f3); set("a3", lp); set("k3", f2(lu.f3)); set("a4", lp.f3)
          set("k4", if (periodic) { R("ksu") } else { null }); ph = RU
        }
        if (s.ph == UT) {
          if (R("cnt") == null) go("ut-bound") { set("c", R("lp").f1); ph = UB }
          else if (!same(R("a"), R("b"))) go("ut-no") { toRU(false) }
          else go("ut") { set("a", pf(R("a"))); set("b", pf(R("b"))); set("cnt", R("cnt").f2) }
        } else {
          if (R("c") == null) go("ub-yes") { toRU(true) }
          else if (R("b") == null) go("ub-no") { toRU(false) }
          else go("ub") { set("b", pf(R("b"))); set("c", pf(R("c"))) }
        }
      case RU =>
        val k1 = R("k1"); val a1 = R("a1")
        val stageA = k1 != null; val stageB = k1 == null && a1 != null
        if (!stageA && !stageB && R("k3") == null && R("k4") == null) go("ru-end") {
          val a3 = R("a3")
          set("rskip", R("a2")); set("rmem", f1(R("a4")))
          set("tn", a3); set("ui", R("lu").f1); set("ue", R("rue")); bit("smem", false); set("tb", f2(a3.f3)); ph = SF
        } else go("ru") {
          if (stageA) { set("a1", pf(a1)); set("k1", pf(k1)); if (pf(k1) == null) { set("rue", pf(a1)) } }
          if (stageB) { set("a1", pf(a1)); set("a2", f2(R("a2"))) }
          if (R("k3") != null) { set("a3", R("a3").f2); set("k3", R("k3").f2) }
          if (R("k4") != null) { set("a4", R("a4").f2); set("k4", pf(R("k4"))) }
        }
      case SF | SB | SK =>
        def fresh(tn: Cell, tb: Cell): Unit = { set("tn", tn); set("ui", R("lu").f1); set("ue", R("rue")); bit("smem", false); set("tb", tb); ph = SF }
        def notFound(): Unit = {
          val lp = R("lp")
          set("ul", lp.f1); set("ule", lp.f1); set("ubn", f2(lp.f3)); bit("umem", false); bit("dbl", false)
          set("fp", lp.f1); set("ce", lp.f1); bit("usk", true); set("bp", f2(f2(lp.f3))); bit("bok", true); ph = MATCH
        }
        def found(tE: Cell): Unit = {
          set("tE", tE); set("ma", if (tE == null) { R("nh") } else { tE.f3 }); set("cnt", if (tE == null) { f2(R("lp").f3) } else { R("lp").f3 }); ph = MW
        }
        val tn = R("tn")
        s.ph match {
          case SF =>
            if (tn == null) go("sf-none") { notFound() }
            else if (same(R("ui"), tn.f1)) {
              // u[i] matched; the forward part is complete when the u-end countdown runs out (i + 1 = m)
              if (pf(R("ue")) == null && B("smem")) go("sf-found") { found(tn.f2) }
              else if (pf(R("ue")) == null) go("sf-back") { set("tE", tn.f2); set("ub", f2(R("lu").f3)); ph = SB }
              else go("sf") { set("ui", pf(R("ui"))); set("ue", pf(R("ue"))); set("tn", tn.f2) }
            } else go("sf-mis") { fresh(tn.f2, tn.f3) }
          case SB =>
            if (R("ub") == null) go("sb-found") { found(R("tE")) }
            else if (same(R("ub").f1, R("tb").f1)) go("sb") { set("ub", R("ub").f2); set("tb", R("tb").f2) }
            else if (B("uper")) go("sb-mem") { set("tn", R("tE")); set("ui", R("rmem")); set("ue", R("ksu")); bit("smem", true); ph = SF }
            else go("sb-skip") { set("tn", R("tE")); set("sk", R("rskip")); ph = SK }
          case _ =>
            if (tn == null) go("sk-none") { notFound() }
            else if (f2(R("sk")) == null) go("sk-end") { fresh(tn.f2, tn.f3) }
            else go("sk") { set("tn", tn.f2); set("sk", f2(R("sk"))) }
        }
      case MW =>
        if (R("cnt") == null) go("mw-end") {
          val tE = R("tE"); val la = R("la")
          set("kmu", R("ma")); set("x1", la.f1); set("x2", f1(tE)); ph = XR
        } else go("mw") { set("ma", R("ma").f2); set("cnt", R("cnt").f2) }
      case XR =>
        def toRR(xpA: Boolean): Unit = {
          val la = R("la"); val kmu = R("kmu")
          bit("xpA", xpA)
          set("s1", la.f3); set("t1", if (xpA) { kmu } else { null })
          set("s2", R("p0")); set("t2", pf(kmu.f1))
          set("s3", R("lp").f1); set("s4", f2(la.f3).f1); set("s5", R("p0")); set("t3", if (xpA) { la.f1 } else { f1(R("tE")) })
          ph = RR
        }
        if (R("x2") == null) go("xr-mu") { toRR(false) }
        else if (R("x1") == null) go("xr-la") { toRR(true) }
        else go("xr") { set("x1", pf(R("x1"))); set("x2", pf(R("x2"))) }
      case RR =>
        if (R("t1") == null && R("t2") == null && R("t3") == null) go("rr-end") {
          val la = R("la"); val lp = R("lp")
          set("ul", lp.f1); set("ule", R("s3")); set("ubn", f2(lp.f3)); bit("umem", true)
          set("umf", if (B("xpA")) { R("s1").f1 } else { lp.f1 }); set("ume", R("s2"))
          set("wl", la.f1); set("D2", R("s4")); set("cd", R("s5")); bit("flag", false); bit("dbl", true)
          set("fp", lp.f1); set("ce", R("s3")); bit("usk", true); set("bp", f2(f2(lp.f3))); bit("bok", true)
          set("wfp", la.f1); bit("wsk", true); set("wbp", f2(f2(la.f3)))
          ph = MATCH
        } else go("rr") {
          if (R("t1") != null) { set("s1", R("s1").f2); set("t1", R("t1").f2) }
          if (R("t2") != null) { set("s2", pf(R("s2"))); set("t2", pf(R("t2"))) }
          if (R("t3") != null) { set("s3", pf(R("s3"))); set("s4", pf(R("s4"))); set("s5", pf(R("s5"))); set("t3", pf(R("t3"))) }
        }
      case _ =>
        val tx = R("tx")
        if (tx != null) go("match-replay") { set("tx", tx.f2); matchLetter(x, tx.f1) }
        else if (!B("rev") && R("pendF") != null) go("match-fifo") { set("pendF", R("pendF").f2); matchLetter(x, R("pendF").f1) }
        else if (B("rev") || R("pendB") != null) go("fifo") { fifoRev(x) }
        else go("idle") { () }
    }
    st.byBranch(branch) += 1; st.steps += 1
    x.result
  }

  /** the young role is ready for the handover: matching, the replay and the FIFO consumed */
  def caughtUp(s: St): Boolean = s.ph == MATCH && s.reg("tx") == null && s.reg("pendF") == null && s.reg("pendB") == null &&
    s.reg("pendN") == null && !s.bit("rev")

  /** the input cells of a word (f1 = the previous cell; `pos` = the index) */
  def inputCells(w: String): Vector[Cell] = {
    val b = Vector.newBuilder[Cell]; var prev: Cell = null
    for (i <- w.indices) { prev = new Cell(if (w(i) == 'a') { 0 } else { 1 }, prev, null, null, i); b += prev }
    b.result()
  }

  /** the plan of pattern p alone (a bank with no replay, run until MATCH): the young state and the steps */
  def planOf(p: String, st: Stats = new Stats): St = {
    val in = inputCells(p.reverse)
    var s = born(in.last, null)
    var guard = 0
    while (s.ph != MATCH) { s = youngStep(s, st); guard += 1; require(guard < 100 * p.length + 100, s"no plan for $p") }
    s
  }

  /** one bank of the overlap recognizer on word w: young from tick h (ky steps a tick), old from n0 (ko steps a tick) to n1;
    * returns (wrong answers, whether the young role was not ready at n0, the young steps used) */
  final case class BankRun(wrong: Int, late: Boolean, youngSteps: Long, plan: St)
  def runBank(w: String, h: Int, n0: Int, n1: Int, ky: Int, ko: Int, st: Stats, kb: Int = Int.MaxValue): BankRun = {
    val in = inputCells(w)
    val t0 = n0 - h; val k = h - t0
    var y = born(in(h - 1), if (k >= 1) { in(k - 1) } else { null })
    var used = 0L
    for (tau <- h until n0) {
      if (tau > h) { y = push(y, in(tau - 1), y.ph == MATCH, report = false) }
      for (i <- 0 until ky) { if (!caughtUp(y)) { used += 1 }; y = youngStep(y, st, canAlloc = i < kb) }
    }
    val late = !caughtUp(y)
    var o = handover(y)
    var wrong = 0
    for (tau <- n0 until math.min(n1, w.length + 1)) {
      o = push(o, in(tau - 1), matchSide = true, report = true)
      for (_ <- 0 until ko) { o = oldStep(o, st) }
      if (o.bit("acc") != LpsPal.isPal(w.take(tau))) { wrong += 1 }
    }
    BankRun(wrong, late, used, y)
  }

  /** the banks of `words` (anchors from W0, schedule sc) with ky young and ko old steps a tick: answers, late young windows, where
    * the young steps go (STRATEGY.md §3p) */
  def probe(words: Vector[String], w0: Int, sc: OverlapPal.Sched, ky: Int, ko: Int, kb: Int = Int.MaxValue): String = {
    val st = new Stats
    var banks = 0; var wrong = 0; var late = 0; var doubles = 0
    var perTickMax = 0.0; var perTickSum = 0.0
    for (w <- words) {
      val as = OverlapPal.anchors(w0, w.length + 1, sc)
      for (i <- as.indices if i + 1 < as.length && as(i + 1) <= w.length) {
        val n1 = if (i + 2 < as.length) { as(i + 2) } else { w.length + 1 }
        val r = runBank(w, as(i), as(i + 1), n1, ky, ko, st, kb)
        banks += 1; wrong += r.wrong; if (r.late) { late += 1 }; if (r.plan.bit("dbl")) { doubles += 1 }
        val pt = r.youngSteps.toDouble / (as(i + 1) - as(i)); perTickMax = math.max(perTickMax, pt); perTickSum += pt
      }
    }
    val tot = st.byBranch.values.sum.toDouble
    val top = st.byBranch.toVector.sortBy(-_._2).map { case (k, v) => f"$k ${100 * v / tot}%.1f%%" }.mkString(", ")
    f"two-way-machine W0=$w0${sc.label}, young $ky (${if (kb < ky) { kb } else { ky }} allocating), old $ko steps a tick: ${words.length} words, $banks banks ($doubles double), " +
      f"wrong $wrong, late $late\n  young steps used a tick of the window: mean ${perTickSum / banks}%.2f, max $perTickMax%.2f\n" +
      s"  steps by branch: $top\n"
  }
}
