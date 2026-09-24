package research

/** The window-KMP matcher of DESIGN.md §2–§4 as a layered pointer machine, on its own.
  *
  * Input (in reading order): a pattern `p` (over {a,b}), the marker `#`, then a text; the machine
  * reports, at every text letter, whether the text read so far ends with `p`.
  *
  * Table entries `Entry_i` (i = 0..m): p1 = the input cell of `p[i+1]` (or of `#` for `Entry_m`),
  * p2 = failure entry, p3 = the root of `d_i` in the shared growing unary list, p4 = `Entry_(i-1)`.
  * `d_i = i - π_i` is non-decreasing, so one growing list `G` serves every entry: backing up one
  * entry pops one unit of the cursor's `d` and pushes one unit on `G`. Entries are finalized when
  * the following pattern letter arrives (their `next` cell must exist). The cursor over the entries
  * is the current entry plus a two-stack queue of the entries to its right; the completed table is
  * reversed once into a stack for the matching phase.
  */
object LpmKmp {
  import Lpm.*
  import Lpm.E.*
  import Lpm.C.*

  val HASH = 2      // input tags: a = 0, b = 1, # = 2, separator = 3
  val QC = 4        // queue cell: p1 = input cell (input queue) or p3 = entry (entry queue); p2 = below
  val UNARY = 5     // p2 = below
  val ENTRY = 6     // p1 = next letter cell, p2 = fail, p3 = gap root, p4 = previous entry
  val ENTRY0 = 7    // the entry of the empty prefix
  val END = 8       // the entry of the whole pattern (its next cell is the marker)

  val phaseBits = 3
  val subBits = 2
  val BUILD = 0; val BACK = 1; val REV = 2; val MATCH = 3; val MBACK = 4; val DEAD = 5

  val machRegs: Vector[String] = Vector("pendF", "pendB", "pendN", "ent", "cur", "front", "rear", "gap", "gw", "pFail", "pRoot", "walk")
  val inpRegs: Vector[String] = Vector("c")
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

