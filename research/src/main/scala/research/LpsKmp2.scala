package research

/** The window-KMP matcher as one universal local step `U` (DESIGN §2–§4), repeated `n` times per tick.
  *
  * One `U` does exactly one of: a back-up unit (BACK/MBACK), one step of the archive reversal (REV),
  * one step of a queue reversal, loading one letter, or processing the loaded letter (in BUILD:
  * allocate its entry, enqueue it and compare; in MATCH: compare). Each kind of cell has one
  * allocation site in `U` (the branches share it through `Ite` fields), so a pointer's site bits
  * only name which copy of `U` allocated it.
  *
  * Same input convention as `LpsKmp`: pattern letters, '#', then the text; `acc` after a tick
  * means the text read so far ends with the pattern.
  */
object LpsKmp2 {
  import Lpm.{E, C, TE, Prog, Step}
  import Lpm.TE.Const
  import Lpm.E.*
  import Lpm.C.*
  import Lps.*

  val HASH = 2      // input tags: a = 0, b = 1, # = 2, separator = 3
  val IQC = 4       // input queue cell: p1 = input cell, p2 = below
  val UNARY = 5     // p2 = below
  val ENTRY = 6     // p1 = letter cell, p2 = fail entry, p3 = gap root (i - pi_i), p4 = previous entry
  val ENTRY0 = 7
  val END = 8
  val EQC = 9       // entry queue cell: p2 = below, p3 = entry

  val BUILD = 0; val BACK = 1; val REV = 2; val MATCH = 3; val MBACK = 4; val DEAD = 5
  val phaseBits = 3

  val entryKinds: Set[K] = Set(KTag(ENTRY), KTag(ENTRY0), KTag(END))
  val regs: Vector[(String, Set[K])] = Vector(
    "c" -> Set(KInp), "pendF" -> Set(KTag(IQC)), "pendB" -> Set(KTag(IQC)), "pendN" -> Set(KTag(IQC)),
    "ent" -> entryKinds, "cur" -> entryKinds, "pFail" -> entryKinds,
    "front" -> Set(KTag(EQC)), "rear" -> Set(KTag(EQC)),
    "gap" -> Set(KTag(UNARY)), "gw" -> Set(KTag(UNARY)), "pRoot" -> Set(KTag(UNARY)))
  val schema: Map[Int, Vector[Set[K]]] = Map(
    IQC -> Vector(Set(KInp), Set(KTag(IQC)), Set(), Set()),
    EQC -> Vector(Set(), Set(KTag(EQC)), entryKinds, Set()),
    UNARY -> Vector(Set(), Set(KTag(UNARY)), Set(), Set()),
    ENTRY -> Vector(Set(KInp), entryKinds, Set(KTag(UNARY)), entryKinds),
    ENTRY0 -> Vector(Set(KInp), entryKinds, Set(KTag(UNARY)), entryKinds),
    END -> Vector(Set(KInp), entryKinds, Set(KTag(UNARY)), entryKinds))
  val bitNames: Vector[String] = (0 until phaseBits).map(i => s"p$i").toVector ++ Vector("have", "cmp", "erev", "rev", "acc", "again", "started")

  private def r(n: String): E = Reg(n)
  private def B(n: String): C = Bit(n)
  private def not(c: C): C = Not(c)
  private def below(e: E): E = Fld(e, 2)
  private def entryOf(q: E): E = Fld(q, 3)
  private def phaseIs(k: Int): C = all((0 until phaseBits).map(i => if (((k >> i) & 1) == 1) { B(s"p$i") } else { Not(B(s"p$i")) })*)
  private def bitOf(k: Int, i: Int): C = if (((k >> i) & 1) == 1) { True } else { False }
  private def Ite2(g: C, a: C, b: C): C = Or(And(g, a), And(Not(g), b))
  private def selectE(cases: Seq[(C, E)], default: E): E = cases.foldRight(default) { case ((g, v), acc) => Ite(g, v, acc) }

  /** the input step: the letter joins the FIFO (directly at the front when the queue is empty) */
  def in: Step = {
    val p = new Prog(phaseBits, 0, "", exclusive = true)
    val queueEmpty = all(IsNull(r("pendF")), IsNull(r("pendB")), IsNull(r("pendN")), not(B("rev")))
    p.when(queueEmpty) { u => u.set("pendF", u.alloc(IQC, In, Null, Null, Null)); u.bit("acc", False) }
    p.when(not(B("rev"))) { u => u.set("pendB", u.alloc(IQC, In, r("pendB"), Null, Null)); u.bit("acc", False) }
    p.when(True) { u => u.set("pendN", u.alloc(IQC, In, r("pendN"), Null, Null)); u.bit("acc", False) }
    p.build(regs.map(_._1), bitNames)
  }

