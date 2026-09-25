package research

/** PAL by overlapping anchors (plan §4): `w` is a palindrome iff its suffix of length h is the reverse of
  * its prefix of length h for some ceil(n/2) <= h <= n, so two matcher banks whose patterns are the reversed
  * prefixes of lengths W, 3W/2, 2W, 3W, ... (family A: h = W, answers [3W/2, 2W); family B: h = 3W/2,
  * answers [2W, 3W)) decide every length from 3 W0/2 on with no flags worker at all.
  *
  * Reading order: tick n reads rd[n-1]. A bank born at tick h reads its pattern rd[h-1] .. rd[0] backwards
  * from the birth cell (the BUILD source walk) and, during the first k = 2h - n0 of those loads, also
  * prepends the letter cell to `pendF`, so that the FIFO starts with rd[t0:h] in forward order (t0 = n0 - h)
  * before the letters arriving after birth; the matcher's text is therefore rd[t0:n], of length exactly h at
  * the first answering tick n0. `k` is counted by a unary countdown over a saved dyadic birth pointer:
  * family A gets the cell of tick W/2 (the half clock value at birth), family B the cell of tick W.
  *
  * Controller clocks (one slot `CT`, all reads are old values): `pw`/`nb` are LpsPal.ct1's dyadic clock
  * (dy at ticks 2, 4, 8, ...), `clk` is armed with the old `pw` (depth W/2) at a dyadic birth and runs out
  * exactly at 3W/2 (bB); `clk` is null on [3W/2, 2W), where family A answers, and non-null on [2W, 3W).
  * With `minAnchor` = W0 > 2 the dyadic births below W0 are disabled (the lengths below 3 W0/2 are then for
  * the grammar's bounded fallback; the machine answers false there).
  */
object OverlapPal {
  import Lpm.{E, C, Prog, Step}
  import Lpm.E.*
  import Lpm.C.*
  import Lpm.TE.Const
  import Lps.*
  import LpsPal.{Mod, Tags, mTags}
  import LpsPal2.{Kmp2, BUILD, MATCH, MBACK}

  val IDLE = 5
  private def not(c: C): C = Not(c)
  private def below(e: E): E = Fld(e, 2)
  private def prevIn(e: E): E = Fld(Fld(e, 1), 1)
  private def bitOf(k: Int, i: Int): C = if (((k >> i) & 1) == 1) { True } else { False }
  private def Ite2(g: C, a: C, b: C): C = Or(And(g, a), And(Not(g), b))
  private def select(cases: Seq[(C, C)], default: C): C = cases.foldRight(default) { case ((g, v), acc) => Ite2(g, v, acc) }
  private def smIs(k: Int): C = And(if ((k & 1) == 1) { Bit("sm0") } else { not(Bit("sm0")) }, if ((k & 2) == 2) { Bit("sm1") } else { not(Bit("sm1")) })

  val ctlRegs: Vector[(String, Set[K])] = Vector("pw" -> Set(KInp), "nb" -> Set(KInp), "clk" -> Set(KInp))
  /** dj_1 .. dj_{log2 minAnchor - 1}: the dyadic-birth counter that enables the births from W0 on */
  def djBits(minAnchor: Int): Vector[String] = (1 until Integer.numberOfTrailingZeros(minAnchor)).map(i => s"dj_$i").toVector
  def ctlBits(minAnchor: Int): Vector[String] = Vector("sm0", "sm1", "first", "b0", "b1", "out") ++ djBits(minAnchor)

  def ct(minAnchor: Int): Prog = {
    val p = new Prog(0, 0, "", exclusive = true)
    val dj = djBits(minAnchor)
    val dy = And(not(smIs(0)), IsNull(prevIn(Reg("nb"))))
    val en: C = if (dj.isEmpty) { True } else { Bit(dj.last) }
    val bA = And(dy, en)
    val bB = And(not(IsNull(Reg("clk"))), IsNull(prevIn(Reg("clk"))))
    p.when(True) { u =>
      for (i <- 0 until 2) { u.bit(s"sm$i", select((0 to 2).map(k => (smIs(k), bitOf(k + 1, i))), Bit(s"sm$i"))) }
      u.bit("first", Ite2(smIs(0), TagIs(In, 1), Bit("first")))
      for ((b, i) <- dj.zipWithIndex) { u.bit(b, Or(Bit(b), if (i == 0) { dy } else { And(Bit(dj(i - 1)), dy) })) }
      u.set("pw", Ite(Or(smIs(0), dy), In, Reg("pw")))
      u.set("nb", Ite(Or(smIs(0), dy), In, prevIn(Reg("nb"))))
      u.set("clk", Ite(bA, Reg("pw"), prevIn(Reg("clk"))))
      u.bit("b0", bA)
      u.bit("b1", bB)
    }
    p
  }

