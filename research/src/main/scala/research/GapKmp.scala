package research

/** The binary KMP matcher with persistent gap sequences (under-1000 plan §4), in the amortised form that the
  * shape probe suggests (`probes/gap_shape_probe.py`): every sequence of the abstract matcher is a contiguous
  * range of entries, so a stored gap `h_i` is kept as the *triple* `(hL, hK, hR)` of the live sequence at the
  * time (no copy), and the live sequence is
  *
  *   [[L]] ++ concat(K) ++ reverse(R)
  *
  * with `L` a list of entries whose nodes are `LNODE(e, rest)` = `[e] ++ rest` or `GNODE(e, rest)` = `gap(e) ++
  * [e] ++ rest` (`gap(e) = [[e.hL]] ++ concat(e.hK) ++ reverse(e.hR)`), `K` a stack `KNODE(pending, rest)` of
  * pending sequences (a list, a `K` stack to splice, or an `RNODE` stack to reverse) and `R` a stack `RNODE(e,
  * rest)` of the entries appended last (newest on top). Every operation allocates at most one cell per tag per
  * step; a pop that meets a nested head spends further steps (phases BPOP/MPOP) — worst case Θ(pattern), amortised
  * O(1) per letter by the KMP potential (a pop out of `h_cur` costs at most |h_cur| ≤ the matches since the last
  * jump). There are no failure-chain walks, unary counters or archive reversals; the handover from BUILD to MATCH
  * is one step (the live sequence after the build *is* the initial front `[E_1 .. E_{m-1}]`).
  *
  * States are the entries `E_0 .. E_{m-1}`; "state m" (a full match) is `cur = E_{m-1}` matched with an empty
  * live sequence. The END cell holds the reset state `(f_m, g_m)`. A mismatch at `cur` pushes `GNODE(cur, L)`
  * (`h[cur] ++ [cur] ++ front`) and pops; a mismatch at an entry with an empty gap (only `E_0`) stays.
  */
object GapKmp {
  import Lpm.{E, C, TE, Prog}
  import Lpm.E.*
  import Lpm.C.*
  import Lpm.TE.Const
  import Lps.*
  import LpsPal.Mod

  val BUILD = 0; val BPOP = 1; val REV = 2; val MATCH = 3; val MPOP = 4
  /** sub-states of a pop in progress: none; push `ex.hR`; push `ex.hK` and take `ex.hL`; push the spliced `ks` */
  val S0 = 0; val SHR = 1; val SHK = 2; val SSPL = 3

  final class Tags(base: Int) {
    val IQC = base        // queue cell: p1 = input cell, p2 = below
    val ENTRY = base + 1  // p1 = letter cell, p2 = hL, p3 = hK, p4 = hR
    val END = base + 2    // p1 = reset entry f_m, p2 = L, p3 = K, p4 = R of the reset sequence g_m
    val LNODE = base + 3  // p1 = entry, p2 = rest
    val GNODE = base + 4  // p1 = entry (its gap comes first), p2 = rest
    val KNODE = base + 5  // p1 = pending (LNODE/GNODE list, KNODE stack, RNODE stack), p2 = rest
    val RNODE = base + 6  // p1 = entry, p2 = rest
    val listKinds: Set[K] = Set(KTag(LNODE), KTag(GNODE))
    val pendKinds: Set[K] = listKinds ++ Set(KTag(KNODE), KTag(RNODE))
    val schema: Map[Int, Vector[Set[K]]] = Map(
      IQC -> Vector(Set(KInp), Set(KTag(IQC)), Set(), Set()),
      ENTRY -> Vector(Set(KInp), listKinds, Set(KTag(KNODE)), Set(KTag(RNODE))),
      END -> Vector(Set(KTag(ENTRY)), listKinds, Set(KTag(KNODE)), Set(KTag(RNODE))),
      LNODE -> Vector(Set(KTag(ENTRY)), listKinds, Set(), Set()),
      GNODE -> Vector(Set(KTag(ENTRY)), listKinds, Set(), Set()),
      KNODE -> Vector(pendKinds, Set(KTag(KNODE)), Set(), Set()),
      RNODE -> Vector(Set(KTag(ENTRY)), Set(KTag(RNODE)), Set(), Set()))
  }
  val bankTags: Vector[Tags] = Vector(new Tags(30), new Tags(40))