  /** the universal step: written as one guard set with all cell kinds allocated once (guards select the fields) */
  def universal: Step = {
    val queueEmpty = all(IsNull(r("pendF")), IsNull(r("pendB")), IsNull(r("pendN")), not(B("rev")))
    val c = r("c"); val cur = r("cur"); val front = r("front"); val rear = r("rear"); val gap = r("gap"); val gw = r("gw"); val ent = r("ent")
    val same = SameTag(c, Fld(cur, 1))
    val isHash = TagIs(c, HASH)
    val gwDone = IsNull(below(gw))
    val firstLetter = IsNull(ent)
    // --- the guards, in priority order (exclusive) ---
    val gBack = phaseIs(BACK)
    val gRevPush = And(phaseIs(REV), not(IsNull(cur)))
    val gRevEnd = And(phaseIs(REV), IsNull(cur))
    val gMBack = phaseIs(MBACK)
    val busy = Or(phaseIs(BUILD), phaseIs(MATCH))
    val gLoadF = all(busy, not(B("have")), not(B("rev")), not(IsNull(r("pendF"))))
    val gLoadRev = all(busy, not(B("have")), Or(B("rev"), And(IsNull(r("pendF")), not(IsNull(r("pendB"))))))
    val haveB = all(phaseIs(BUILD), B("have"))
    val gDead = all(haveB, not(B("cmp")), isHash, not(B("started")))                       // '#' first: reject everything
    val gFirst = all(haveB, not(B("cmp")), firstLetter)                                       // ENTRY0 and the first gap cell
    val gEnd = all(haveB, not(B("cmp")), isHash)                                              // END entry, then the archive reversal
    val gNew = all(haveB, not(B("cmp")))                                                      // a pattern letter: entry, enqueue, compare
    val gCmp = all(haveB, B("cmp"))                                                           // compare only (after a back-up or a reversal)
    val cmpOK = all(same, not(B("erev")), not(IsNull(front)))                                // the next entry is at the front
    val cmpRev = all(same, Or(B("erev"), IsNull(front)))                                     // bring the rear to the front, one cell
    val cmpGap = all(not(same), TagIs(cur, ENTRY0))
    val cmpBack = all(not(same), not(TagIs(cur, ENTRY0)))
    val haveM = all(phaseIs(MATCH), B("have"))
    val gMDead = all(haveM, isHash)
    val gMSame = all(haveM, same)
    val gMGap = all(haveM, not(same), TagIs(cur, ENTRY0))
    val gMBackStart = all(haveM, not(same))
    // --- one allocation site per kind of cell, fields selected by the branch (priority order) ---
    val p = new Prog(phaseBits, 0, "", exclusive = true)
    val pushing = any(gBack, gRevPush, gMBack)
    val e: E = p.site(TE.Ite(gFirst, Const(ENTRY0), TE.Ite(gEnd, Const(END), Const(ENTRY))),
      c, Ite(gFirst, Null, r("pFail")), Ite(gFirst, Null, r("pRoot")), Ite(gFirst, Null, ent))
    val q: E = p.site(Const(EQC), Null, selectE(Vector((pushing, front), (gNew, rear)), front),
      selectE(Vector((pushing, cur), (gNew, e)), entryOf(rear)), Null)
    val g: E = p.site(Const(UNARY), Null, Ite(gFirst, Null, gap), Null, Null)
    val iq: E = p.site(Const(IQC), Fld(r("pendB"), 1), r("pendF"), Null, Null)
    p.when(gBack) { u =>
      u.set("front", q); u.set("cur", Fld(cur, 4))
      u.set("gap", g); u.set("gw", below(gw))
      for (i <- 0 until phaseBits) { u.bit(s"p$i", Ite2(gwDone, bitOf(BUILD, i), bitOf(BACK, i))) }
    }
    p.when(gRevPush) { u => u.set("front", q); u.set("cur", Fld(cur, 4)) }
    p.when(gRevEnd) { u => u.set("cur", entryOf(front)); u.set("front", below(front)); u.phase(MATCH) }
    p.when(gMBack) { u =>
      u.set("front", q); u.set("cur", Fld(cur, 4)); u.set("gw", below(gw))
      for (i <- 0 until phaseBits) { u.bit(s"p$i", Ite2(gwDone, bitOf(MATCH, i), bitOf(MBACK, i))) }
      u.bit("again", Ite2(gwDone, False, B("again")))
    }
    p.when(gLoadF) { u => u.set("c", Fld(r("pendF"), 1)); u.set("pendF", below(r("pendF"))); u.bit("have", True) }
    p.when(gLoadRev) { u =>
      val last = IsNull(below(r("pendB")))
      u.set("pendF", iq)
      u.set("pendB", Ite(last, r("pendN"), below(r("pendB"))))
      u.set("pendN", Ite(last, Null, r("pendN")))
      u.bit("rev", not(last))
    }
    p.when(gDead) { u => u.phase(DEAD); u.bit("have", False) }
    p.when(gFirst) { u =>
      u.set("ent", e); u.set("cur", e); u.set("pFail", e); u.set("gap", g); u.set("pRoot", g); u.bit("have", False); u.bit("started", True)
    }
    p.when(gEnd) { u =>
      u.set("ent", e); u.set("cur", e); u.set("front", Null); u.bit("have", False); u.phase(REV)
    }
    p.when(gNew) { u =>
      // allocate the letter's entry and enqueue it at the rear, then compare in the same step when possible
      u.set("ent", e); u.set("rear", q)
      u.set("cur", Ite(cmpOK, entryOf(front), cur))
      u.set("front", Ite(cmpOK, below(front), front))
      u.set("pFail", selectE(Vector((cmpOK, entryOf(front)), (cmpGap, cur)), r("pFail")))
      u.set("pRoot", selectE(Vector((cmpOK, gap), (cmpGap, g)), r("pRoot")))
      u.set("gap", Ite(cmpGap, g, gap))
      u.set("gw", Ite(cmpBack, Fld(cur, 3), gw))
      u.bit("have", Ite2(Or(cmpOK, cmpGap), False, True))
      u.bit("cmp", Ite2(Or(cmpRev, cmpBack), True, False))
      for (i <- 0 until phaseBits) { u.bit(s"p$i", Ite2(cmpBack, bitOf(BACK, i), bitOf(BUILD, i))) }
    }
    p.when(gCmp) { u =>
      val last = IsNull(below(rear))
      u.set("cur", Ite(cmpOK, entryOf(front), cur))
      u.set("front", selectE(Vector((cmpOK, below(front)), (cmpRev, q)), front))
      u.set("rear", Ite(cmpRev, below(rear), rear))
      u.bit("erev", Ite2(cmpRev, not(last), B("erev")))
      u.set("pFail", selectE(Vector((cmpOK, entryOf(front)), (cmpGap, cur)), r("pFail")))
      u.set("pRoot", selectE(Vector((cmpOK, gap), (cmpGap, g)), r("pRoot")))
      u.set("gap", Ite(cmpGap, g, gap))
      u.set("gw", Ite(cmpBack, Fld(cur, 3), gw))
      u.bit("have", Ite2(Or(cmpOK, cmpGap), False, True))
      u.bit("cmp", Ite2(Or(cmpRev, cmpBack), True, False))
      for (i <- 0 until phaseBits) { u.bit(s"p$i", Ite2(cmpBack, bitOf(BACK, i), bitOf(BUILD, i))) }
    }
    p.when(gMDead) { u => u.phase(DEAD); u.bit("have", False) }
    p.when(gMSame) { u =>
      val nxt = entryOf(front)
      val full = TagIs(nxt, END)
      u.set("cur", nxt); u.set("front", below(front))
      u.bit("acc", Or(B("acc"), And(full, queueEmpty)))
      u.set("gw", Fld(nxt, 3)); u.bit("again", False); u.bit("have", False)
      for (i <- 0 until phaseBits) { u.bit(s"p$i", Ite2(full, bitOf(MBACK, i), bitOf(MATCH, i))) }
    }
    p.when(gMGap) { u => u.bit("have", False) }
    p.when(gMBackStart) { u => u.set("gw", Fld(cur, 3)); u.bit("again", True); u.phase(MBACK) }
    p.build(regs.map(_._1), bitNames)
  }

  def machine(n: Int): Lps.Machine =
    Lps.Machine("ab#", regs, bitNames, Set(), "acc", schema, Vector("IN") ++ Vector.fill(n)("U"), Map("IN" -> in, "U" -> universal))

  def oracle(w: String): Boolean = LpmKmp.oracle(w)
}
