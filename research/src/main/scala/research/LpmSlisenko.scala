package research


/** Slisenko's real-time recognizer of initial palindromes as a layered pointer machine.
  *
  * The word is read reversed (as the PEG sees it); ω is the word spread with separators, so the
  * input cells of a tick are the letter (odd point) and its separator (even point). The machine
  * follows `SlisenkoRT` (region search, Fischer–Paterson border pass, chained-lattice extension)
  * with these representations:
  *
  * - The right side of every scan is the *stream*: the input cells not yet consumed, kept in a
  *   two-stack queue (`pendF`/`pendB`); every consumed cell is pushed on the trail `RT`, and a
  *   `MARK` is pushed on `RT` when a region search starts. Unary counters are chains ending at a
  *   mark or at null (`atEnd`); "R" is the number of trail cells above the mark.
  * - Walking toward the start of the word is free (`p1` of an input cell is the previous point);
  *   walking toward the frontier is never needed: X is read from its right end (X is a palindrome),
  *   the left lattice extension reads its right side through the mirror around the next node, and
  *   the right extension re-positions its left probe by popping `RT`.
  * - The border pass keeps the α cells (position, failure link, per-tree, previous) of the text
  *   positions between the pattern head and the text head in a two-stack queue; a failure jump of
  *   length per(p) walks the previous-links while pushing them back on the queue's front.
  * - Numbers that grow (dist, per) are trees of `UNIT` leaves; they are only ever walked.
  */
object LpmSlisenko {
  import Lpm.*
  import Lpm.E.*
  import Lpm.C.*

  // input tags: a = 0, b = 1, separator = 2
  val SEP = 2
  val MARK = 3
  val RTC = 4      // consumed right cell: p1 = the input cell, p2 = below
  val QC = 5       // queue cell: p1 = input cell (input queue) or p3 = α (α queue); p2 = below
  val UNARY = 6    // p2 = below
  val ALPHA = 7    // p1 = cell of X[j+1], p2 = fail, p3 = per tree, p4 = previous α
  val ALPHAM = 8   // α within the bound (its border may be chosen)
  val ALPHA0 = 9   // sentinel α_0
  val TREE = 10    // p2 = left, p3 = right
  val UNIT = 11
  val DFS = 12     // p2 = below, p3 = tree node
  val ALPHAK = 13  // α exactly at the bound: its centre is K's last node (a point, never a lattice)

  // phases
  val INIT = 0; val SCAN = 1; val SCAN_NEW = 2; val FINDY0 = 3; val FINDY_MK = 4; val FINDY1 = 5; val FINDY2 = 6
  val FINDY3 = 7; val KMP = 8; val KMP_B = 9; val KMP_C = 10; val KMP_T = 11; val KMP_REW0 = 12; val KMP_REW1 = 13
  val KMP_REW = 14; val KMP_REW_R = 15; val CHAIN = 16; val TOY_WALK = 17; val TOY_H = 18; val TOY_CMP = 19
  val PROBE_SETUP = 20; val PROBE = 21; val PROBE_FAIL = 22; val LAT_U2H = 23; val LATL_CMP = 24; val LATL_MIR = 25
  val LATL = 26; val LATL_SW = 27; val LATR_ENTRY = 28; val LATR_BCNT = 29; val LATR = 30; val LATR_SW = 31
  val LATDONE = 32; val LATDONE_SYM = 33; val LATDONE_NSYM = 34; val POINT_Y = 35

  val phaseBits = 6
  val subBits = 3

  val inpRegs: Vector[String] = Vector("hx", "hl", "sl", "rCur", "lastR", "kEnd", "tT", "hy", "mir", "g1")
  val machRegs: Vector[String] = Vector("pendF", "pendB", "pendN", "RT", "alpha", "alphaT", "aF", "aB", "dist", "dfs", "uR",
    "cnt", "cnt2", "hPtr", "u2h", "mk", "upL", "upR", "spanH", "tmp", "one", "bcnt", "cntL", "cntR")
  val bitNames: Vector[String] = (0 until phaseBits).map(i => s"p$i").toVector ++ (0 until subBits).map(i => s"s$i").toVector ++
    Vector("wait", "acc", "needSep", "useCur", "lm1", "par", "parL", "bnd", "tog", "endMis", "imm", "switched", "eq", "kSame", "rev", "arev")

  private def phaseIs(k: Int): C = all((0 until phaseBits).map { i => if (((k >> i) & 1) == 1) { Bit(s"p$i") } else { Not(Bit(s"p$i")) } }*)
  private def subIs(k: Int): C = all((0 until subBits).map { i => if (((k >> i) & 1) == 1) { Bit(s"s$i") } else { Not(Bit(s"s$i")) } }*)
  private def r(n: String): E = Reg(n)
  private def prev(e: E): E = Fld(e, 1)
  private def below(e: E): E = Fld(e, 2)
  private def atEnd(e: E): C = Or(IsNull(e), TagIs(e, MARK))
  private def isMark(e: E): C = TagIs(e, MARK)
  private def B(n: String): C = Bit(n)
  private def not(c: C): C = Not(c)

