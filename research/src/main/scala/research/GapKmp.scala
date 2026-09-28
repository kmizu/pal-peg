package research

/** The binary KMP matcher with persistent gap sequences (under-1000 plan §4), in the amortised form that the
  * shape probe suggests (`probes/gap_shape_probe.py`): every sequence of the abstract matcher is a contiguous
  * range of entries, so a stored gap `h_i` is kept as the *triple* `(hL, hK, hR)` of the live sequence at the
  * time (no copy), and the live sequence is
  *
  *   [[L]] ++ concat(K) ++ reverse(R)
  *
  * with `L` a list of entries whose nodes are `LNODE(e, rest)` = `[e] ++ rest` or `GNODE(e, rest)` = `gap(e) ++
  * [e] ++ rest` (`gap(e) = [[e.hL]] ++ concat(e.hK) ++ reverse(e.hR)`), `K` a stack of pending sequences and `R` a
  * stack `RNODE(e, rest)` of the entries appended last (newest on top). A `KNODE(p1, p2, p3, p4)` holds up to three
  * pendings typed by position — `p4` a `K` stack to splice, `p3` an `R` stack to reverse, `p1` a list — processed
  * in that order, then `p2` (the rest of the stack); so every pointer field has one tag (one allocation site per
  * layer per kind, which keeps the site colouring small). Every step allocates at most one cell per tag; a pop
  * that meets a nested head spends further steps (phases BPOP/MPOP) — worst case Θ(pattern), amortised O(1) per
  * letter by the KMP potential (a pop out of `h_cur` costs at most |h_cur| ≤ the matches since the last jump).
  * There are no failure-chain walks, unary counters or archive reversals; the handover from BUILD to MATCH is one
  * step.
  *
  * States are the entries `E_0 .. E_{m-1}`; "state m" (a full match) is `cur = E_{m-1}` matched with an empty
  * live sequence. The END cell holds the reset state `(f_m, g_m)` as `(LNODE(f_m, L), K, R)`, popped into `cur`
  * after a full match; it is also the state the text matching starts in (the automaton then runs on P ++ text;
  * for |text| >= m — every answering tick of a bank — its state is the state of text alone, since the longest
  * suffix that is a prefix of P has length <= m). A mismatch at `cur` pushes `GNODE(cur, L)` (`h[cur] ++ [cur] ++
  * front`) and pops; a mismatch at an entry with an empty gap (only `E_0`) stays.
  */
object GapKmp {
  import Lpm.{E, C, TE, Prog}
  import Lpm.E.*
  import Lpm.C.*
  import Lpm.TE.Const
  import Lps.*
  import LpsPal.Mod

  val BUILD = 0; val BPOP = 1; val REV = 2; val MATCH = 3; val MPOP = 4
  /** sub-states of a pop in progress: none; the two steps that splice the K stack held in `ks` */
  val S0 = 0; val SSPL1 = 1; val SSPL2 = 2

  /** `rearInP3`: a rear cell keeps its entry in p3 instead of p1 (so that a site shared by rear and stack cells has
    * compatible field kinds: KNODE's p3 is a rear stack) */
  final class Tags(base: Int, rearInP3: Boolean = false) {
    val rearEntry: Int = if (rearInP3) { 3 } else { 1 }
    val IQC = base        // queue cell: p1 = input cell, p2 = below
    val ENTRY = base + 1  // p1 = letter cell, p2 = hL, p3 = hK, p4 = hR
    val END = base + 2    // p2 = LNODE(f_m, L), p3 = K, p4 = R: the reset state (f_m, g_m)
    val LNODE = base + 3  // p1 = entry, p2 = rest
    val GNODE = base + 4  // p1 = entry (its gap comes first), p2 = rest
    val KNODE = base + 5  // p1 = list pending, p2 = rest of the stack, p3 = R stack to reverse, p4 = K stack to splice
    val RNODE = base + 6  // p1 = entry, p2 = rest
    val listKinds: Set[K] = Set(KTag(LNODE), KTag(GNODE))
    val schema: Map[Int, Vector[Set[K]]] = Map(
      IQC -> Vector(Set(KInp), Set(KTag(IQC)), Set(), Set()),
      ENTRY -> Vector(Set(KInp), listKinds, Set(KTag(KNODE)), Set(KTag(RNODE))),
      END -> Vector(Set(), listKinds, Set(KTag(KNODE)), Set(KTag(RNODE))),
      LNODE -> Vector(Set(KTag(ENTRY)), listKinds, Set(), Set()),
      GNODE -> Vector(Set(KTag(ENTRY)), listKinds, Set(), Set()),
      KNODE -> Vector(listKinds, Set(KTag(KNODE)), Set(KTag(RNODE)), Set(KTag(KNODE))),
      RNODE -> (if (rearInP3) { Vector(Set(), Set(KTag(RNODE)), Set(KTag(ENTRY)), Set()) } else { Vector(Set(KTag(ENTRY)), Set(KTag(RNODE)), Set(), Set()) }))
  }
  val bankTags: Vector[Tags] = Vector(new Tags(30), new Tags(40))
  /** the one tag set of RolePal with the p3 rear layout */
  val roleTagsP3: Tags = new Tags(30, rearInP3 = true)