  def machine: Machine = {
    val p = new Prog(phaseBits, subBits)
    val active = all(not(First), not(B("wait")))
    def at(k: Int, s: Int = -1): C = if (s < 0) { And(active, phaseIs(k)) } else { all(active, phaseIs(k), subIs(s)) }
    val queueEmpty = all(IsNull(r("pendF")), IsNull(r("pendB")), IsNull(r("pendN")), not(B("rev")))

    // ---- tick prologue: queue the new letter (separators are not used) --------------------------
    p.when(And(First, not(B("rev")))) { u => u.set("pendB", u.alloc(QC, In, r("pendB"), Null, Null)); u.bit("wait", False); u.bit("acc", False) }
    p.when(And(First, B("rev"))) { u => u.set("pendN", u.alloc(QC, In, r("pendN"), Null, Null)); u.bit("wait", False); u.bit("acc", False) }

    /** Consume the next input cell under guard `g`; the body may not allocate. */
    def consume(g: C)(body: (Prog#Upd, E) => Unit): Unit = {
      p.when(all(g, not(B("rev")), not(IsNull(r("pendF"))))) { u =>
        val c = nextOf(r("pendF"))
        u.set("c", c); u.set("pendF", below(r("pendF")))
        body(u, c)
      }
      p.when(all(g, Or(B("rev"), And(IsNull(r("pendF")), not(IsNull(r("pendB"))))))) { u =>
        val last = IsNull(below(r("pendB")))
        u.set("pendF", u.alloc(QC, nextOf(r("pendB")), r("pendF"), Null, Null))
        u.set("pendB", Ite(last, r("pendN"), below(r("pendB"))))
        u.set("pendN", Ite(last, Null, r("pendN")))
        u.bit("rev", not(last))
      }
      p.when(all(g, not(B("rev")), IsNull(r("pendF")), IsNull(r("pendB")))) { u => u.bit("wait", True) }
    }

    // ---- BUILD, sub 0: take the next pattern letter (or the marker) ------------------------------
    // The first letter: Entry_0 (next = it), π_1 = 0, d_1 = 1.
    consume(all(at(BUILD, 0), not(B("started")))) { (u, c) =>
      u.bit("started", True)
      for (i <- 0 until phaseBits) { u.bit(s"p$i", Ite2(TagIs(c, HASH), bitOf(DEAD, i), bitOf(BUILD, i))) }
      u.sub(1)
    }
    p.when(all(at(BUILD, 1), IsNull(r("ent")))) { u =>
      u.set("ent", u.alloc(ENTRY0, r("c"), Null, Null, Null)); u.set("cur", Made); u.sub(2)
    }
    p.when(all(at(BUILD, 2), IsNull(r("gap")))) { u =>
      u.set("gap", u.alloc(UNARY, Null, Null, Null, Null)); u.set("pFail", r("cur")); u.set("pRoot", Made); u.sub(0)
    }
    // Later letters: finalize Entry_i (next = the letter), then the KMP step against the cursor.
    consume(all(at(BUILD, 0), B("started"))) { (u, _) => u.sub(1) }
    p.when(all(at(BUILD, 1), not(IsNull(r("ent"))), not(TagIs(r("c"), HASH)))) { u =>
      u.set("ent", u.alloc(ENTRY, r("c"), r("pFail"), r("pRoot"), r("ent"))); u.sub(2)
    }
    p.when(all(at(BUILD, 1), not(IsNull(r("ent"))), TagIs(r("c"), HASH))) { u =>
      u.set("ent", u.alloc(END, r("c"), r("pFail"), r("pRoot"), r("ent"))); u.set("walk", Made); u.set("front", Null); u.phase(REV)
    }
    p.when(all(at(BUILD, 2), not(IsNull(r("gap"))))) { u => u.set("rear", u.alloc(QC, Null, r("rear"), r("ent"), Null)); u.sub(3) }
    // sub 3: the KMP step: compare the letter with cur.next
    val sameB = SameTag(r("c"), nextOf(r("cur")))
    // match: the cursor moves right (front / rear queue); the new entry's fail is the new cursor, root = gap
    p.when(all(at(BUILD, 3), sameB, not(B("erev")), not(IsNull(r("front"))))) { u =>
      u.set("cur", Fld(r("front"), 3)); u.set("front", below(r("front"))); u.set("pFail", Fld(r("front"), 3)); u.set("pRoot", r("gap")); u.sub(0)
    }
    p.when(all(at(BUILD, 3), sameB, Or(B("erev"), And(IsNull(r("front")), not(IsNull(r("rear"))))))) { u =>
      val last = IsNull(below(r("rear")))
      u.set("front", u.alloc(QC, Null, r("front"), Fld(r("rear"), 3), Null)); u.set("rear", below(r("rear"))); u.bit("erev", not(last))
    }
    // mismatch at Entry_0: π = 0, one more gap unit
    p.when(all(at(BUILD, 3), not(sameB), TagIs(r("cur"), ENTRY0))) { u =>
      u.set("gap", u.alloc(UNARY, Null, r("gap"), Null, Null)); u.set("pFail", r("cur")); u.set("pRoot", Made); u.sub(0)
    }
    // mismatch elsewhere: back up d_cur entries
    p.when(all(at(BUILD, 3), not(sameB), not(TagIs(r("cur"), ENTRY0)))) { u => u.set("gw", Fld(r("cur"), 3)); u.phase(BACK) }
    // BACK sub 0: push the current entry on the front, step left; sub 1: one gap unit
    p.when(at(BACK, 0)) { u => u.set("front", u.alloc(QC, Null, r("front"), r("cur"), Null)); u.set("cur", Fld(r("cur"), 4)); u.sub(1) }
    p.when(at(BACK, 1)) { u =>
      u.set("gap", u.alloc(UNARY, Null, r("gap"), Null, Null)); u.set("gw", below(r("gw")))
      // continue backing up while units remain; otherwise compare again
      for (i <- 0 until phaseBits) { u.bit(s"p$i", Ite2(IsNull(below(r("gw"))), bitOf(BUILD, i), bitOf(BACK, i))) }
      for (i <- 0 until subBits) { u.bit(s"s$i", Ite2(IsNull(below(r("gw"))), bitOf(3, i), bitOf(0, i))) }
    }

    // ---- REV: reverse the archive into a stack whose top is Entry_0 ------------------------------
    p.when(And(at(REV), not(IsNull(r("walk"))))) { u => u.set("front", u.alloc(QC, Null, r("front"), r("walk"), Null)); u.set("walk", Fld(r("walk"), 4)) }
    p.when(And(at(REV), IsNull(r("walk")))) { u => u.set("cur", Fld(r("front"), 3)); u.set("front", below(r("front"))); u.phase(MATCH) }

    // ---- MATCH: text letters against the table --------------------------------------------------
    consume(at(MATCH, 0)) { (u, _) => u.sub(1) }
    val sameM = SameTag(r("c"), nextOf(r("cur")))
    p.when(all(at(MATCH, 1), TagIs(r("c"), HASH))) { u => u.phase(DEAD) }
    p.when(all(at(MATCH, 1), not(TagIs(r("c"), HASH)), sameM)) { u =>
      val nxt = Fld(r("front"), 3)
      u.set("cur", nxt); u.set("front", below(r("front")))
      u.bit("acc", Or(B("acc"), And(TagIs(nxt, END), queueEmpty)))
      // a full match: back up as for a mismatch at the end entry; otherwise the next letter
      for (i <- 0 until phaseBits) { u.bit(s"p$i", Ite2(TagIs(nxt, END), bitOf(MBACK, i), bitOf(MATCH, i))) }
      u.set("gw", Fld(nxt, 3)); u.sub(0)
    }
    p.when(all(at(MATCH, 1), not(TagIs(r("c"), HASH)), not(sameM), TagIs(r("cur"), ENTRY0))) { u => u.sub(0) }
    p.when(all(at(MATCH, 1), not(TagIs(r("c"), HASH)), not(sameM), not(TagIs(r("cur"), ENTRY0)))) { u => u.set("gw", Fld(r("cur"), 3)); u.bit("again", True); u.phase(MBACK) }
    // MBACK: one entry left per unit; afterwards compare again (mismatch) or take the next letter (full match)
    p.when(at(MBACK)) { u =>
      u.set("front", u.alloc(QC, Null, r("front"), r("cur"), Null)); u.set("cur", Fld(r("cur"), 4)); u.set("gw", below(r("gw")))
      val done = IsNull(below(r("gw")))
      for (i <- 0 until phaseBits) { u.bit(s"p$i", Ite2(done, bitOf(MATCH, i), bitOf(MBACK, i))) }
      for (i <- 0 until subBits) { u.bit(s"s$i", Ite2(And(done, B("again")), bitOf(1, i), bitOf(0, i))) }
      u.bit("again", Ite2(done, False, B("again")))
    }
    p.when(at(DEAD)) { u => u.bit("wait", True) }

    val step = p.build(inpRegs ++ machRegs, bitNames)
    Machine("ab#", inpRegs.map(_ -> Inp) ++ machRegs.map(_ -> Mach), bitNames, Set(), "acc", step)
  }

  /** Reading order = the reversed word: pattern, marker, text. Accept iff the text ends with the pattern. */
  def oracle(w: String): Boolean = {
    val rd = w.reverse
    val k = rd.indexOf('#')
    k >= 1 && !rd.substring(k + 1).contains('#') && rd.substring(k + 1).endsWith(rd.substring(0, k))
  }
}