  /** bank 0 = family A (h = W), bank 1 = family B (h = 3W/2) */
  final class Bank(val s: Int) {
    val M = new Mod(s"m${s}_", 3, 0, True)
    val T: Tags = mTags(s)
    import M.*
    import T.IQC
    val queueEmpty: C = all(IsNull(r("pendF")), IsNull(r("pendB")), IsNull(r("pendN")), not(B("rev")))
    val core = new Kmp2(M, T, queueEmpty, keepFull = false)
    val regs: Vector[(String, Set[K])] = core.regs ++ Vector(n("src") -> Set(KInp), n("rc") -> Set(KInp), n("pendF") -> Set(KTag(IQC)), n("pendB") -> Set(KTag(IQC)), n("pendN") -> Set(KTag(IQC)))
    val bits: Vector[String] = core.bits ++ M.ctlBits ++ Vector(n("rev"))
    val notIdle: C = not(phaseIs(IDLE))
    /** birth: reset, the birth cell is the pattern source, the replay countdown is the saved dyadic pointer; then the
      * letters after birth join the FIFO — directly at the front only in MATCH/MBACK with an empty queue, otherwise
      * at the back (the front is reserved for the replay during BUILD) */
    def in: Prog = {
      val p = prog()
      p.when(Bit(s"b$s")) { u =>
        core.reset(u); u.set(n("src"), In); u.set(n("rc"), if (s == 0) { Reg("clk") } else { Reg("pw") })
        u.set(n("pendF"), Null); u.set(n("pendB"), Null); u.set(n("pendN"), Null); u.bit(n("rev"), False)
      }
      p.when(all(queueEmpty, Or(phaseIs(MATCH), phaseIs(MBACK)))) { u => u.set(n("pendF"), u.alloc(IQC, In, Null, Null, Null)); u.bit(n("acc"), False) }
      p.when(all(notIdle, not(B("rev")))) { u => u.set(n("pendB"), u.alloc(IQC, In, r("pendB"), Null, Null)); u.bit(n("acc"), False) }
      p.when(notIdle) { u => u.set(n("pendN"), u.alloc(IQC, In, r("pendN"), Null, Null)); u.bit(n("acc"), False) }
      p
    }
    /** the pattern from the source walk (the first k loads also prepend the letter cell to pendF: the replay);
      * the text from the FIFO (pendF first, then pendB reversed one cell per step) */
    def load(p: Prog): Unit = {
      val iq: E = p.site(Const(IQC), Ite(at(BUILD), r("src"), Fld(r("pendB"), 1)), r("pendF"), Null, Null)
      val idleB = And(at(BUILD), not(B("have")))
      val idleM = And(at(MATCH), not(B("have")))
      p.when(And(idleB, not(IsNull(r("src"))))) { u =>
        u.set(n("c"), r("src")); u.set(n("src"), prevIn(r("src"))); u.bit(n("have"), True)
        u.set(n("pendF"), Ite(IsNull(r("rc")), r("pendF"), iq)); u.set(n("rc"), prevIn(r("rc")))
      }
      p.when(And(idleB, not(B("eop")))) { u => u.bit(n("eop"), True); u.bit(n("have"), True) }
      p.when(all(idleM, not(B("rev")), not(IsNull(r("pendF"))))) { u => u.set(n("c"), Fld(r("pendF"), 1)); u.set(n("pendF"), below(r("pendF"))); u.bit(n("have"), True) }
      p.when(all(idleM, Or(B("rev"), And(IsNull(r("pendF")), not(IsNull(r("pendB"))))))) { u =>
        val last = IsNull(below(r("pendB")))
        u.set(n("pendF"), iq)
        u.set(n("pendB"), Ite(last, r("pendN"), below(r("pendB"))))
        u.set(n("pendN"), Ite(last, Null, r("pendN")))
        u.bit(n("rev"), not(last))
      }
    }
    def slots(allRegs: Vector[String], allBits: Vector[String]): Map[String, Step] =
      Map(s"M${s}IN" -> in.build(allRegs, allBits), s"M${s}U" -> core.universal(load, (_, _) => ()).build(allRegs, allBits))
    def schedule(k: Int): Vector[String] = Vector(s"M${s}IN") ++ Vector.fill(k)(s"M${s}U")
  }

  /** out: the short words by the letter counter, then the answering bank (family A while clk is null) */
  def out: Prog = {
    val p = new Prog(0, 0, "", exclusive = true)
    val short = Or(smIs(1), And(smIs(2), Ite2(TagIs(In, 1), Bit("first"), not(Bit("first")))))
    p.when(True) { u => u.bit("out", Ite2(smIs(3), Ite2(IsNull(Reg("clk")), Bit("m0_acc"), Bit("m1_acc")), short)) }
    p
  }

  def machine(aSteps: Int, bSteps: Int, minAnchor: Int = 2): Lps.Machine = {
    require(aSteps >= 1 && bSteps >= 1 && minAnchor >= 2 && (minAnchor & (minAnchor - 1)) == 0, "rates >= 1, minAnchor a power of two >= 2")
    val banks = Vector(new Bank(0), new Bank(1))
    val regs = ctlRegs ++ banks.flatMap(_.regs)
    val bits = ctlBits(minAnchor) ++ banks.flatMap(_.bits)
    val names = regs.map(_._1)
    val slots = Map("CT" -> ct(minAnchor).build(names, bits), "OUT" -> out.build(names, bits)) ++ banks.flatMap(_.slots(names, bits))
    val schedule = Vector("CT") ++ banks(0).schedule(aSteps) ++ banks(1).schedule(bSteps) ++ Vector("OUT")
    val schema = mTags.map(_.schema).reduce(_ ++ _)
    Lps.Machine("ab", regs, bits, Set("out", "m0_p0", "m0_p2", "m1_p0", "m1_p2"), "out", schema, schedule, slots)
  }

  /** the anchors of the schedule (h values) up to `lim` */
  def anchors(minAnchor: Int, lim: Int): Vector[Int] = { var w = minAnchor; val b = Vector.newBuilder[Int]; while (w <= lim) { b += w; b += 3 * w / 2; w *= 2 }; b.result().filter(_ <= lim).sorted }
  def firstAnswer(minAnchor: Int): Int = 3 * minAnchor / 2
  /** the outputs the machine must give: the oracle below 3 (letter counter) and from 3 W0/2 on, false in between */
  def expectedOutputs(minAnchor: Int, w: String): Vector[Boolean] = {
    val rd = w.reverse
    (1 to w.length).map(t => if (t >= 3 && t < firstAnswer(minAnchor)) { false } else { LpsPal.isPal(rd.take(t)) }).toVector
  }
}