  private def not(c: C): C = Not(c)
  private def selectE(cases: Seq[(C, E)], default: E): E = cases.foldRight(default) { case ((g, v), acc) => Ite(g, v, acc) }

  /** the letter feeds of a bank: the pattern source walk (`src`, with the replay countdown `rc` that prepends the
    * first letters to `pendF`) and the FIFO (`pendF` front, `pendB`/`pendN` back with the reversal flag `rev`) */
  final case class Feed(src: String, rc: String, pendF: String, pendB: String, pendN: String, rev: String)

  /** the matcher core over module `M` (3 phase bits, 2 sub bits) with the letter loads fused into the transitions:
    * a BUILD step consumes the source letter, a MATCH step the FIFO head; a full match is reported when the FIFO
    * is empty after the consumed letter (the letter is the most recent one). `matchOnly` (the old role of the pools
    * plan): only the MATCH/MPOP branches and the FIFO; no BUILD, BPOP, REV, no entry/rear allocation, and the feed's
    * `src`/`rc` are never read — the core takes over a bank that has finished its build. */
  /** `report = false`: the core does not keep `acc` (the young role of the pools plan: its report is never read);
    * `mergeTmp`: `rv` and `ks` share one register `tmp` (never both non-null: `ks` lives in the sub-states SSPL1/2, `rv`
    * in S0), the sub bits tell which */
  /** `compact` (the pools plan's state diet; `M` must then have no phase or sub bits): the phase and sub-state live in
    * the core's own bits `q2 q1 q0` (the match-only core: `q1 q0`) — `q2` = match side (MATCH/MPOP) vs build side
    * (BUILD/BPOP), `q1 q0` = 00 transition state, 01 pop, 10 splice step 1, 11 splice step 2. There is no REV state
    * (the end of the build goes straight to MATCH) and no IDLE state: an unborn young core has `src` and `cur` null, an
    * unborn old core has `cur` null (`born`). */
  /** `sharedRK`: the rear cell of a build step (RNODE) and the stack cell of a pop step (KNODE) come from one allocation
    * site (a step never allocates both), so with `mergeTmp` the register `tmp` points into one site per layer and the
    * colouring does not grow; `endAsEntry`: the END cell is allocated with the ENTRY tag (its letter field null), which
    * drops the tag bit of the entry site */
  /** `unfold` (STRATEGY.md S2'): a mismatch step (BUILD or MATCH) unfolds the gap of `cur` itself instead of pushing
    * `GNODE(cur, L)` for the next pop step to unfold (that step is always `gPopG`): the list-node site allocates
    * `LNODE(cur, L)`, the K-node site (unused by a transition) `KNODE(LNODE, K, cur.hR, cur.hK)`, and `L := cur.hL` — the
    * live sequence gap(cur) ++ [cur] ++ g is the same, one step earlier. Needs separate rear and K-node sites. */
  final class Core(M: Mod, T: Tags, feed: Feed, matchOnly: Boolean = false, report: Boolean = true, mergeTmp: Boolean = false,
                   compact: Boolean = false, sharedRK: Boolean = false, endAsEntry: Boolean = false, endStep: Boolean = true,
                   unfold: Boolean = false, lgap: Boolean = false, merge2: Boolean = false,
                   merge3: Boolean = false, m2off: Set[String] = Set(), noAlloc: Boolean = false, stallPops: Boolean = true)
      extends OverlapPal.BankCore {
    /** the branches of the last `universal` in priority order, with their guards (STRATEGY.md §3e, experiment E3): the pops, then
      * the BUILD and MATCH transitions split by their fast paths, then the FIFO reversal */
    var branches: Vector[(String, C)] = Vector()
    require(endStep || compact, "a step without the end-of-build transition needs the compact states (the pending end is a state)")
    require(!(unfold && sharedRK), "unfold needs the K-node site in a BUILD step, which sharedRK gives to the rear cell")
    /** `noAlloc` (STRATEGY.md §3g): the step has no allocation site; a branch that would store a new cell stalls instead (the
      * exclusive guards keep every later branch out and every register and bit keeps its value), so the cells of a tick are
      * born only at the layers of the full step */
    require(!noAlloc || compact, "a non-allocating step needs the compact states")
    /** `stallPops = false` (STRATEGY.md §3h, a what-if): the non-allocating step keeps the list and K node sites and acts on every
      * pop, stalling only on builds, the end, the FIFO reversal and match mismatches; its rates are those a matcher whose pops
      * store no cell would reach with the same steps (not a candidate grammar: its pops still allocate) */
    private val freePops = noAlloc && !stallPops
    import M.*
    import T.*
    private val rvName = if (mergeTmp) { "tmp" } else { "rv" }
    private val ksName = if (mergeTmp) { "tmp" } else { "ks" }
    val regNames: Vector[String] = if (mergeTmp) { Vector("cur", "L", "K", "R", "tmp", "end") } else { Vector("cur", "L", "K", "R", "rv", "ks", "end") }
    val regs: Vector[(String, Set[K])] = Vector(n("cur") -> Set(KTag(ENTRY)), n("L") -> listKinds,
      n("K") -> Set(KTag(KNODE)), n("R") -> Set(KTag(RNODE))) ++
      (if (mergeTmp) { Vector(n("tmp") -> Set[K](KTag(RNODE), KTag(KNODE))) } else { Vector(n("rv") -> Set[K](KTag(RNODE)), n("ks") -> Set[K](KTag(KNODE))) }) ++
      Vector(n("end") -> Set[K](KTag(if (endAsEntry) { ENTRY } else { END })))
    private val qBits = if (matchOnly) { 2 } else { 3 }
    val bits: Vector[String] = (if (report) { Vector(n("acc")) } else { Vector() }) ++ (if (compact) { (0 until qBits).map(i => n(s"q$i")).toVector } else { Vector() })
    private def q(i: Int): C = B(s"q$i")
    private def qSide: C = if (matchOnly) { True } else { q(2) }
    /** the state predicates in either encoding */
    private def stBuild: C = if (compact) { all(not(qSide), not(q(1)), not(q(0))) } else { at(BUILD) }
    private def stMatch: C = if (compact) { all(qSide, not(q(1)), not(q(0))) } else { at(MATCH) }
    private def stBPop: C = if (compact) { And(not(qSide), Or(q(1), q(0))) } else { at(BPOP) }
    private def stSub(k: Int): C = if (compact) {
      k match { case S0 => And(not(q(1)), q(0)); case SSPL1 => And(q(1), not(q(0))); case _ => And(q(1), q(0)) }
    } else { subIs(k) }
    private def setSub(u: Prog#Upd, k: Int): Unit = if (compact) {
      k match {
        case S0 => u.bit(n("q1"), False); u.bit(n("q0"), True)
        case SSPL1 => u.bit(n("q1"), True); u.bit(n("q0"), False)
        case _ => u.bit(n("q1"), True); u.bit(n("q0"), True)
      }
    } else { u.sub(k) }
    /** the bank exists (born and not reset to idle) and is on the match side: the letter pushes of the modules */
    def born: C = if (compact) { if (matchOnly) { not(IsNull(r("cur"))) } else { Or(not(IsNull(Reg(feed.src))), not(IsNull(r("cur")))) } } else { not(phaseIs(5)) }
    def matchSide: C = if (compact) { And(born, qSide) } else { Or(phaseIs(MATCH), phaseIs(MPOP)) }
    def reset(u: Prog#Upd): Unit = { regNames.foreach(x => u.set(n(x), Null)); bits.foreach(b => u.bit(b, False)); u.phase(BUILD) }

    def universal(load: Prog => Unit): Prog = {
      val cur = r("cur"); val L = r("L"); val K = r("K"); val R = r("R"); val rv = r(rvName); val ks = r(ksName); val end = r("end")
      val src = Reg(feed.src); val rc = Reg(feed.rc); val pendF = Reg(feed.pendF); val pendB = Reg(feed.pendB); val pendN = Reg(feed.pendN)
      val rev = Bit(feed.rev)
      /** the FIFO is empty once the head being consumed is gone: a full match on this letter is at the frontier */
      val atFrontier = all(IsNull(Fld(pendF, 2)), IsNull(pendB), IsNull(pendN), not(rev))
      def prevIn(e: E): E = Fld(Fld(e, 1), 1)
      val popping = if (compact) { Or(q(1), q(0)) } else if (matchOnly) { at(MPOP) } else { Or(at(BPOP), at(MPOP)) }
      val letter = if (matchOnly) { Fld(pendF, 1) } else { Ite(stBuild, src, Fld(pendF, 1)) }
      val same = SameTag(letter, Fld(cur, 1))
      val headIsL = TagIs(L, LNODE)
      val headIsG = TagIs(L, GNODE)
      val gE = Fld(L, 1)                      // the entry of the GNODE at the head of L
      val k1 = Fld(K, 1); val k2 = Fld(K, 2); val k3 = Fld(K, 3); val k4 = Fld(K, 4)
      val liveEmpty = all(IsNull(L), IsNull(K), IsNull(R))
      val kTop = all(IsNull(L), not(IsNull(K)), IsNull(k4), IsNull(k3), TagIs(k1, LNODE))   // the next entry is k1's head
      val rOnly = all(IsNull(L), IsNull(K), not(IsNull(R)), IsNull(Fld(R, 2)))                // the live sequence is the single entry of R
      def rearE(x: E): E = Fld(x, T.rearEntry)                                                    // the entry of a rear cell
      val rvLast = IsNull(Fld(rv, 2))
      // pop-progress guards (priority order)
      val gRev = if (mergeTmp) { all(popping, stSub(S0), not(IsNull(rv))) } else { And(popping, not(IsNull(rv))) }
      val gSpl1 = if (mergeTmp) { And(popping, stSub(SSPL1)) } else { all(popping, IsNull(rv), stSub(SSPL1)) }
      val gSpl2 = if (mergeTmp) { And(popping, stSub(SSPL2)) } else { all(popping, IsNull(rv), stSub(SSPL2)) }
      val gPop0 = all(popping, IsNull(rv), stSub(S0))
      val gPopL = And(gPop0, headIsL)
      val gPopG = And(gPop0, headIsG)
      val gTake = all(gPop0, IsNull(L), not(IsNull(K)))
      val gTakeSpl = And(gTake, not(IsNull(k4)))
      val gTakeRev1 = all(gTake, IsNull(k4), not(IsNull(k3)), IsNull(Fld(k3, 2)))
      val gTakeRev = all(gTake, IsNull(k4), not(IsNull(k3)))
      val gTakePop = all(gTake, IsNull(k4), IsNull(k3), TagIs(k1, LNODE))
      val gTakeList = all(gTake, IsNull(k4), IsNull(k3), not(IsNull(k1)))
      val gTakeEmpty = all(gTake, IsNull(k4), IsNull(k3), IsNull(k1))
      val gPopR1 = And(gPop0, rOnly)
      // merge2 (STRATEGY.md §3e): a pop and the pop that always follows it, in one step and with the same cells — popG whose
      // gap starts with an LNODE (then popL), the last two entries of a reversal (rev then rev-last, takeRev then rev-last)
      val gPopGL = if (merge2 && !m2off("G")) { And(gPopG, TagIs(Fld(gE, 2), LNODE)) } else { False }
      val gRev2 = if (merge2 && !m2off("R") && !m2off("R1")) { all(gRev, not(rvLast), IsNull(Fld(Fld(rv, 2), 2))) } else { False }
      val gTakeRev2 = if (merge2 && !m2off("R") && !m2off("R2")) { all(gTakeRev, not(IsNull(Fld(k3, 2))), IsNull(Fld(Fld(k3, 2), 2))) } else { False }
      // merge3 (STRATEGY.md §3e): the splice of a one-node K stack skips its no-op first step; the splice's last step also takes the
      // list it put on top when takePop would; a matched BUILD step also takes a one-entry reversal from the top of K (takeRev1)
      val gTakeSplSkip = if (merge3) { And(gTakeSpl, IsNull(Fld(k4, 2))) } else { False }
      val gSpl2Pop = if (merge3) { all(gSpl2, IsNull(Fld(ks, 4)), IsNull(Fld(ks, 3)), TagIs(Fld(ks, 1), LNODE)) } else { False }
      val gPopR = all(gPop0, IsNull(L), IsNull(K), not(IsNull(R)))
      // transitions
      val gBuildT = And(stBuild, not(IsNull(src)))
      val gFastT: C = if (merge3 && unfold && !matchOnly) {
        all(gBuildT, same, IsNull(L), not(IsNull(K)), IsNull(k4), not(IsNull(k3)), IsNull(Fld(k3, 2)))
      } else { False }
      val gEnd = if (endStep) { all(stBuild, IsNull(src), not(IsNull(cur))) } else { False }   // otherwise the bank waits in this state
      val gRevPhase = if (compact) { False } else { at(REV) }   // a Mod without phase bits has no at()
      val gMatchT = all(stMatch, not(rev), not(IsNull(pendF)))
      val gFifoRev = And(stMatch, Or(rev, And(IsNull(pendF), not(IsNull(pendB)))))
      val gTrans = if (matchOnly) { gMatchT } else { Or(gBuildT, gMatchT) }
      val p = prog()
      // the options of STRATEGY.md §3e add branches and cases only when they are on: a literal False guard is not compiled away, so
      // it would change (and grow) the grammar of every machine without the option
      def whenOn(g: C)(f: Prog#Upd => Unit): Unit = if (g != False) { p.when(g)(f) }
      def on(cs: Vector[(C, E)]): Vector[(C, E)] = cs.filter(_._1 != False)
      def anyOn(cs: C*): C = any(cs.filter(_ != False)*)
      /** a branch that stores a new cell: with noAlloc it stalls (an empty body) */
      def alloc(g: C)(f: Prog#Upd => Unit): Unit = if (noAlloc) { p.when(g)(_ => ()) } else { p.when(g)(f) }
      /** a pop that stores a new cell: it stalls like `alloc` unless the pops are free (the what-if of §3h) */
      def popAlloc(g: C)(f: Prog#Upd => Unit): Unit = if (freePops) { p.when(g)(f) } else { alloc(g)(f) }
      def popAllocOn(g: C)(f: Prog#Upd => Unit): Unit = if (g != False) { popAlloc(g)(f) }
      /** unfold: a transition that mismatches at an entry with a nonempty gap unfolds it in the same step */
      val gU: C = if (unfold) { all(gTrans, not(same), not(all(IsNull(Fld(cur, 2)), IsNull(Fld(cur, 3)), IsNull(Fld(cur, 4))))) } else { False }
      // shared allocation sites, one per tag: the list node (GNODE for the mismatch sequence of a transition, LNODE
      // otherwise; with unfold GNODE only as the gap of a new entry), the entry (END at the end of the pattern), the K
      // node, the R node, the queue cell
      // lgap (STRATEGY.md §3e): gap(cur) ++ [cur] ++ L is [cur] ++ L when gap(cur) is empty, so the node is an LNODE then: its
      // later pop is one popL instead of a popG followed by a takePop
      val gapful: C = if (lgap) { not(all(IsNull(Fld(cur, 2)), IsNull(Fld(cur, 3)), IsNull(Fld(cur, 4)))) } else { True }
      val lnode: E = if (noAlloc && !freePops) { Null } else p.site(if (unfold && matchOnly) { Const(LNODE) }
        else if (unfold) { TE.Ite(if (lgap) { all(gBuildT, same, gapful) } else { And(gBuildT, same) }, Const(GNODE), Const(LNODE)) }
        else { TE.Ite(if (lgap) { And(gTrans, gapful) } else { gTrans }, Const(GNODE), Const(LNODE)) },
        selectE(Vector((gRev, rearE(rv)), (gPopG, gE), (gPopR, rearE(R)), (gTakeRev, rearE(k3))), cur),
        Ite(gPopG, Fld(L, 2), Ite(Or(gPopR, gTakeRev), Null, L)), Null, Null)
      val entry: E = if (matchOnly || noAlloc) { Null } else { p.site(if (endAsEntry) { Const(ENTRY) } else { TE.Ite(gEnd, Const(END), Const(ENTRY)) },
        Ite(gEnd, Null, src), Ite(gEnd, lnode, Ite(same, lnode, L)), K, R) }
      val uc = if (unfold) { Vector((gU, 0)) } else { Vector() }   // the unfold case comes first: a transition is exclusive of the pops
      val kf1 = selectE(uc.map(c => (c._1, lnode)) ++ Vector((gPopG, lnode), (gSpl2, Fld(ks, 1)), (gSpl1, Null)), k1)
      val kf2 = selectE(uc.map(c => (c._1, K)) ++ Vector((gPopG, K), (gSpl1, K), (gSpl2, K)), k2)
      val kf3 = selectE(uc.map(c => (c._1, Fld(cur, 4))) ++ on(Vector((gFastT, Null))) ++ Vector((gPopG, Fld(gE, 4)), (gSpl2, Fld(ks, 3)), (gSpl1, Null), (gTakeRev, Null)), k3)
      val kf4 = selectE(uc.map(c => (c._1, Fld(cur, 3))) ++ Vector((gPopG, Fld(gE, 3)), (gSpl1, Fld(ks, 2)), (gSpl2, Fld(ks, 4))), Null)
      val rearNext = Ite(all(gBuildT, same, rOnly), Null, R)   // fast4: the rear becomes [E_i]
      def rearFields(e: E, next: E): Vector[E] = if (T.rearEntry == 3) { Vector(Null, next, e, Null) } else { Vector(e, next, Null, Null) }
      val (knode, rnode) = if (freePops) { (p.site(Const(KNODE), kf1, kf2, kf3, kf4), Null) } else if (noAlloc) { (Null, Null) } else if (sharedRK && !matchOnly) {
        val rf = rearFields(entry, rearNext)
        val rk = p.site(TE.Ite(gBuildT, Const(RNODE), Const(KNODE)), Ite(gBuildT, rf(0), kf1), Ite(gBuildT, rf(1), kf2), Ite(gBuildT, rf(2), kf3), Ite(gBuildT, rf(3), kf4))
        (rk, rk)
      } else {
        val kn = p.site(Const(KNODE), kf1, kf2, kf3, kf4)
        (kn, if (matchOnly) { Null } else { val rf = rearFields(entry, rearNext); p.site(Const(RNODE), rf(0), rf(1), rf(2), rf(3)) })
      }
      val iq: E = if (noAlloc) { Null } else p.site(Const(IQC), if (matchOnly) { Fld(pendB, 1) } else { Ite(stBuild, src, Fld(pendB, 1)) }, pendF, Null, Null)
      def leave(u: Prog#Upd): Unit =
        if (compact) { u.bit(n("q1"), False); u.bit(n("q0"), False) }   // back to the transition state of the same side
        else if (matchOnly) { for (i <- 0 until 3) { u.bit(n(s"p$i"), if (((MATCH >> i) & 1) == 1) { True } else { False }) } }
        else { M.setPhaseIf(u, stBPop, BUILD, MATCH) }
      /** stay in the transition state if `g`, else start a pop (same side) */
      def popUnless(u: Prog#Upd, g: C, stay: Int, pop: Int): Unit =
        if (compact) { u.bit(n("q1"), False); u.bit(n("q0"), not(g)) } else { M.setPhaseIf(u, g, stay, pop) }
      /** the pattern source advances; the first k letters (the replay countdown rc) are prepended to the FIFO */
      def consumeSrc(u: Prog#Upd): Unit = { u.set(feed.src, prevIn(src)); u.set(feed.pendF, Ite(IsNull(rc), pendF, iq)); u.set(feed.rc, prevIn(rc)) }
      // ---- pop in progress
      p.when(And(gRev, rvLast)) { u => u.set(n("cur"), rearE(rv)); u.set(n(rvName), Null); leave(u) }   // the last reversed entry is the state
      popAllocOn(gRev2) { u => u.set(n("L"), lnode); u.set(n("cur"), rearE(Fld(rv, 2))); u.set(n(rvName), Null); leave(u) }
      popAlloc(gRev) { u => u.set(n("L"), lnode); u.set(n(rvName), Fld(rv, 2)) }
      if (noAlloc && !freePops) { p.when(And(gSpl1, IsNull(Fld(ks, 2)))) { u => u.set(n("K"), K); setSub(u, SSPL2) } }   // no K node needed
      popAlloc(gSpl1) { u => u.set(n("K"), Ite(IsNull(Fld(ks, 2)), K, knode)); setSub(u, SSPL2) }          // the spliced stack's own rest first
      whenOn(gSpl2Pop) { u => u.set(n("cur"), Fld(Fld(ks, 1), 1)); u.set(n("L"), Fld(Fld(ks, 1), 2)); u.set(n(ksName), Null); leave(u) }
      popAlloc(gSpl2) { u => u.set(n("K"), knode); u.set(n(ksName), Null); setSub(u, S0) }                    // then its three slots
      p.when(gPopL) { u => u.set(n("cur"), Fld(L, 1)); u.set(n("L"), Fld(L, 2)); leave(u) }
      popAllocOn(gPopGL) { u => u.set(n("cur"), Fld(Fld(gE, 2), 1)); u.set(n("L"), Fld(Fld(gE, 2), 2)); u.set(n("K"), knode); leave(u) }
      popAlloc(gPopG) { u => u.set(n("L"), Fld(gE, 2)); u.set(n("K"), knode) }                          // gap(e) ++ [e] ++ rest in one step
      popAllocOn(gTakeSplSkip) { u => u.set(n(ksName), k4); u.set(n("K"), knode); setSub(u, SSPL2) }
      popAlloc(gTakeSpl) { u => u.set(n(ksName), k4); u.set(n("K"), knode); setSub(u, SSPL1) }
      if (noAlloc && !freePops) { p.when(And(gTakeRev1, IsNull(k1))) { u => u.set(n("cur"), rearE(k3)); u.set(n("K"), k2); leave(u) } }   // no K node needed
      popAlloc(gTakeRev1) { u => u.set(n("cur"), rearE(k3)); u.set(n("K"), Ite(IsNull(k1), k2, knode)); leave(u) }
      popAllocOn(gTakeRev2) { u => u.set(n("L"), lnode); u.set(n("cur"), rearE(Fld(k3, 2))); u.set(n(rvName), Null); u.set(n("K"), Ite(IsNull(k1), k2, knode)); leave(u) }
      popAlloc(gTakeRev) { u => u.set(n("L"), lnode); u.set(n(rvName), Fld(k3, 2)); u.set(n("K"), Ite(IsNull(k1), k2, knode)) }
      p.when(gTakePop) { u => u.set(n("cur"), Fld(k1, 1)); u.set(n("L"), Fld(k1, 2)); u.set(n("K"), k2); leave(u) }
      p.when(gTakeList) { u => u.set(n("L"), k1); u.set(n("K"), k2) }
      p.when(gTakeEmpty) { u => u.set(n("K"), k2) }
      p.when(gPopR1) { u => u.set(n("cur"), rearE(R)); u.set(n("R"), Null); leave(u) }
      popAlloc(gPopR) { u => u.set(n("L"), lnode); u.set(n(rvName), Fld(R, 2)); u.set(n("R"), Null) }   // the top goes to L, the rest is reversed
      load(p)
      locally {
        val fastM = And(same, headIsL); val fastK = And(same, kTop); val fastR = And(same, rOnly)
        val misZ0 = all(not(same), IsNull(Fld(cur, 2)), IsNull(Fld(cur, 3)), IsNull(Fld(cur, 4)))
        val bt = And(gBuildT, not(IsNull(cur)))
        val fastUx = if (merge2 && unfold && !m2off("U")) { And(not(same), TagIs(Fld(cur, 2), LNODE)) } else { False }
        branches = Vector("rev-last" -> And(gRev, rvLast), "rev2" -> gRev2, "rev" -> gRev, "spl1" -> gSpl1, "spl2pop" -> gSpl2Pop, "spl2" -> gSpl2, "popL" -> gPopL,
          "popGL" -> gPopGL, "popG" -> gPopG, "takeSplSkip" -> gTakeSplSkip, "takeSpl" -> gTakeSpl, "takeRev1" -> gTakeRev1, "takeRev2" -> gTakeRev2, "takeRev" -> gTakeRev,
          "takePop" -> gTakePop, "takeList" -> gTakeList,
          "takeEmpty" -> gTakeEmpty, "popR1" -> gPopR1, "popR" -> gPopR) ++
          (if (matchOnly) { Vector() } else {
            Vector("build-E0" -> And(gBuildT, IsNull(cur)), "build-fast1" -> And(bt, fastM), "build-fast2" -> all(bt, same, liveEmpty),
              "build-fast3" -> And(bt, fastK), "build-fast4" -> And(bt, fastR), "build-misZ" -> And(bt, misZ0), "build-misL" -> And(bt, fastUx), "build-takeRev1" -> gFastT,
              "build-same-pop" -> And(bt, same),
              "build-mis" -> bt, "end" -> gEnd) }) ++
          Vector("match-full" -> all(gMatchT, same, liveEmpty), "match-fastM" -> And(gMatchT, fastM), "match-fastK" -> And(gMatchT, fastK),
            "match-fastR" -> And(gMatchT, fastR), "match-misZ" -> And(gMatchT, misZ0), "match-misL" -> And(gMatchT, fastUx),
            "match-same-pop" -> And(gMatchT, same), "match-mis" -> gMatchT,
            "fifo" -> gFifoRev)
      }
      // ---- BUILD: the pattern letter src against cur = E_f; E_i = (src, h_i) with h_i = same ? C : g,
      //      C = gap(cur) ++ [cur] ++ g (the GNODE); D = (same ? g : C) ++ [E_i]; (cur, live) = pop(D)
      if (!matchOnly) {   // the BUILD branches stay in their original place: the exclusive guards of later branches depend on the order
      alloc(And(gBuildT, IsNull(cur))) { u => u.set(n("cur"), entry); consumeSrc(u) }   // E_0: empty gap
      alloc(gBuildT) { u =>
        val fast1 = And(same, headIsL)              // D = [head L] ++ …: pop directly
        val fast2 = And(same, liveEmpty)            // D = [E_i]: the new entry is the state
        val fast3 = And(same, kTop)                 // D = [head of the list on top of K] ++ …
        val fast4 = And(same, rOnly)                // D = [R.p1, E_i]: pop R.p1, the rear becomes [E_i]
        val misZ = all(not(same), IsNull(Fld(cur, 2)), IsNull(Fld(cur, 3)), IsNull(Fld(cur, 4)))   // gap(cur) empty (E_0): D = [cur] ++ g ++ [E_i]
        // merge2: the unfolded gap starts with an LNODE, whose entry is the new state at once
        val fastU = if (merge2 && unfold && !m2off("U")) { And(not(same), TagIs(Fld(cur, 2), LNODE)) } else { False }
        val fastT = gFastT
        u.set(n("cur"), selectE(on(Vector((fast1, Fld(L, 1)), (fast2, entry), (fast3, Fld(k1, 1)), (fast4, rearE(R)), (fastU, Fld(Fld(cur, 2), 1)), (fastT, rearE(k3)))), cur))
        u.set(n("L"), selectE(on(Vector((fast1, Fld(L, 2)), (fast3, Fld(k1, 2)), (Or(same, misZ), L), (fastU, Fld(Fld(cur, 2), 2)))), if (unfold) { Fld(cur, 2) } else { lnode }))
        u.set(n("K"), if (unfold) { selectE(on(Vector((fast3, k2), (fastT, Ite(IsNull(k1), k2, knode)), (Or(same, misZ), K))), knode) } else { Ite(fast3, k2, K) })
        u.set(n("R"), Ite(fast2, R, rnode))
        consumeSrc(u)
        popUnless(u, anyOn(fast1, fast2, fast3, fast4, misZ, fastU, fastT), BUILD, BPOP)
      }
      if (compact) { if (endStep) { alloc(gEnd) { u => u.set(n("end"), entry); u.bit(n("q2"), True); u.bit(n("q1"), False); u.bit(n("q0"), False) } } }
      else {
        p.when(gEnd) { u => u.set(n("end"), entry); u.phase(REV) }
        p.when(gRevPhase) { u => u.phase(MATCH) }
      }
      }
      // ---- MATCH: the FIFO head against cur; a match pops the live sequence (empty = full match: report, then the
      //      reset state from END is popped into cur); a mismatch pushes GNODE(cur, L) and pops, except at an entry
      //      with an empty gap (E_0)
      if (noAlloc) {
        val misZm = all(not(same), IsNull(Fld(cur, 2)), IsNull(Fld(cur, 3)), IsNull(Fld(cur, 4)))
        p.when(all(gMatchT, not(same), not(misZm))) { _ => () }   // stall: the mismatch stores a new cell
        p.when(gMatchT) { u =>
          val full = And(same, liveEmpty)
          val fastM = And(same, headIsL)
          val fastK = And(same, kTop)
          val fastR = And(same, rOnly)
          u.set(n("cur"), selectE(Vector((fastM, Fld(L, 1)), (fastK, Fld(k1, 1)), (fastR, rearE(R))), cur))
          u.set(n("L"), selectE(Vector((fastM, Fld(L, 2)), (fastK, Fld(k1, 2)), (full, Fld(end, 2))), L))
          u.set(n("K"), selectE(Vector((fastK, k2), (full, Fld(end, 3))), K))
          u.set(n("R"), selectE(Vector((fastR, Null), (full, Fld(end, 4))), R))
          if (report) { u.bit(n("acc"), Or(B("acc"), And(full, atFrontier))) }
          u.set(feed.pendF, Fld(pendF, 2))
          popUnless(u, anyOn(fastM, fastK, fastR, misZm), MATCH, MPOP)
        }
      } else p.when(gMatchT) { u =>
        val full = And(same, liveEmpty)
        val fastM = And(same, headIsL)
        val fastK = And(same, kTop)
        val fastR = And(same, rOnly)
        val misZ = all(not(same), IsNull(Fld(cur, 2)), IsNull(Fld(cur, 3)), IsNull(Fld(cur, 4)))
        val fastU = if (merge2 && unfold && !m2off("U")) { And(not(same), TagIs(Fld(cur, 2), LNODE)) } else { False }
        u.set(n("cur"), selectE(on(Vector((fastM, Fld(L, 1)), (fastK, Fld(k1, 1)), (fastR, rearE(R)), (fastU, Fld(Fld(cur, 2), 1)))), cur))
        u.set(n("L"), selectE(on(Vector((fastM, Fld(L, 2)), (fastK, Fld(k1, 2)), (full, Fld(end, 2)), (fastU, Fld(Fld(cur, 2), 2)),
          (And(not(same), not(misZ)), if (unfold) { Fld(cur, 2) } else { lnode }))), L))
        u.set(n("K"), selectE(Vector((fastK, k2), (full, Fld(end, 3))) ++ (if (unfold) { Vector((And(not(same), not(misZ)), knode)) } else { Vector() }), K))
        u.set(n("R"), selectE(Vector((fastR, Null), (full, Fld(end, 4))), R))
        if (report) { u.bit(n("acc"), Or(B("acc"), And(full, atFrontier))) }
        u.set(feed.pendF, Fld(pendF, 2))
        popUnless(u, anyOn(fastM, fastK, fastR, misZ, fastU), MATCH, MPOP)
      }
      // ---- the FIFO: the back stack is reversed onto the front one cell per step (letters that arrived while the
      //      front was busy); the same as LpsPal2.Matcher.load
      alloc(gFifoRev) { u =>
        val last = IsNull(Fld(pendB, 2))
        u.set(feed.pendF, iq)
        u.set(feed.pendB, Ite(last, pendN, Fld(pendB, 2)))
        u.set(feed.pendN, Ite(last, Null, pendN))
        u.bit(feed.rev, not(last))
      }
      p
    }
  }
}