  def machine: Machine = {
    val p = new Prog(phaseBits, subBits)
    val active = all(not(First), not(B("needSep")), not(B("wait")))
    def at(k: Int, s: Int = -1): C = if (s < 0) { And(active, phaseIs(k)) } else { all(active, phaseIs(k), subIs(s)) }

    // the stream is exactly [this tick's separator]: the letter just consumed is the current one
    val onTime = all(IsNull(r("pendB")), not(IsNull(r("pendF"))), IsNull(below(r("pendF"))), TagIs(prev(r("pendF")), SEP))
    // the same, seen from the unit that is consuming the letter (the old stream is [letter, separator])
    val onTimeNow = all(IsNull(r("pendB")), not(IsNull(r("pendF"))), not(IsNull(below(r("pendF")))),
      IsNull(below(below(r("pendF")))), TagIs(prev(below(r("pendF"))), SEP))

    // ---- tick prologue: queue the new cells; clear the waiting flag -----------------------------
    // while a reversal is in progress the new cells go to pendN (they are younger than everything queued)
    p.when(And(First, not(B("rev")))) { u =>
      u.set("pendB", u.alloc(QC, In, r("pendB"), Null, Null))
      u.bit("needSep", True); u.bit("wait", False); u.bit("acc", False)
    }
    p.when(And(First, B("rev"))) { u =>
      u.set("pendN", u.alloc(QC, In, r("pendN"), Null, Null))
      u.bit("needSep", True); u.bit("wait", False); u.bit("acc", False)
    }
    p.when(all(not(First), B("needSep"), not(B("rev")))) { u =>
      u.set("pendB", u.alloc(QC, Sep, r("pendB"), Null, Null))
      u.bit("needSep", False)
    }
    p.when(all(not(First), B("needSep"), B("rev"))) { u =>
      u.set("pendN", u.alloc(QC, Sep, r("pendN"), Null, Null))
      u.bit("needSep", False)
    }

    /** Consume the next right cell under guard `g`: `body(u, cell)` runs in the unit that consumes
      * it (it must not allocate). */
    def consume(g: C)(body: (Prog#Upd, E, C) => Unit): Unit = {
      // re-reading the last consumed cell: it is already on the trail as a point, so only a unit is pushed (counts as a match)
      p.when(all(g, not(B("rev")), B("useCur"))) { u =>
        u.bit("useCur", False); u.set("RT", u.alloc(UNARY, Null, r("RT"), Null, Null)); body(u, r("rCur"), onTime)
      }
      p.when(all(g, not(B("rev")), not(B("useCur")), not(IsNull(r("pendF"))))) { u =>
        val c = prev(r("pendF"))
        u.set("rCur", c); u.set("pendF", below(r("pendF")))
        u.set("RT", u.alloc(RTC, c, r("RT"), Null, Null))
        body(u, c, onTimeNow)
      }
      // reversal of pendB into pendF, one cell per unit; new arrivals wait in pendN
      p.when(all(g, Or(B("rev"), all(not(B("useCur")), IsNull(r("pendF")), not(IsNull(r("pendB"))))))) { u =>
        val last = IsNull(below(r("pendB")))
        u.set("pendF", u.alloc(QC, prev(r("pendB")), r("pendF"), Null, Null))
        u.set("pendB", Ite(last, r("pendN"), below(r("pendB"))))
        u.set("pendN", Ite(last, Null, r("pendN")))
        u.bit("rev", not(last))
      }
      p.when(all(g, not(B("rev")), not(B("useCur")), IsNull(r("pendF")), IsNull(r("pendB")))) { u => u.bit("wait", True) }
    }

    // ---- INIT: the unit cell, then the first point becomes x ----------------------------------
    p.when(at(INIT, 0)) { u => u.set("one", u.alloc(UNIT, Null, Null, Null, Null)); u.sub(1) }
    p.when(at(INIT, 1)) { u => u.bit("endMis", False); u.phase(SCAN_NEW) }

    // ---- SCAN_NEW: x := the next point (or the mismatch cell), then a mark ---------------------
    p.when(at(SCAN_NEW, 0)) { u => u.sub(1) }
    consume(all(at(SCAN_NEW, 1), not(B("endMis")))) { (u, c, _) => u.set("hx", c); u.sub(2) }
    p.when(all(at(SCAN_NEW, 1), B("endMis"))) { u => u.set("hx", r("rCur")); u.sub(2) }
    p.when(at(SCAN_NEW, 2)) { u =>
      u.set("RT", u.alloc(MARK, Null, r("RT"), Null, Null))
      u.set("lastR", r("hx")); u.set("hl", prev(r("hx"))); u.bit("endMis", False); u.set("kEnd", Null); u.bit("bnd", False)
      u.phase(SCAN)
    }

    // ---- SCAN: region search around x ----------------------------------------------------------
    p.when(And(at(SCAN), IsNull(r("hl")))) { u =>
      u.bit("acc", Or(B("acc"), onTime))
      u.set("uR", r("RT")); u.bit("endMis", False); u.phase(FINDY0)
    }
    consume(And(at(SCAN), not(IsNull(r("hl"))))) { (u, c, _) =>
      val same = SameTag(r("hl"), c)
      u.set("hl", Ite(same, prev(r("hl")), r("hl")))
      u.set("lastR", Ite(same, c, r("lastR")))
      u.bit("endMis", not(same))
      u.set("uR", Ite(same, r("uR"), r("RT")))          // on a mismatch: the trail top *before* this unit's push, i.e. without the mismatch cell
      for (i <- 0 until phaseBits) { u.bit(s"p$i", Ite2(same, bitOf(SCAN, i), bitOf(FINDY0, i))) }
      u.sub(0)
    }

    // ---- FINDY0: R = 0 → next point; else bound marks, then the border pass -------------------
    // sub 0: fix uR (skip the mismatch cell), save the left probe
    p.when(at(FINDY0, 0)) { u => u.set("sl", r("hl")); u.sub(1) }
    p.when(And(at(FINDY0, 1), atEnd(r("uR")))) { u => u.phase(SCAN_NEW) }                 // R = 0: y = Λ
    p.when(all(at(FINDY0, 1), not(atEnd(r("uR"))), not(B("bnd")))) { u => u.set("mk", Null); u.phase(FINDY1) }
    p.when(all(at(FINDY0, 1), not(atEnd(r("uR"))), B("bnd"))) { u => u.set("cnt", r("uR")); u.set("tmp", r("cnt2")); u.set("mk", Null); u.phase(FINDY_MK) }
    // FINDY_MK sub 0: cnt := uR walked down kd (tmp = kd chain); sub 1: mk := 2·depth(cnt) + 1 units
    p.when(all(at(FINDY_MK, 0), not(atEnd(r("tmp"))))) { u => u.set("cnt", below(r("cnt"))); u.set("tmp", below(r("tmp"))) }
    p.when(all(at(FINDY_MK, 0), atEnd(r("tmp")))) { u => u.bit("tog", False); u.sub(1) }
    p.when(all(at(FINDY_MK, 1), not(atEnd(r("cnt"))))) { u =>
      u.set("mk", u.alloc(UNARY, Null, r("mk"), Null, Null))
      u.bit("tog", not(B("tog")))
      u.set("cnt", Ite(B("tog"), below(r("cnt")), r("cnt")))
    }
    p.when(all(at(FINDY_MK, 1), atEnd(r("cnt")))) { u => u.set("mk", u.alloc(UNARY, Null, r("mk"), Null, Null)); u.phase(FINDY1) }

    val markTag: TE = TE.Ite(not(B("bnd")), TE.Const(ALPHAM),
      TE.Ite(atEnd(r("mk")), TE.Const(ALPHA), TE.Ite(atEnd(below(r("mk"))), TE.Const(ALPHAK), TE.Const(ALPHAM))))
    def mkStep(u: Prog#Upd): Unit = u.set("mk", Ite(And(B("bnd"), not(atEnd(r("mk")))), below(r("mk")), r("mk")))

    p.when(at(FINDY1)) { u => u.set("alpha", u.alloc(ALPHA0, r("lastR"), Null, Null, Null)); u.set("aF", Null); u.set("aB", Null); u.phase(FINDY2) }
    p.when(at(FINDY2)) { u =>
      u.set("alphaT", u.alloc(markTag, prev(r("lastR")), r("alpha"), r("one"), r("alpha")))   // α_1: fail = α_0, per = 1
      mkStep(u); u.phase(FINDY3)
    }
    p.when(at(FINDY3)) { u =>
      u.set("aB", u.alloc(QC, Null, r("aB"), r("alphaT"), Null))
      u.set("tT", prev(r("lastR"))); u.set("dist", r("one")); u.set("cnt", r("uR")); u.bit("tog", False)
      u.phase(KMP)
    }

    // ---- KMP: stage head ------------------------------------------------------------------------
    val same = SameTag(r("tT"), prev(r("alpha")))
    p.when(all(at(KMP), not(B("tog")), atEnd(r("cnt")))) { u => u.phase(CHAIN) }
    val kmpLive = all(at(KMP), Or(B("tog"), not(atEnd(r("cnt")))))
    p.when(all(kmpLive, same, not(B("arev")), not(IsNull(r("aF"))))) { u => u.set("alpha", Fld(r("aF"), 3)); u.set("aF", below(r("aF"))); u.phase(KMP_B) }
    p.when(all(kmpLive, same, Or(B("arev"), And(IsNull(r("aF")), not(IsNull(r("aB"))))))) { u =>
      val last = IsNull(below(r("aB")))
      u.set("aF", u.alloc(QC, Null, r("aF"), Fld(r("aB"), 3), Null)); u.set("aB", below(r("aB"))); u.bit("arev", not(last))
    }
    p.when(all(kmpLive, same, not(B("arev")), IsNull(r("aF")), IsNull(r("aB")))) { u => u.phase(CHAIN) }   // unreachable
    p.when(all(kmpLive, not(same), TagIs(r("alpha"), ALPHA0))) { u => u.phase(KMP_T) }
    p.when(all(kmpLive, not(same), not(TagIs(r("alpha"), ALPHA0)))) { u => u.phase(KMP_REW0) }
    p.when(at(KMP_T)) { u => u.set("dist", u.alloc(TREE, Null, r("dist"), r("one"), Null)); u.phase(KMP_B) }
    p.when(at(KMP_B)) { u =>
      u.set("alphaT", u.alloc(markTag, prev(r("tT")), r("alpha"), r("dist"), r("alphaT")))
      mkStep(u); u.phase(KMP_C)
    }
    p.when(at(KMP_C)) { u =>
      u.set("aB", u.alloc(QC, Null, r("aB"), r("alphaT"), Null))
      u.set("tT", prev(r("tT")))
      u.bit("tog", not(B("tog")))
      u.set("cnt", Ite(B("tog"), below(r("cnt")), r("cnt")))
      u.phase(KMP)
    }
    p.when(at(KMP_REW0)) { u => u.set("dfs", u.alloc(DFS, Null, Null, Fld(r("alpha"), 3), Null)); u.phase(KMP_REW1) }
    p.when(at(KMP_REW1)) { u => u.set("dist", u.alloc(TREE, Null, r("dist"), Fld(r("alpha"), 3), Null)); u.phase(KMP_REW) }
    p.when(And(at(KMP_REW), IsNull(r("dfs")))) { u => u.phase(KMP) }
    val node = Fld(r("dfs"), 3)
    p.when(all(at(KMP_REW), not(IsNull(r("dfs"))), TagIs(node, UNIT))) { u =>
      u.set("aF", u.alloc(QC, Null, r("aF"), r("alpha"), Null))
      u.set("alpha", Fld(r("alpha"), 4)); u.set("dfs", below(r("dfs")))
    }
    p.when(all(at(KMP_REW), not(IsNull(r("dfs"))), not(TagIs(node, UNIT)))) { u =>
      u.set("dfs", u.alloc(DFS, Null, below(r("dfs")), Fld(node, 2), Null))
      u.set("tmp", Fld(node, 3)); u.phase(KMP_REW_R)
    }
    p.when(at(KMP_REW_R)) { u => u.set("dfs", u.alloc(DFS, Null, r("dfs"), r("tmp"), Null)); u.phase(KMP_REW) }

    // ---- CHAIN: the longest admissible border ---------------------------------------------------
    p.when(And(at(CHAIN), TagIs(r("alpha"), ALPHA0))) { u => u.phase(SCAN_NEW) }
    p.when(And(at(CHAIN), Or(TagIs(r("alpha"), ALPHAM), TagIs(r("alpha"), ALPHAK)))) { u =>
      u.set("hy", r("lastR")); u.set("tmp", r("alpha")); u.set("cnt2", Null); u.bit("kSame", TagIs(r("alpha"), ALPHAK)); u.phase(TOY_WALK)
    }
    p.when(And(at(CHAIN), TagIs(r("alpha"), ALPHA))) { u => u.set("alpha", Fld(r("alpha"), 2)) }

    // ---- TO_Y: y = xr − (b−1)/2, rY = (b−1)/2 as a unary chain ------------------------------------
    p.when(And(at(TOY_WALK), TagIs(Fld(r("tmp"), 4), ALPHA0))) { u => u.set("cnt", r("uR")); u.set("tmp", r("cnt2")); u.phase(TOY_H) }
    p.when(And(at(TOY_WALK), not(TagIs(Fld(r("tmp"), 4), ALPHA0)))) { u =>
      u.set("tmp", Fld(Fld(r("tmp"), 4), 4)); u.set("hy", prev(r("hy")))
      u.set("cnt2", u.alloc(UNARY, Null, r("cnt2"), Null, Null))
    }
    // TOY_H: hPtr := uR walked down rY (depth h)
    p.when(And(at(TOY_H), not(atEnd(r("tmp"))))) { u => u.set("cnt", below(r("cnt"))); u.set("tmp", below(r("tmp"))) }
    p.when(And(at(TOY_H), atEnd(r("tmp")))) { u => u.set("hPtr", r("cnt")); u.set("tmp", r("cnt2")); u.phase(TOY_CMP) }
    // TOY_CMP: h ≤ rY ?  (cnt walks from hPtr, tmp from rY)
    p.when(all(at(TOY_CMP), atEnd(r("cnt")), not(B("kSame")))) { u => u.bit("imm", True); u.phase(LAT_U2H) }
    p.when(all(at(TOY_CMP), atEnd(r("cnt")), B("kSame"))) { u => u.bit("imm", True); u.set("hl", r("hy")); u.set("tmp", r("cnt2")); u.phase(POINT_Y) }
    p.when(all(at(TOY_CMP), not(atEnd(r("cnt"))), atEnd(r("tmp")))) { u => u.set("hl", r("hy")); u.set("cnt", r("hPtr")); u.set("tmp", r("cnt2")); u.phase(PROBE_SETUP) }
    p.when(all(at(TOY_CMP), not(atEnd(r("cnt"))), not(atEnd(r("tmp"))))) { u => u.set("cnt", below(r("cnt"))); u.set("tmp", below(r("tmp"))) }

    // ---- PROBE: extend S(y) from radius rY to y − x ----------------------------------------------
    p.when(And(at(PROBE_SETUP), not(atEnd(r("tmp"))))) { u => u.set("hl", prev(r("hl"))); u.set("cnt", below(r("cnt"))); u.set("tmp", below(r("tmp"))) }
    p.when(And(at(PROBE_SETUP), atEnd(r("tmp")))) { u =>
      u.set("hl", prev(r("hl")))
      u.set("RT", u.alloc(MARK, Null, r("RT"), Null, Null))
      u.bit("useCur", B("endMis")); u.phase(PROBE)
    }
    consume(at(PROBE)) { (u, c, _) =>
      val sm = SameTag(r("hl"), c)
      u.set("hl", Ite(sm, prev(r("hl")), r("hl")))
      u.set("lastR", Ite(sm, c, r("lastR")))
      u.set("cnt", Ite(sm, below(r("cnt")), r("cnt")))
      val done = And(sm, atEnd(below(r("cnt"))))
      // success → lattice (imm := false); mismatch → PROBE_FAIL; else stay
      for (i <- 0 until phaseBits) {
        u.bit(s"p$i", Ite3(sm, done, Ite2(B("kSame"), bitOf(POINT_Y, i), bitOf(LAT_U2H, i)), bitOf(PROBE, i), bitOf(PROBE_FAIL, i)))
      }
      u.bit("imm", False)
      u.set("tmp", Null)
      u.sub(0)
    }
    // PROBE_FAIL: x := y with R(y) = rY + matched: uR := rY units on top of the trail below the mismatch
    p.when(at(PROBE_FAIL, 0)) { u => u.set("cnt", below(r("RT"))); u.set("tmp", r("cnt2")); u.set("hx", r("hy")); u.set("kEnd", Null); u.bit("bnd", False); u.bit("endMis", True); u.sub(1) }
    p.when(And(at(PROBE_FAIL, 1), not(atEnd(r("tmp"))))) { u => u.set("cnt", u.alloc(UNARY, Null, r("cnt"), Null, Null)); u.set("tmp", below(r("tmp"))) }
    p.when(And(at(PROBE_FAIL, 1), atEnd(r("tmp")))) { u => u.set("uR", r("cnt")); u.phase(FINDY0) }

    // ---- POINT_Y: y = K.end: f(k) = y as a point; continue the scan of S(y) from the confirmed radius
    // sub 0: (imm) hl := y walked left rY + 1 (tmp = rY chain); sub 1: mark; sub 2: push the confirmed radius
    p.when(all(at(POINT_Y, 0), B("imm"), not(atEnd(r("tmp"))))) { u => u.set("hl", prev(r("hl"))); u.set("tmp", below(r("tmp"))) }
    p.when(all(at(POINT_Y, 0), B("imm"), atEnd(r("tmp")))) { u => u.set("hl", prev(r("hl"))); u.set("cnt", r("cnt2")); u.sub(1) }
    p.when(all(at(POINT_Y, 0), not(B("imm")))) { u => u.set("cnt", r("hPtr")); u.sub(1) }
    p.when(at(POINT_Y, 1)) { u => u.set("RT", u.alloc(MARK, Null, r("RT"), Null, Null)); u.sub(2) }
    p.when(all(at(POINT_Y, 2), not(atEnd(r("cnt"))))) { u => u.set("RT", u.alloc(UNARY, Null, r("RT"), Null, Null)); u.set("cnt", below(r("cnt"))) }
    p.when(all(at(POINT_Y, 2), atEnd(r("cnt")))) { u =>
      u.set("hx", r("hy")); u.set("kEnd", Null); u.bit("bnd", False); u.bit("useCur", And(B("imm"), B("endMis"))); u.bit("kSame", False)
      u.phase(SCAN)
    }

    // ---- LATTICE entry: u2h := h units on hPtr; spanH := h units; parities -----------------------
    p.when(at(LAT_U2H, 0)) { u => u.set("cnt", r("hPtr")); u.set("u2h", r("hPtr")); u.set("spanH", Null); u.bit("par", True); u.bit("parL", False); u.bit("switched", False); u.sub(1) }
    p.when(And(at(LAT_U2H, 1), not(atEnd(r("cnt"))))) { u => u.set("u2h", u.alloc(UNARY, Null, r("u2h"), Null, Null)); u.set("cnt", below(r("cnt"))) }
    p.when(And(at(LAT_U2H, 1), atEnd(r("cnt")))) { u => u.set("cnt", r("hPtr")); u.sub(2) }
    p.when(And(at(LAT_U2H, 2), not(atEnd(r("cnt"))))) { u => u.set("spanH", u.alloc(UNARY, Null, r("spanH"), Null, Null)); u.set("cnt", below(r("cnt"))) }
    p.when(And(at(LAT_U2H, 2), atEnd(r("cnt")))) { u => u.set("cnt", r("uR")); u.phase(LATL_CMP) }

    // ---- LATL_CMP: while R − kh ≥ 2h: switch (k++). cnt = the confirmed radius chain ----------
    p.when(at(LATL_CMP, 0)) { u => u.set("tmp", r("u2h")); u.set("g1", Null); u.set("mk", r("cnt")); u.sub(1) }      // mk = walker copy of cnt
    p.when(all(at(LATL_CMP, 1), atEnd(r("tmp")))) { u => u.set("tmp", r("hPtr")); u.sub(2) }                                // ≥ 2h → switch
    p.when(all(at(LATL_CMP, 1), not(atEnd(r("tmp"))), atEnd(r("mk")))) { u =>                                                // < 2h → left side settled
      u.set("upL", r("cnt")); u.set("mk", Null)
      u.bit("eq", B("switched"))
      u.phase(LATL_MIR)
    }
    p.when(all(at(LATL_CMP, 1), not(atEnd(r("tmp"))), not(atEnd(r("mk"))))) { u => u.set("tmp", below(r("tmp"))); u.set("mk", below(r("mk"))) }
    // sub 2: cnt := cnt walked down h; sub 3: spanH += h
    p.when(all(at(LATL_CMP, 2), not(atEnd(r("tmp"))))) { u => u.set("cnt", below(r("cnt"))); u.set("tmp", below(r("tmp"))) }
    p.when(all(at(LATL_CMP, 2), atEnd(r("tmp")))) { u => u.bit("par", not(B("par"))); u.bit("parL", not(B("parL"))); u.bit("switched", True); u.set("tmp", r("hPtr")); u.sub(3) }
    p.when(all(at(LATL_CMP, 3), not(atEnd(r("tmp"))))) { u => u.set("spanH", u.alloc(UNARY, Null, r("spanH"), Null, Null)); u.set("tmp", below(r("tmp"))) }
    p.when(all(at(LATL_CMP, 3), atEnd(r("tmp")))) { u => u.sub(0) }

    // ---- LATL_MIR: no switch → left side done; else mir := x − (R − 2h + 1), cntL := 2h − |upL| --
    p.when(And(at(LATL_MIR, 0), not(B("eq")))) { u => u.bit("lm1", IsNull(r("sl"))); u.phase(LATR_ENTRY) }
    p.when(And(at(LATL_MIR, 0), B("eq"))) { u => u.set("cnt", r("uR")); u.set("tmp", r("u2h")); u.sub(1) }
    p.when(And(at(LATL_MIR, 1), not(atEnd(r("tmp"))))) { u => u.set("cnt", below(r("cnt"))); u.set("tmp", below(r("tmp"))) }
    p.when(And(at(LATL_MIR, 1), atEnd(r("tmp")))) { u => u.set("mir", r("hx")); u.sub(2) }
    p.when(And(at(LATL_MIR, 2), not(atEnd(r("cnt"))))) { u => u.set("mir", prev(r("mir"))); u.set("cnt", below(r("cnt"))) }
    p.when(And(at(LATL_MIR, 2), atEnd(r("cnt")))) { u => u.set("mir", prev(r("mir"))); u.set("tmp", r("u2h")); u.set("cnt", r("upL")); u.sub(3) }
    p.when(And(at(LATL_MIR, 3), not(atEnd(r("cnt"))))) { u => u.set("tmp", below(r("tmp"))); u.set("cnt", below(r("cnt"))) }
    p.when(And(at(LATL_MIR, 3), atEnd(r("cnt")))) { u => u.set("cntL", r("tmp")); u.phase(LATL) }

    // ---- LATL: scan S(first) leftward with the mirror; switch node at 2h ------------------------
    p.when(And(at(LATL), IsNull(r("sl")))) { u => u.bit("lm1", True); u.phase(LATR_ENTRY) }
    val sameL = SameTag(r("sl"), r("mir"))
    val switchNow = atEnd(below(r("cntL")))
    p.when(all(at(LATL), not(IsNull(r("sl"))), sameL)) { u =>
      u.set("sl", prev(r("sl"))); u.set("mir", prev(r("mir")))
      u.set("upL", Ite(switchNow, r("hPtr"), u.alloc(UNARY, Null, r("upL"), Null, Null)))
      u.set("cntL", Ite(switchNow, r("hPtr"), below(r("cntL"))))
      u.bit("par", Ite2(switchNow, not(B("par")), B("par"))); u.bit("parL", Ite2(switchNow, not(B("parL")), B("parL")))
      u.set("tmp", r("hPtr"))
      for (i <- 0 until phaseBits) { u.bit(s"p$i", Ite2(switchNow, bitOf(LATL_SW, i), bitOf(LATL, i))) }
      u.sub(0)
    }
    p.when(all(at(LATL), not(IsNull(r("sl"))), not(sameL))) { u => u.bit("lm1", False); u.phase(LATR_ENTRY) }
    p.when(And(at(LATL_SW), not(atEnd(r("tmp"))))) { u => u.set("spanH", u.alloc(UNARY, Null, r("spanH"), Null, Null)); u.set("tmp", below(r("tmp"))) }
    p.when(And(at(LATL_SW), atEnd(r("tmp")))) { u => u.phase(LATL) }

    // ---- LATR_ENTRY: confirmed radius of S(end), left probe, countdowns ----------------------
    // probe case (imm = false): confirmed = h, left probe = hl (x − 1), the stream continues
    p.when(all(at(LATR_ENTRY, 0), not(B("imm")))) { u =>
      u.set("upR", r("hPtr")); u.set("cntR", r("hPtr")); u.bit("useCur", False)
      u.set("cnt", Null); u.sub(6)
    }
    // immediate case: confirmed chain cnt := rY (cnt2), then while ≥ 2h: pop h, switch
    p.when(all(at(LATR_ENTRY, 0), B("imm"))) { u => u.set("cnt", r("cnt2")); u.sub(1) }
    p.when(at(LATR_ENTRY, 1)) { u => u.set("tmp", r("u2h")); u.set("mk", r("cnt")); u.sub(2) }
    p.when(all(at(LATR_ENTRY, 2), atEnd(r("tmp")))) { u => u.set("tmp", r("hPtr")); u.sub(3) }                              // ≥ 2h
    p.when(all(at(LATR_ENTRY, 2), not(atEnd(r("tmp"))), atEnd(r("mk")))) { u => u.set("mk", Null); u.set("tmp", r("RT")); u.set("g1", Null); u.sub(5) }
    p.when(all(at(LATR_ENTRY, 2), not(atEnd(r("tmp"))), not(atEnd(r("mk"))))) { u => u.set("tmp", below(r("tmp"))); u.set("mk", below(r("mk"))) }
    p.when(all(at(LATR_ENTRY, 3), not(atEnd(r("tmp"))))) { u => u.set("cnt", below(r("cnt"))); u.set("tmp", below(r("tmp"))) }
    p.when(all(at(LATR_ENTRY, 3), atEnd(r("tmp")))) { u => u.bit("par", not(B("par"))); u.set("tmp", r("hPtr")); u.sub(4) }
    p.when(all(at(LATR_ENTRY, 4), not(atEnd(r("tmp"))))) { u => u.set("spanH", u.alloc(UNARY, Null, r("spanH"), Null, Null)); u.set("tmp", below(r("tmp"))) }
    p.when(all(at(LATR_ENTRY, 4), atEnd(r("tmp")))) { u => u.sub(1) }
    // sub 5: left probe := the trail cell 2c + 1 (+1 if a mismatch cell is on top) consumed cells below the top.
    //        tmp walks the trail (marks and units skipped), mk counts c twice (toggle), then the extra one(s)
    p.when(all(at(LATR_ENTRY, 5), IsNull(r("g1")))) { u => u.set("g1", r("hx")); u.set("mk", r("cnt")); u.bit("tog", False); u.bit("eq", B("endMis")); u.bit("switched", False); u.sub(7) }
    // sub 7: find the top point (switched := true), skip it, skip one more if it is the mismatch cell, then 2c points, then one more
    p.when(all(at(LATR_ENTRY, 7), not(TagIs(r("tmp"), RTC)))) { u => u.set("tmp", below(r("tmp"))) }
    p.when(all(at(LATR_ENTRY, 7), TagIs(r("tmp"), RTC), not(B("switched")))) { u => u.bit("switched", True); u.set("tmp", below(r("tmp"))) }
    p.when(all(at(LATR_ENTRY, 7), TagIs(r("tmp"), RTC), B("switched"), B("eq"))) { u => u.set("tmp", below(r("tmp"))); u.bit("eq", False) }
    p.when(all(at(LATR_ENTRY, 7), TagIs(r("tmp"), RTC), B("switched"), not(B("eq")), not(atEnd(r("mk"))))) { u =>
      u.set("tmp", below(r("tmp"))); u.bit("tog", not(B("tog")))
      u.set("mk", Ite(B("tog"), below(r("mk")), r("mk")))
    }
    p.when(all(at(LATR_ENTRY, 7), TagIs(r("tmp"), RTC), B("switched"), not(B("eq")), atEnd(r("mk")))) { u =>
      u.set("hl", prev(r("tmp")))
      u.set("upR", r("cnt")); u.bit("useCur", B("endMis"))
      u.set("tmp", r("u2h")); u.set("mk", r("cnt")); u.sub(6)
    }
    // sub 6: cntR := u2h walked down |cnt| (probe case: cnt = null → cntR := u2h?? no: probe case sets cntR = hPtr already)
    p.when(all(at(LATR_ENTRY, 6), IsNull(r("cnt")))) { u => u.set("RT", u.alloc(MARK, Null, r("RT"), Null, Null)); u.phase(LATR_BCNT) }
    p.when(all(at(LATR_ENTRY, 6), not(IsNull(r("cnt"))), not(atEnd(r("mk"))))) { u => u.set("tmp", below(r("tmp"))); u.set("mk", below(r("mk"))) }
    p.when(all(at(LATR_ENTRY, 6), not(IsNull(r("cnt"))), atEnd(r("mk")))) { u => u.set("cntR", r("tmp")); u.set("cnt", Null); u.set("RT", u.alloc(MARK, Null, r("RT"), Null, Null)); u.phase(LATR_BCNT) }

    // ---- LATR_BCNT: bcnt := upL walked down (1 + [imm: R] + [parL: h]) with wrap-around mod 2h -----
    def stepWrap(u: Prog#Upd): Unit = u.set("bcnt", Ite(atEnd(r("bcnt")), below(r("u2h")), below(r("bcnt"))))
    p.when(at(LATR_BCNT, 0)) { u => u.set("bcnt", r("upL")); u.set("tmp", Ite(B("imm"), r("uR"), Null)); u.set("mk", Ite(B("parL"), r("hPtr"), Null)); u.sub(1) }
    p.when(at(LATR_BCNT, 1)) { u => stepWrap(u); u.sub(2) }                                                 // the "+1"
    p.when(all(at(LATR_BCNT, 2), not(atEnd(r("tmp"))))) { u => stepWrap(u); u.set("tmp", below(r("tmp"))) }
    p.when(all(at(LATR_BCNT, 2), atEnd(r("tmp")), not(atEnd(r("mk"))))) { u => stepWrap(u); u.set("mk", below(r("mk"))) }
    p.when(all(at(LATR_BCNT, 2), atEnd(r("tmp")), atEnd(r("mk")))) { u => u.set("mk", Null); u.phase(LATR) }

    // ---- LATR: the right sweep ----------------------------------------------------------------------
    p.when(And(at(LATR), IsNull(r("hl")))) { u => u.bit("endMis", False); u.phase(LATDONE) }
    consume(And(at(LATR), not(IsNull(r("hl"))))) { (u, c, timely) =>
      val sm = SameTag(r("hl"), c)
      u.set("hl", Ite(sm, prev(r("hl")), r("hl")))
      u.set("lastR", Ite(sm, c, r("lastR")))
      u.set("cntR", Ite(sm, below(r("cntR")), r("cntR")))
      u.bit("acc", Or(B("acc"), all(sm, B("lm1"), atEnd(r("bcnt")), timely)))
      u.set("bcnt", Ite(sm, Ite(atEnd(r("bcnt")), below(r("u2h")), below(r("bcnt"))), r("bcnt")))
      u.bit("endMis", not(sm))
      val sw = And(sm, atEnd(below(r("cntR"))))
      for (i <- 0 until phaseBits) { u.bit(s"p$i", Ite3(sm, sw, bitOf(LATR_SW, i), bitOf(LATR, i), bitOf(LATDONE, i))) }
      u.sub(0)
    }
    // LATR_SW: left probe := the 2h+1-th consumed cell below the top; mark; countdowns; spanH += h
    p.when(at(LATR_SW, 0)) { u => u.set("tmp", r("RT")); u.set("mk", r("u2h")); u.bit("eq", False); u.sub(1) }
    p.when(all(at(LATR_SW, 1), not(TagIs(r("tmp"), RTC)))) { u => u.set("tmp", below(r("tmp"))) }
    p.when(all(at(LATR_SW, 1), TagIs(r("tmp"), RTC), not(B("eq")))) { u => u.bit("eq", True); u.set("tmp", below(r("tmp"))) }   // the top point
    p.when(all(at(LATR_SW, 1), TagIs(r("tmp"), RTC), B("eq"), not(atEnd(r("mk"))))) { u => u.set("tmp", below(r("tmp"))); u.set("mk", below(r("mk"))) }
    p.when(all(at(LATR_SW, 1), TagIs(r("tmp"), RTC), B("eq"), atEnd(r("mk")))) { u =>
      u.set("hl", prev(r("tmp"))); u.set("cntR", r("hPtr")); u.set("upR", r("hPtr")); u.bit("par", not(B("par")))
      u.set("RT", u.alloc(MARK, Null, r("RT"), Null, Null)); u.set("tmp", r("hPtr")); u.sub(2)
    }
    p.when(all(at(LATR_SW, 2), not(atEnd(r("tmp"))))) { u => u.set("spanH", u.alloc(UNARY, Null, r("spanH"), Null, Null)); u.set("tmp", below(r("tmp"))) }
    p.when(all(at(LATR_SW, 2), atEnd(r("tmp")))) { u => u.phase(LATR) }

    // ---- LATDONE: rEnd chain from the trail; symmetric ⇔ even nodes ∧ rFirst = rEnd ---------------
    p.when(at(LATDONE, 0)) { u => u.set("tmp", Ite(B("endMis"), below(r("RT")), r("RT"))); u.sub(1) }
    p.when(all(at(LATDONE, 1), isMark(r("tmp")))) { u => u.set("cnt", r("upL")); u.set("mk", r("upR")); u.sub(2) }
    p.when(all(at(LATDONE, 1), not(isMark(r("tmp"))))) { u => u.set("upR", u.alloc(UNARY, Null, r("upR"), Null, Null)); u.set("tmp", below(r("tmp"))) }
    p.when(all(at(LATDONE, 2), atEnd(r("cnt")), atEnd(r("mk")))) { u => u.bit("eq", True); u.sub(3) }
    p.when(all(at(LATDONE, 2), Or(atEnd(r("cnt")), atEnd(r("mk"))), not(And(atEnd(r("cnt")), atEnd(r("mk")))))) { u => u.bit("eq", False); u.sub(3) }
    p.when(all(at(LATDONE, 2), not(atEnd(r("cnt"))), not(atEnd(r("mk"))))) { u => u.set("cnt", below(r("cnt"))); u.set("mk", below(r("mk"))) }
    p.when(all(at(LATDONE, 3), not(B("par")), B("eq"))) { u =>
      u.set("hx", r("lastR")); u.set("cnt", r("upL")); u.bit("tog", False); u.set("kEnd", Null); u.set("cnt2", Null); u.set("mk", Null)
      u.phase(LATDONE_SYM)
    }
    p.when(all(at(LATDONE, 3), Or(B("par"), not(B("eq"))))) { u => u.set("hx", r("lastR")); u.set("cnt", r("upR")); u.phase(LATDONE_NSYM) }

    // LATDONE_SYM sub 0..2: hx := L⁺ walked left by (|upL| + |upR| + |spanH|)/2 (tog: every second step)
    def halfWalk(s: Int, next: Int, nextChain: E): Unit = {
      p.when(all(at(LATDONE_SYM, s), not(atEnd(r("cnt"))))) { u =>
        u.set("cnt", below(r("cnt"))); u.bit("tog", not(B("tog")))
        u.set("hx", Ite(B("tog"), prev(r("hx")), r("hx")))
      }
      p.when(all(at(LATDONE_SYM, s), atEnd(r("cnt")))) { u => u.set("cnt", nextChain); u.sub(next) }
    }
    halfWalk(0, 1, r("upR"))
    halfWalk(1, 2, r("spanH"))
    halfWalk(2, 3, r("upR"))
    // sub 3: kEnd := L⁺ walked left by |upR|
    p.when(all(at(LATDONE_SYM, 3), IsNull(r("kEnd")))) { u => u.set("kEnd", r("lastR")) }
    p.when(all(at(LATDONE_SYM, 3), not(IsNull(r("kEnd"))), not(atEnd(r("cnt"))))) { u => u.set("kEnd", prev(r("kEnd"))); u.set("cnt", below(r("cnt"))) }
    p.when(all(at(LATDONE_SYM, 3), not(IsNull(r("kEnd"))), atEnd(r("cnt")))) { u => u.set("RT", u.alloc(MARK, Null, r("RT"), Null, Null)); u.set("cnt", r("upL")); u.bit("tog", False); u.sub(4) }
    // sub 4..6: RT := (|upL|+|upR|+|spanH|)/2 units on the mark (the radius of S(L⁰) so far)
    def halfPush(s: Int, next: Int, nextChain: E): Unit = {
      p.when(all(at(LATDONE_SYM, s), not(atEnd(r("cnt"))))) { u =>
        u.set("cnt", below(r("cnt"))); u.bit("tog", not(B("tog")))
        u.set("RT", Ite(B("tog"), u.alloc(UNARY, Null, r("RT"), Null, Null), r("RT")))
      }
      p.when(all(at(LATDONE_SYM, s), atEnd(r("cnt")))) { u => u.set("cnt", nextChain); u.sub(next) }
    }
    halfPush(4, 5, r("upR"))
    halfPush(5, 6, r("spanH"))
    halfPush(6, 7, r("spanH"))
    // sub 7: cnt2 := |spanH|/2 (kd = kEnd − L⁰), then continue the scan of S(L⁰)
    p.when(all(at(LATDONE_SYM, 7), IsNull(r("mk")))) { u => u.set("mk", r("one")); u.bit("tog", False) }   // mk ≠ null marks "started"
    p.when(all(at(LATDONE_SYM, 7), not(IsNull(r("mk"))), not(atEnd(r("cnt"))))) { u =>
      u.set("cnt", below(r("cnt"))); u.bit("tog", not(B("tog")))
      u.set("cnt2", Ite(B("tog"), u.alloc(UNARY, Null, r("cnt2"), Null, Null), r("cnt2")))
    }
    p.when(all(at(LATDONE_SYM, 7), not(IsNull(r("mk"))), atEnd(r("cnt")))) { u =>
      u.set("mk", Null); u.set("hl", r("sl")); u.bit("useCur", B("endMis")); u.bit("bnd", True); u.set("uR", r("RT"))
      u.phase(SCAN)
    }

    // LATDONE_NSYM: x := end (L⁺ walked left by |upR|), R = rEnd, no bound
    p.when(And(at(LATDONE_NSYM), not(atEnd(r("cnt"))))) { u => u.set("hx", prev(r("hx"))); u.set("cnt", below(r("cnt"))) }
    p.when(And(at(LATDONE_NSYM), atEnd(r("cnt")))) { u => u.set("uR", r("upR")); u.set("kEnd", Null); u.bit("bnd", False); u.phase(FINDY0) }

    val step = p.build(inpRegs ++ machRegs, bitNames)
    Machine("ab", inpRegs.map(_ -> Inp) ++ machRegs.map(_ -> Mach), bitNames, Set("acc"), "acc", step)
  }

  private def bitOf(k: Int, i: Int): C = if (((k >> i) & 1) == 1) { True } else { False }
  private def Ite2(g: C, a: C, b: C): C = Or(And(g, a), And(Not(g), b))
  private def Ite3(g1: C, g2: C, a: C, b: C, c: C): C = Ite2(g1, Ite2(g2, a, b), c)

  def oracle(w: String): Boolean = Exhaust.isPal(w)
}