  private def not(c: C): C = Not(c)
  private def selectE(cases: Seq[(C, E)], default: E): E = cases.foldRight(default) { case ((g, v), acc) => Ite(g, v, acc) }

  /** the matcher core over module `M` (3 phase bits, 2 sub bits); `atFrontier` qualifies a full-match report */
  final class Core(M: Mod, T: Tags, atFrontier: C) extends OverlapPal.BankCore {
    import M.*
    import T.*
    val regNames: Vector[String] = Vector("c", "cur", "L", "K", "R", "rv", "ex", "ks", "end")
    val regs: Vector[(String, Set[K])] = Vector(n("c") -> Set(KInp), n("cur") -> Set(KTag(ENTRY)), n("L") -> listKinds,
      n("K") -> Set(KTag(KNODE)), n("R") -> Set(KTag(RNODE)), n("rv") -> Set(KTag(RNODE)), n("ex") -> Set(KTag(ENTRY)),
      n("ks") -> Set(KTag(KNODE)), n("end") -> Set(KTag(END)))
    val bits: Vector[String] = Vector(n("have"), n("acc"), n("eop"))
    def reset(u: Prog#Upd): Unit = { regNames.foreach(x => u.set(n(x), Null)); bits.foreach(b => u.bit(b, False)); u.phase(BUILD) }

    def universal(load: Prog => Unit): Prog = {
      val c = r("c"); val cur = r("cur"); val L = r("L"); val K = r("K"); val R = r("R"); val rv = r("rv"); val ex = r("ex"); val ks = r("ks"); val end = r("end")
      val popping = Or(at(BPOP), at(MPOP))
      val same = SameTag(c, Fld(cur, 1))
      val headIsL = TagIs(L, LNODE)
      val headIsG = TagIs(L, GNODE)
      val kHead = Fld(K, 1)
      val k1 = Fld(kHead, 1)
      val k2 = Fld(kHead, 2)
      val isList = (e: E) => Or(TagIs(e, LNODE), TagIs(e, GNODE))
      // pop-progress guards (priority order)
      val gRev = And(popping, not(IsNull(rv)))
      val gHR = all(popping, IsNull(rv), subIs(SHR))
      val gHK = all(popping, IsNull(rv), subIs(SHK))
      val gSpl = all(popping, IsNull(rv), subIs(SSPL))
      val gPop0 = all(popping, IsNull(rv), subIs(S0))
      val gPopL = And(gPop0, headIsL)
      val gPopG = And(gPop0, headIsG)
      val gPopK = all(gPop0, IsNull(L), not(IsNull(K)))
      val gPopKList = And(gPopK, isList(kHead))
      val gPopKRev = And(gPopK, TagIs(kHead, RNODE))
      val gPopKSpl = And(gPopK, TagIs(kHead, KNODE))
      val gPopR = all(gPop0, IsNull(L), IsNull(K), not(IsNull(R)))
      // transitions
      val haveB = And(at(BUILD), B("have"))
      val gBuildT = And(haveB, not(B("eop")))
      val gEnd = And(haveB, B("eop"))
      val gRevPhase = at(REV)
      val gMatchT = And(at(MATCH), B("have"))
      val gTrans = Or(gBuildT, gMatchT)
      val p = prog()
      // shared allocation sites (one per tag): a list node (GNODE for the mismatch sequence of a transition),
      // the entry (END at the end of the pattern), a K node, an R node
      val lnode: E = p.site(TE.Ite(gTrans, Const(GNODE), Const(LNODE)),
        Ite(gRev, Fld(rv, 1), Ite(gPopG, Fld(L, 1), cur)), Ite(gPopG, Fld(L, 2), L), Null, Null)
      val entry: E = p.site(TE.Ite(gEnd, Const(END), Const(ENTRY)),
        Ite(gEnd, cur, c), Ite(gEnd, L, Ite(same, lnode, L)), K, R)
      val knode: E = p.site(Const(KNODE),
        selectE(Vector((gHR, Fld(ex, 4)), (gHK, Fld(ex, 3)), (gPopG, lnode), (gSpl, ks)), k2), Ite(gPopKSpl, Fld(K, 2), K), Null, Null)
      val rnode: E = p.site(Const(RNODE), entry, R, Null, Null)
      def leave(u: Prog#Upd): Unit = M.setPhaseIf(u, at(BPOP), BUILD, MATCH)
      // ---- pop in progress
      p.when(gRev) { u => u.set(n("L"), lnode); u.set(n("rv"), Fld(rv, 2)) }
      p.when(gHR) { u => u.set(n("K"), Ite(IsNull(Fld(ex, 4)), K, knode)); u.sub(SHK) }
      p.when(gHK) { u => u.set(n("K"), Ite(IsNull(Fld(ex, 3)), K, knode)); u.set(n("L"), Fld(ex, 2)); u.set(n("ex"), Null); u.sub(S0) }
      p.when(gSpl) { u => u.set(n("K"), knode); u.set(n("ks"), Null); u.sub(S0) }
      p.when(gPopL) { u => u.set(n("cur"), Fld(L, 1)); u.set(n("L"), Fld(L, 2)); leave(u) }
      p.when(gPopG) { u => u.set(n("ex"), Fld(L, 1)); u.set(n("K"), knode); u.set(n("L"), Null); u.sub(SHR) }
      p.when(gPopKList) { u => u.set(n("L"), kHead); u.set(n("K"), Fld(K, 2)) }
      p.when(gPopKRev) { u => u.set(n("rv"), kHead); u.set(n("K"), Fld(K, 2)) }
      p.when(gPopKSpl) { u =>
        val newK = Ite(IsNull(k2), Fld(K, 2), knode)
        u.set(n("L"), Ite(isList(k1), k1, L))
        u.set(n("rv"), Ite(TagIs(k1, RNODE), k1, rv))
        u.set(n("ks"), Ite(TagIs(k1, KNODE), k1, ks))
        u.set(n("K"), newK)
        M.setSubIf(u, TagIs(k1, KNODE), SSPL, S0)
      }
      p.when(gPopR) { u => u.set(n("rv"), R); u.set(n("R"), Null) }
      // ---- the module's letter loads (BUILD: pattern letters from the source; MATCH: text letters from the FIFO)
      load(p)
      // ---- BUILD: a pattern letter c against cur = E_f; E_i = (c, h_i) with h_i = same ? C : g,
      //      C = gap(cur) ++ [cur] ++ g (the GNODE); D = (same ? g : C) ++ [E_i]; (cur, live) = pop(D)
      p.when(And(gBuildT, IsNull(cur))) { u => u.set(n("cur"), entry); u.bit(n("have"), False) }   // E_0: empty gap
      p.when(gBuildT) { u =>
        val fast = And(same, headIsL)
        u.set(n("cur"), Ite(fast, Fld(L, 1), cur))
        u.set(n("L"), Ite(fast, Fld(L, 2), Ite(same, L, lnode)))
        u.set(n("R"), rnode)
        u.bit(n("have"), False)
        M.setPhaseIf(u, fast, BUILD, BPOP)
      }
      p.when(gEnd) { u => u.set(n("end"), entry); u.bit(n("have"), False); u.phase(REV) }
      p.when(gRevPhase) { u => u.phase(MATCH) }
      // ---- MATCH: a text letter against cur; a match pops the live sequence (empty = full match: report and reset
      //      from END); a mismatch pushes GNODE(cur, L) and pops, except at an entry with an empty gap (E_0)
      p.when(gMatchT) { u =>
        val full = all(same, IsNull(L), IsNull(K), IsNull(R))
        val fastM = And(same, headIsL)
        val misZ = all(not(same), IsNull(Fld(cur, 2)), IsNull(Fld(cur, 3)), IsNull(Fld(cur, 4)))
        u.set(n("cur"), selectE(Vector((fastM, Fld(L, 1)), (full, Fld(end, 1))), cur))
        u.set(n("L"), selectE(Vector((fastM, Fld(L, 2)), (full, Fld(end, 2)), (And(not(same), not(misZ)), lnode)), L))
        u.set(n("K"), Ite(full, Fld(end, 3), K))
        u.set(n("R"), Ite(full, Fld(end, 4), R))
        u.bit(n("acc"), Or(B("acc"), And(full, atFrontier)))
        u.bit(n("have"), False)
        M.setPhaseIf(u, any(fastM, full, misZ), MATCH, MPOP)
      }
      p
    }
  }
}
