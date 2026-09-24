package research

/** The window-KMP matcher of `LpmKmp` in slot-scheduled form (see `Lps`). */
object LpsKmp {
  import Lpm.{E, C, Prog, Step}
  import Lpm.E.*
  import Lpm.C.*
  import Lps.*

  val HASH = 2      // input tags: a = 0, b = 1, # = 2, separator = 3
  val IQC = 4       // input queue cell: p1 = input cell, p2 = below
  val UNARY = 5     // p2 = below
  val ENTRY = 6     // p1 = next letter cell, p2 = fail, p3 = gap root, p4 = previous entry
  val ENTRY0 = 7
  val END = 8
  val EQC = 9       // entry queue cell: p3 = entry, p2 = below

  val phaseBits = 3
  val subBits = 2
  val BUILD = 0; val BACK = 1; val REV = 2; val MATCH = 3; val MBACK = 4; val DEAD = 5

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
  val bitNames: Vector[String] = (0 until phaseBits).map(i => s"p$i").toVector ++ (0 until subBits).map(i => s"s$i").toVector ++
    Vector("wait", "acc", "rev", "erev", "started", "again")

  private def phaseIs(k: Int): C = all((0 until phaseBits).map { i => if (((k >> i) & 1) == 1) { Bit(s"p$i") } else { Not(Bit(s"p$i")) } }*)
  private def subIs(k: Int): C = all((0 until subBits).map { i => if (((k >> i) & 1) == 1) { Bit(s"s$i") } else { Not(Bit(s"s$i")) } }*)
  private def r(n: String): E = Reg(n)
  private def nextOf(e: E): E = Fld(e, 1)
  private def below(e: E): E = Fld(e, 2)
  private def B(n: String): C = Bit(n)
  private def not(c: C): C = Not(c)
  private def bitOf(k: Int, i: Int): C = if (((k >> i) & 1) == 1) { True } else { False }
  private def Ite2(g: C, a: C, b: C): C = Or(And(g, a), And(Not(g), b))
  private def at(k: Int, s: Int = -1): C = if (s < 0) { And(not(B("wait")), phaseIs(k)) } else { all(not(B("wait")), phaseIs(k), subIs(s)) }
  private def setPhaseIf(u: Prog#Upd, g: C, ifTrue: Int, ifFalse: Int): Unit =
    for (i <- 0 until phaseBits) { u.bit(s"p$i", Ite2(g, bitOf(ifTrue, i), bitOf(ifFalse, i))) }
  private def setSubIf(u: Prog#Upd, g: C, ifTrue: Int, ifFalse: Int): Unit =
    for (i <- 0 until subBits) { u.bit(s"s$i", Ite2(g, bitOf(ifTrue, i), bitOf(ifFalse, i))) }

  def slots: Map[String, Step] = {
    val queueEmpty = all(IsNull(r("pendF")), IsNull(r("pendB")), IsNull(r("pendN")), not(B("rev")))
    val in = new Prog(phaseBits, subBits)
    // an empty queue takes the letter directly at its front (no reversal needed in the synchronous case)
    in.when(queueEmpty) { u => u.set("pendF", u.alloc(IQC, In, Null, Null, Null)); u.bit("wait", False); u.bit("acc", False) }
    in.when(And(not(queueEmpty), not(B("rev")))) { u => u.set("pendB", u.alloc(IQC, In, r("pendB"), Null, Null)); u.bit("wait", False); u.bit("acc", False) }
    in.when(And(not(queueEmpty), B("rev"))) { u => u.set("pendN", u.alloc(IQC, In, r("pendN"), Null, Null)); u.bit("wait", False); u.bit("acc", False) }

    val cons = new Prog(phaseBits, subBits)
    val wantLetter = Or(at(BUILD, 0), at(MATCH, 0))
    cons.when(all(wantLetter, not(B("rev")), not(IsNull(r("pendF"))))) { u =>
      val c = nextOf(r("pendF"))
      u.set("c", c); u.set("pendF", below(r("pendF")))
      u.bit("started", True)
      // the first letter may not be the marker; otherwise the phase is kept and the letter is handed on
      val bad = all(not(B("started")), TagIs(c, HASH))
      for (i <- 0 until phaseBits) { u.bit(s"p$i", Ite2(bad, bitOf(DEAD, i), Bit(s"p$i"))) }
      u.sub(1)
    }
    cons.when(all(wantLetter, Or(B("rev"), And(IsNull(r("pendF")), not(IsNull(r("pendB"))))))) { u =>
      val last = IsNull(below(r("pendB")))
      u.set("pendF", u.alloc(IQC, nextOf(r("pendB")), r("pendF"), Null, Null))
      u.set("pendB", Ite(last, r("pendN"), below(r("pendB"))))
      u.set("pendN", Ite(last, Null, r("pendN")))
      u.bit("rev", not(last))
    }
    cons.when(all(wantLetter, not(B("rev")), IsNull(r("pendF")), IsNull(r("pendB")))) { u => u.bit("wait", True) }

    val fin = new Prog(phaseBits, subBits)
    fin.when(And(at(BUILD, 1), IsNull(r("ent")))) { u => u.set("ent", u.alloc(ENTRY0, r("c"), Null, Null, Null)); u.set("cur", Made); u.sub(2) }
    fin.when(all(at(BUILD, 1), not(IsNull(r("ent"))), not(TagIs(r("c"), HASH)))) { u =>
      u.set("ent", u.alloc(ENTRY, r("c"), r("pFail"), r("pRoot"), r("ent"))); u.sub(2)
    }
    fin.when(all(at(BUILD, 1), not(IsNull(r("ent"))), TagIs(r("c"), HASH))) { u =>
      u.set("ent", u.alloc(END, r("c"), r("pFail"), r("pRoot"), r("ent"))); u.set("cur", Made); u.set("front", Null); u.phase(REV)
    }

    val gap = new Prog(phaseBits, subBits)
    gap.when(And(at(BUILD, 2), IsNull(r("gap")))) { u => u.set("gap", u.alloc(UNARY, Null, Null, Null, Null)); u.set("pFail", r("cur")); u.set("pRoot", Made); u.sub(0) }

    val enq = new Prog(phaseBits, subBits)
    enq.when(And(at(BUILD, 2), not(IsNull(r("gap"))))) { u => u.set("rear", u.alloc(EQC, Null, r("rear"), r("ent"), Null)); u.sub(3) }

    val step = new Prog(phaseBits, subBits)
    val same = SameTag(r("c"), nextOf(r("cur")))
    step.when(all(at(BUILD, 3), same, not(B("erev")), not(IsNull(r("front"))))) { u =>
      u.set("cur", Fld(r("front"), 3)); u.set("front", below(r("front"))); u.set("pFail", Fld(r("front"), 3)); u.set("pRoot", r("gap")); u.sub(0)
    }
    step.when(all(at(BUILD, 3), same, Or(B("erev"), And(IsNull(r("front")), not(IsNull(r("rear"))))))) { u =>
      val last = IsNull(below(r("rear")))
      u.set("front", u.alloc(EQC, Null, r("front"), Fld(r("rear"), 3), Null)); u.set("rear", below(r("rear"))); u.bit("erev", not(last))
    }
    step.when(all(at(BUILD, 3), not(same), TagIs(r("cur"), ENTRY0))) { u =>
      u.set("gap", u.alloc(UNARY, Null, r("gap"), Null, Null)); u.set("pFail", r("cur")); u.set("pRoot", Made); u.sub(0)
    }
    step.when(all(at(BUILD, 3), not(same), not(TagIs(r("cur"), ENTRY0)))) { u => u.set("gw", Fld(r("cur"), 3)); u.phase(BACK) }
    step.when(And(at(REV), IsNull(r("cur")))) { u => u.set("cur", Fld(r("front"), 3)); u.set("front", below(r("front"))); u.phase(MATCH) }
    step.when(And(at(MATCH, 1), TagIs(r("c"), HASH))) { u => u.phase(DEAD) }
    step.when(all(at(MATCH, 1), not(TagIs(r("c"), HASH)), same)) { u =>
      val nxt = Fld(r("front"), 3)
      u.set("cur", nxt); u.set("front", below(r("front")))
      u.bit("acc", Or(B("acc"), And(TagIs(nxt, END), queueEmpty)))
      setPhaseIf(u, TagIs(nxt, END), MBACK, MATCH); u.sub(0)
      u.set("gw", Fld(nxt, 3)); u.bit("again", False)
    }
    step.when(all(at(MATCH, 1), not(TagIs(r("c"), HASH)), not(same), TagIs(r("cur"), ENTRY0))) { u => u.sub(0) }
    step.when(all(at(MATCH, 1), not(TagIs(r("c"), HASH)), not(same), not(TagIs(r("cur"), ENTRY0)))) { u => u.set("gw", Fld(r("cur"), 3)); u.bit("again", True); u.phase(MBACK) }

    val ba = new Prog(phaseBits, subBits)
    ba.when(at(BACK, 0)) { u => u.set("front", u.alloc(EQC, Null, r("front"), r("cur"), Null)); u.set("cur", Fld(r("cur"), 4)); u.sub(1) }
    ba.when(And(at(REV), not(IsNull(r("cur"))))) { u => u.set("front", u.alloc(EQC, Null, r("front"), r("cur"), Null)); u.set("cur", Fld(r("cur"), 4)) }
    ba.when(at(MBACK)) { u =>
      u.set("front", u.alloc(EQC, Null, r("front"), r("cur"), Null)); u.set("cur", Fld(r("cur"), 4)); u.set("gw", below(r("gw")))
      val done = IsNull(below(r("gw")))
      setPhaseIf(u, done, MATCH, MBACK)
      setSubIf(u, And(done, B("again")), 1, 0)
      u.bit("again", Ite2(done, False, B("again")))
    }

    val bb = new Prog(phaseBits, subBits)
    bb.when(at(BACK, 1)) { u =>
      u.set("gap", u.alloc(UNARY, Null, r("gap"), Null, Null)); u.set("gw", below(r("gw")))
      setPhaseIf(u, IsNull(below(r("gw"))), BUILD, BACK); setSubIf(u, IsNull(below(r("gw"))), 3, 0)
    }

    val names = regs.map(_._1)
    Map("IN" -> in.build(names, bitNames), "CONS" -> cons.build(names, bitNames), "FIN" -> fin.build(names, bitNames),
      "GAP" -> gap.build(names, bitNames), "ENQ" -> enq.build(names, bitNames), "STEP" -> step.build(names, bitNames),
      "BA" -> ba.build(names, bitNames), "BB" -> bb.build(names, bitNames))
  }

  /** `groups` letters can be taken per tick; each group has room for two back-up steps */
  def schedule(groups: Int): Vector[String] =
    Vector("IN") ++ Vector.fill(groups)(Vector("CONS", "FIN", "GAP", "ENQ", "STEP", "BA", "BB", "STEP", "BA", "BB")).flatten

  def machine(pairs: Int = 3): Lps.Machine = Lps.Machine("ab#", regs, bitNames, Set(), "acc", schema, schedule(pairs), slots)

  def oracle(w: String): Boolean = LpmKmp.oracle(w)
}
