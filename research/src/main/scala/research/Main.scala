package research

/** `run sanity` checks the harness; `run rttm <anbn|marked> [maxLen]` compiles a machine and checks it. */
object Main {
  /** the PAL machine of a command family: `lps-pal2-*` → LpsPal2, `lps-pal3-*` → LpsPal3 */
  def palMachine(cmd: String, m: Int, f: Int): Lps.Machine =
    if (cmd.contains("role")) { RolePal.machine(m, f, 2) } else if (cmd.contains("gap")) { OverlapPal.machine(m, f, 2, OverlapPal.GapCore) } else if (cmd.contains("overlap")) { OverlapPal.machine(m, f, 2) } else if (cmd.contains("pal3")) { LpsPal3.machine(m, f) } else { LpsPal2.machine(m, f) }
  def main(args: Array[String]): Unit = args.toList match {
    case "sanity" :: _ =>
      val anbn = Grammar.parse("S = \"a\" S \"b\" / \"\";")
      println("anbn grammar: " + Exhaust.words(10).forall(w => anbn.accepts(w) == (w.length % 2 == 0 && w == "a" * (w.length / 2) + "b" * (w.length / 2))))
      val marked = Grammar.parse("S = \"a\" S \"a\" / \"b\" S \"b\" / \"#\";")
      println("marked palindromes: " + Exhaust.words(8, "ab#").forall(w => marked.accepts(w) == (w.count(_ == '#') == 1 && Exhaust.isPal(w))))
      val naive = Grammar.parse("S = P !.; P = \"a\" P \"a\" / \"b\" P \"b\" / \"a\" / \"b\" / \"\";")
      println("naive palindrome grammar, size " + naive.size)
      Exhaust.checkLanguage(naive, 8)
      val parity = Grammar.parse("Even = !. / . Odd; Odd = . Even;")
      println("parity: " + Exhaust.words(9).forall(w => parity.accepts(w, "Even") == (w.length % 2 == 0)))
    case "rttm" :: name :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(10)
      val m = name match {
        case "anbn" => Rttm.anbn
        case "marked" => Rttm.markedPal
        case other => throw new IllegalArgumentException(s"unknown machine $other")
      }
      val g = Rttm2Peg.compile(m)
      val (rules, nodes) = g.size
      println(s"machine $name: states=${m.states.size} tapes=${m.tapes} rules=${m.rules.size}; PEG rules=$rules nodes=$nodes")
      Rttm2Peg.check(m, maxLen)
      if (rest.contains("top")) {
        for ((n, e) <- g.rules.sortBy { case (_, e) => -Expr.size(e) }.take(12)) { println(s"  BIG ${Expr.size(e)} $n = ${Expr.render(e).take(400)}") }
      }
      if (rest.contains("print")) { println(g.render) }
    case "slisenko" :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(10)
      Slisenko.check(maxLen)
    case "psm" :: rest =>
      val budget = rest.headOption.map(_.toInt).getOrElse(8)
      val prog = Psm.markedPal
      def oracle(w: String): Boolean = w.count(_ == '#') == 1 && Exhaust.isPal(w)
      val bad = Exhaust.words(8, "ab#").find { w =>
        val t = Psm.run(prog, w, budget)
        (w.nonEmpty && t.outputs.last) != oracle(w)
      }
      println(bad.map(w => s"psm markedPal mismatch on '$w'").getOrElse("ok: psm markedPal agrees on all words of length <= 8"))
    case "slisenko-rt" :: rest =>
      SlisenkoRT.report(rest.headOption.map(_.toInt).getOrElse(10))
    case "slisenko-rt-one" :: w :: rest =>
      val speed = rest.headOption.map(_.toInt).getOrElse(1 << 20)
      println(SlisenkoRT.run(w, speed, verbose = rest.contains("v")).getOrElse(s"ok at speed $speed; min speed = ${SlisenkoRT.minSpeed(w)}"))
    case "slisenko-stress" :: rest =>
      SlisenkoRT.stress(rest.headOption.map(_.toInt).getOrElse(2000), rest.drop(1).headOption.map(_.toInt).getOrElse(3))
    case "lpm-slisenko-trace" :: w :: rest =>
      val budget = rest.headOption.map(_.toInt).getOrElse(400)
      val events = !rest.contains("full")
      val m = LpmSlisenko.machine
      val pos = new java.util.IdentityHashMap[Lpm.RCell, Int]()
      def pt(c: Lpm.RCell): String = if (c == null) { "-" } else if (pos.containsKey(c)) { pos.get(c).toString } else { s"${c.tag}@${c.layer}" }
      def depth(c: Lpm.RCell): Int = { var d = 0; var x = c; while (x != null && x.tag != LpmSlisenko.MARK) { d += 1; x = x.p2 }; d }
      val names = Vector("INIT", "SCAN", "SCAN_NEW", "FINDY0", "FINDY_MK", "FINDY1", "FINDY2", "FINDY3", "KMP", "KMP_B", "KMP_C", "KMP_T",
        "KMP_REW0", "KMP_REW1", "KMP_REW", "KMP_REW_R", "CHAIN", "TOY_WALK", "TOY_H", "TOY_CMP", "PROBE_SETUP", "PROBE", "PROBE_FAIL",
        "LAT_U2H", "LATL_CMP", "LATL_MIR", "LATL", "LATL_SW", "LATR_ENTRY", "LATR_BCNT", "LATR", "LATR_SW", "LATDONE", "LATDONE_SYM", "LATDONE_NSYM", "POINT_Y")
      val interesting = Set("SCAN", "SCAN_NEW", "FINDY0", "CHAIN", "TOY_WALK", "PROBE", "PROBE_FAIL", "LAT_U2H", "LATL", "LATR", "LATDONE_SYM", "LATDONE_NSYM", "POINT_Y")
      val ref = new SlisenkoRT.Machine(w.reverse)
      for (t <- 1 to w.length) { ref.tick = t; ref.F = 2 * t; var go = true; while (go) { go = ref.step() } }
      println("reference f-values (reversed word): " + ref.trace.mkString(" "))
      var st = Lpm.State(Map.empty, m.initTrue)
      var prevSep: Lpm.RCell = null
      var lastPh = -1
      for (i <- (w.length - 1) to 0 by -1) {
        val k = w.length - i
        val in = new Lpm.RCell(m.alphabet.indexOf(w.charAt(i)), prevSep, null, null, null, -1)
        val sep = new Lpm.RCell(m.alphabet.length, in, null, null, null, -2)
        pos.put(in, 2 * k - 1); pos.put(sep, 2 * k)
        var u = 0
        var go = true
        while (go && u < budget) {
          val next = Lpm.unit(m, st, in, sep, u == 0, u + 1)
          val same = next.bits == st.bits && m.regs.forall { case (r, _) => next.regs(r) eq st.regs.getOrElse(r, null) }
          u += 1
          st = next
          val ph = (0 until LpmSlisenko.phaseBits).map(b => if (st.bits(s"p$b")) { 1 << b } else { 0 }).sum
          val sb = (0 until LpmSlisenko.subBits).map(b => if (st.bits(s"s$b")) { 1 << b } else { 0 }).sum
          val flags = st.bits.filterNot(b => b.startsWith("p") || b.startsWith("s")).toVector.sorted.mkString(",")
          def rg(n: String) = st.regs.getOrElse(n, null)
          val summary = f"x=${pt(rg("hx"))} y=${pt(rg("hy"))} hl=${pt(rg("hl"))} sl=${pt(rg("sl"))} lastR=${pt(rg("lastR"))} rCur=${pt(rg("rCur"))} kEnd=${pt(rg("kEnd"))} |uR|=${depth(rg("uR"))} |hPtr|=${depth(rg("hPtr"))} |upL|=${depth(rg("upL"))} |upR|=${depth(rg("upR"))} |cnt2|=${depth(rg("cnt2"))} |spanH|=${depth(rg("spanH"))}"
          if (!events) { println(f"t$k u$u%3d ${names(ph)}/$sb $flags | $summary") }
          else if (ph != lastPh && interesting(names(ph))) { println(f"t$k u$u%3d -> ${names(ph)}%-12s $flags | $summary") }
          lastPh = ph
          if (same && u > 1) { go = false }
        }
        prevSep = sep
      }
    case "lps-kmp-trace" :: w :: rest =>
      val pairs = rest.headOption.map(_.toInt).getOrElse(3)
      val m = LpsKmp.machine(pairs)
      val names = m.regs.map(_._1)
      def show(c: Lpm.RCell): String = if (c == null) { "-" } else { s"${c.tag}@${c.layer}" }
      var st = Lpm.State(Map.empty, m.initTrue)
      var prevSep: Lpm.RCell = null
      for (i <- (w.length - 1) to 0 by -1) {
        val in = new Lpm.RCell(m.alphabet.indexOf(w.charAt(i)), prevSep, null, null, null, -1)
        val sep = new Lpm.RCell(m.alphabet.length, in, null, null, null, -2)
        println(s"== tick ${w.length - i}: '${w.charAt(i)}'")
        for (t <- 1 to m.budget) {
          st = Lpm.unitStep(m.slotAt(t), names, m.bits, st, in, sep, t == 1, t)
          val ph = (0 until LpsKmp.phaseBits).map(k => if (st.bits(s"p$k")) { 1 << k } else { 0 }).sum
          val sb = (0 until LpsKmp.subBits).map(k => if (st.bits(s"s$k")) { 1 << k } else { 0 }).sum
          val flags = st.bits.filterNot(b => b.startsWith("p") || b.startsWith("s")).toVector.sorted.mkString(",")
          val regs = names.map(r => s"$r=${show(st.regs.getOrElse(r, null))}").mkString(" ")
          println(f"  u$t%2d ${m.schedule(t - 1)}%-5s ph=$ph/$sb $flags | $regs")
        }
        prevSep = sep
      }
    case "lps-kmp-debug" :: w :: rest =>
      // per layer: the PEG's view at position 0 (last tick) next to the interpreter's state
      val pairs = rest.headOption.map(_.toInt).getOrElse(2)
      val m = LpsKmp.machine(pairs)
      val g = Lps.compile(m)
      val mt = g.matcher(w)
      val names = m.regs.map(_._1)
      var st = Lpm.State(Map.empty, m.initTrue)
      var prevSep: Lpm.RCell = null
      val states = Vector.newBuilder[Vector[Lpm.State]]
      for (i <- (w.length - 1) to 0 by -1) {
        val in = new Lpm.RCell(m.alphabet.indexOf(w.charAt(i)), prevSep, null, null, null, -1)
        val sep = new Lpm.RCell(m.alphabet.length, in, null, null, null, -2)
        val perLayer = Vector.newBuilder[Lpm.State]
        for (t <- 1 to m.budget) { st = Lpm.unitStep(m.slotAt(t), names, m.bits, st, in, sep, t == 1, t); perLayer += st }
        states += perLayer.result()
        prevSep = sep
      }
      val all = states.result()
      def bitsAt(t: Int, pos: Int): String = m.bits.map { b => val n = if (t == 0) { s"${b}_0" } else { g.rules.find(_._1 == s"${b}_$t").map(_ => s"${b}_$t").getOrElse("") }; if (n.isEmpty) { "" } else { s"$b=${if (mt.rule(n, pos).isDefined) 1 else 0}" } }.filter(_.nonEmpty).mkString(" ")
      def regsAt(t: Int, pos: Int): String = names.map { r => val n = s"${r}_$t"; if (g.rules.exists(_._1 == n)) { s"$r=${mt.rule(n, pos).map(e => (e - pos).toString).getOrElse("null")}" } else { "" } }.filter(_.nonEmpty).mkString(" ")
      for (pos <- (w.length - 1) to 0 by -1) {
        val tick = w.length - pos
        println(s"== position $pos (tick $tick, letter '${w.charAt(pos)}')")
        for (t <- 1 to m.budget) {
          val is = all(tick - 1)(t - 1)
          val ibits = m.bits.filter(is.bits).mkString(",")
          println(f"  L$t%2d ${m.schedule(t - 1)}%-5s PEG: ${bitsAt(t, pos)} | ${regsAt(t, pos)}")
          println(f"      interp bits: $ibits")
        }
      }
    case "lps-pal-trace" :: w :: pat :: rest =>
      val mg = rest.headOption.map(_.toInt).getOrElse(2)
      val fg = rest.drop(1).headOption.map(_.toInt).getOrElse(3)
      val re = pat.r
      Lps.trace(LpsPal.machine(mg, fg), w) { (tick, st) =>
        val bits = st.bits.toVector.sorted.filter(b => re.findFirstIn(b).isDefined).mkString(" ")
        val regs = st.regs.toVector.sortBy(_._1).filter { case (n, c) => c != null && re.findFirstIn(n).isDefined }.map { case (n, c) => s"$n=t${c.tag}@${c.layer}" }.mkString(" ")
        println(s"tick $tick '${w.charAt(w.length - tick)}': $bits | $regs")
      }
    case "lps-pal-rules" :: w :: pat :: rest =>
      val mg = rest.headOption.map(_.toInt).getOrElse(1)
      val fg = rest.drop(1).headOption.map(_.toInt).getOrElse(1)
      val g = Lps.compile(LpsPal.machine(mg, fg))
      val mt = g.matcher(w)
      val re = pat.r
      for ((n, e) <- g.rules if re.findFirstIn(n).isDefined) {
        val vals = (0 to w.length).map(pos => mt.rule(n, pos).map(x => (x - pos).toString).getOrElse("-")).mkString(" ")
        println(s"$n = ${Expr.render(e).take(300)}   @[$vals]")
      }
    case "lps-pal-peg" :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(6)
      val mg = rest.drop(1).headOption.map(_.toInt).getOrElse(1)
      val fg = rest.drop(2).headOption.map(_.toInt).getOrElse(1)
      val fu = rest.drop(3).headOption.map(_.toInt).getOrElse(0)
      println(Lps.checkSim(LpsPal.machine(mg, fg, fu), maxLen).getOrElse(s"ok: slot PAL PEG agrees with the interpreter on all words of length <= $maxLen (mGroups=$mg, fGroups=$fg, fusion=$fu)"))
    case cmd :: rest if cmd == "lps-pal2-sim" || cmd == "lps-pal3-sim" || cmd == "lps-overlap-sim" || cmd == "lps-gap-sim" || cmd == "lps-role-sim" =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(8)
      val ms = rest.drop(1).headOption.map(_.toInt).getOrElse(6)
      val fs = rest.drop(2).headOption.map(_.toInt).getOrElse(24)
      val m = palMachine(cmd, ms, fs)
      val it = Exhaust.words(maxLen, "ab")
      var bad: Option[String] = None
      var count = 0
      while (bad.isEmpty && it.hasNext) {
        val w = it.next()
        count += 1
        if (w.nonEmpty) {
          val run = Lps.run(m, w)
          val want = (1 to w.length).map(k => LpsPal.isPal(w.reverse.take(k)))
          if (run.outputs != want) { bad = Some(s"'$w': got ${run.outputs.map(b => if (b) "1" else "0").mkString} want ${want.map(b => if (b) "1" else "0").mkString}") }
        }
      }
      println(bad.getOrElse(s"ok: $cmd (mSteps=$ms, fSteps=$fs, budget=${m.budget}) agrees on all $count words of length <= $maxLen"))
    case cmd :: rest if cmd == "lps-pal2-rand" || cmd == "lps-pal3-rand" || cmd == "lps-overlap-rand" || cmd == "lps-gap-rand" || cmd == "lps-role-rand" =>
      val n = rest.headOption.map(_.toInt).getOrElse(200)
      val len = rest.drop(1).headOption.map(_.toInt).getOrElse(64)
      val ms = rest.drop(2).headOption.map(_.toInt).getOrElse(6)
      val fs = rest.drop(3).headOption.map(_.toInt).getOrElse(24)
      val m = palMachine(cmd, ms, fs)
      val rnd = new scala.util.Random(1)
      var bad: Option[String] = None
      var i = 0
      while (bad.isEmpty && i < n) {
        val base = Vector.fill(1 + rnd.nextInt(len))(if (rnd.nextBoolean()) 'a' else 'b').mkString
        val w = rnd.nextInt(3) match { case 0 => base; case 1 => base + base.reverse; case _ => base + base.reverse.drop(1) + (if (rnd.nextBoolean()) "a" else "b") }
        val run = Lps.run(m, w)
        val want = (1 to w.length).map(k => LpsPal.isPal(w.reverse.take(k)))
        if (run.outputs != want) { bad = Some(s"'$w': got ${run.outputs.map(b => if (b) "1" else "0").mkString} want ${want.map(b => if (b) "1" else "0").mkString}") }
        i += 1
      }
      println(bad.getOrElse(s"ok: $cmd (mSteps=$ms, fSteps=$fs) agrees on $n random words of length <= ${2 * len + 1}"))
    case cmd :: rest if cmd == "lps-pal2-size" || cmd == "lps-pal3-size" || cmd == "lps-overlap-size" || cmd == "lps-gap-size" =>
      val ms = rest.headOption.map(_.toInt).getOrElse(6)
      val fs = rest.drop(1).headOption.map(_.toInt).getOrElse(24)
      val m = palMachine(cmd, ms, fs)
      val g = Lps.compile(m)
      val (rules, symbols) = g.size
      println(s"$cmd: mSteps $ms fSteps $fs budget ${m.budget}, PEG rules=$rules symbols=$symbols")
      val byLayer = g.rules.groupBy { case (n, _) => n.split("_").lift(1).flatMap(_.toIntOption).getOrElse(-1) }
      for (t <- 0 to m.budget) {
        val rs = byLayer.getOrElse(t, Vector())
        val sz = rs.map { case (_, e) => Expr.size(e) }.sum
        println(f"  layer $t%2d ${if (t == 0) "-" else m.schedule(t - 1)}%-7s rules=${rs.size}%4d symbols=$sz%6d")
      }
      if (rest.contains("print")) { println(g.render) }
    case cmd :: rest if cmd == "lps-pal2-peg" || cmd == "lps-pal3-peg" || cmd == "lps-overlap-peg" || cmd == "lps-gap-peg" =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(6)
      val ms = rest.drop(1).headOption.map(_.toInt).getOrElse(1)
      val fs = rest.drop(2).headOption.map(_.toInt).getOrElse(1)
      println(Lps.checkSim(palMachine(cmd, ms, fs), maxLen).getOrElse(s"ok: $cmd PEG agrees with the interpreter on all words of length <= $maxLen (mSteps=$ms, fSteps=$fs)"))
    case cmd :: rest if cmd == "lps-pal2" || cmd == "lps-pal3" || cmd == "lps-overlap" =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(6)
      val ms = rest.drop(1).headOption.map(_.toInt).getOrElse(6)
      val fs = rest.drop(2).headOption.map(_.toInt).getOrElse(24)
      println(Lps.check(palMachine(cmd, ms, fs), maxLen, LpsPal.isPal).getOrElse(s"ok: $cmd interpreter and PEG agree with the oracle on all words of length <= $maxLen (mSteps=$ms, fSteps=$fs)"))
    case "lps-pal-sim" :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(8)
      val mg = rest.drop(1).headOption.map(_.toInt).getOrElse(2)
      val fg = rest.drop(2).headOption.map(_.toInt).getOrElse(3)
      val fu = rest.drop(3).headOption.map(_.toInt).getOrElse(0)
      val m = LpsPal.machine(mg, fg, fu)
      val it = Exhaust.words(maxLen, "ab")
      var bad: Option[String] = None
      var count = 0
      while (bad.isEmpty && it.hasNext) {
        val w = it.next()
        count += 1
        if (w.nonEmpty) {
          val run = Lps.run(m, w)
          val want = (1 to w.length).map(k => LpsPal.isPal(w.reverse.take(k)))  // the machine reads the word from its end
          if (run.outputs != want) { bad = Some(s"'$w': got ${run.outputs.map(b => if (b) "1" else "0").mkString} want ${want.map(b => if (b) "1" else "0").mkString}") }
        }
      }
      println(bad.getOrElse(s"ok: slot PAL (mGroups=$mg, fGroups=$fg, budget=${m.budget}) agrees on all $count words of length <= $maxLen"))
    case "lps-pal-rand" :: rest =>
      val n = rest.headOption.map(_.toInt).getOrElse(200)
      val len = rest.drop(1).headOption.map(_.toInt).getOrElse(64)
      val mg = rest.drop(2).headOption.map(_.toInt).getOrElse(2)
      val fg = rest.drop(3).headOption.map(_.toInt).getOrElse(3)
      val m = LpsPal.machine(mg, fg)
      val rnd = new scala.util.Random(1)
      var bad: Option[String] = None
      var i = 0
      while (bad.isEmpty && i < n) {
        // random words and random words with long palindromic prefixes
        val base = Vector.fill(1 + rnd.nextInt(len))(if (rnd.nextBoolean()) 'a' else 'b').mkString
        val w = rnd.nextInt(3) match { case 0 => base; case 1 => base + base.reverse; case _ => base + base.reverse.drop(1) + (if (rnd.nextBoolean()) "a" else "b") }
        val run = Lps.run(m, w)
        val want = (1 to w.length).map(k => LpsPal.isPal(w.reverse.take(k)))
        if (run.outputs != want) { bad = Some(s"'$w': got ${run.outputs.map(b => if (b) "1" else "0").mkString} want ${want.map(b => if (b) "1" else "0").mkString}") }
        i += 1
      }
      println(bad.getOrElse(s"ok: slot PAL agrees on $n random words of length <= ${2 * len + 1}"))
    case "lps-pal-size" :: rest =>
      val mg = rest.headOption.map(_.toInt).getOrElse(2)
      val fg = rest.drop(1).headOption.map(_.toInt).getOrElse(3)
      val fu = rest.drop(2).headOption.flatMap(_.toIntOption).getOrElse(0)
      val m = LpsPal.machine(mg, fg, fu)
      val g = Lps.compile(m)
      val (rules, symbols) = g.size
      println(s"lps pal: mGroups $mg fGroups $fg fusion $fu budget ${m.budget}, PEG rules=$rules symbols=$symbols")
      val byLayer = g.rules.groupBy { case (n, _) => n.split("_").lift(1).flatMap(_.toIntOption).getOrElse(-1) }
      for (t <- 0 to m.budget) {
        val rs = byLayer.getOrElse(t, Vector())
        val sz = rs.map { case (_, e) => Expr.size(e) }.sum
        println(f"  layer $t%2d ${if (t == 0) "-" else m.schedule(t - 1)}%-7s rules=${rs.size}%4d symbols=$sz%6d")
      }
      if (rest.contains("print")) { println(g.render) }
    case "lps-pal" :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(6)
      val mg = rest.drop(1).headOption.map(_.toInt).getOrElse(2)
      val fg = rest.drop(2).headOption.map(_.toInt).getOrElse(3)
      println(Lps.check(LpsPal.machine(mg, fg), maxLen, LpsPal.isPal).getOrElse(s"ok: slot PAL interpreter and PEG agree with the oracle on all words of length <= $maxLen"))
    case "lps-metrics" :: which :: rest =>
      // lps-metrics kmp2 <n> <out.json> | lps-metrics pal2 <m> <f> <out.json>
      val (m, label, extra) = which match {
        case "kmp2" => (LpsKmp2.machine(rest(0).toInt), s"kmp2 n=${rest(0)}", Seq("machine_variant" -> "LpsKmp2", "mSteps" -> rest(0), "fSteps" -> "-"))
        case "pal3" => (LpsPal3.machine(rest(0).toInt, rest(1).toInt), s"pal3 ${rest(0)}/${rest(1)}", Seq("machine_variant" -> "LpsPal3", "mSteps" -> rest(0), "fSteps" -> rest(1)))
        case "overlap" => (OverlapPal.machine(rest(0).toInt, rest(1).toInt, 2), s"overlap ${rest(0)}/${rest(1)}", Seq("machine_variant" -> "OverlapPal", "mSteps" -> rest(0), "fSteps" -> rest(1)))
        case "gap" => (OverlapPal.machine(rest(0).toInt, rest(1).toInt, 2, OverlapPal.GapCore), s"gap ${rest(0)}/${rest(1)}", Seq("machine_variant" -> "OverlapPal+GapKmp", "mSteps" -> rest(0), "fSteps" -> rest(1)))
        case "role" => (RolePal.machine(rest(0).toInt, rest(1).toInt, 2), s"role ${rest(0)}/${rest(1)}", Seq("machine_variant" -> "RolePal", "mSteps" -> rest(0), "fSteps" -> rest(1)))
        case _ => (LpsPal2.machine(rest(0).toInt, rest(1).toInt), s"pal2 ${rest(0)}/${rest(1)}", Seq("machine_variant" -> "LpsPal2", "mSteps" -> rest(0), "fSteps" -> rest(1)))
      }
      val out = rest.last
      val t0 = System.nanoTime()
      val (g, meta) = Lps.compileMeta(m)
      val gen = (System.nanoTime() - t0) / 1e9
      val mt = PegMetrics.of(g, n => meta.get(n).map(_.slot).getOrElse(PegMetrics.componentOfName(n)))
      val commit = scala.sys.process.Process(Seq("git", "rev-parse", "--short", "HEAD")).!!.trim
      val json = mt.json(extra ++ Seq("source_commit" -> commit, "compiler_variant" -> "Lps", "generation_seconds" -> f"$gen%.2f"))
      java.nio.file.Files.write(java.nio.file.Paths.get(out), json.getBytes("UTF-8"))
      println(PegMetrics.markdownHeader)
      println(mt.markdownRow(label))
    case "lps-optimize" :: which :: rest =>
      // lps-optimize kmp2 <n> <out.json> [inline] | lps-optimize pal2 <m> <f> <out.json> [inline]; metrics before/after
      val inl = rest.contains("inline")
      val args = rest.filter(_ != "inline")
      val (m, label, extra) = which match {
        case "kmp2" => (LpsKmp2.machine(args(0).toInt), s"kmp2 n=${args(0)}", Seq("machine_variant" -> "LpsKmp2", "mSteps" -> args(0), "fSteps" -> "-"))
        case "pal3" => (LpsPal3.machine(args(0).toInt, args(1).toInt), s"pal3 ${args(0)}/${args(1)}", Seq("machine_variant" -> "LpsPal3", "mSteps" -> args(0), "fSteps" -> args(1)))
        case "overlap" => (OverlapPal.machine(args(0).toInt, args(1).toInt, 2), s"overlap ${args(0)}/${args(1)}", Seq("machine_variant" -> "OverlapPal", "mSteps" -> args(0), "fSteps" -> args(1)))
        case "gap" => (OverlapPal.machine(args(0).toInt, args(1).toInt, 2, OverlapPal.GapCore), s"gap ${args(0)}/${args(1)}", Seq("machine_variant" -> "OverlapPal+GapKmp", "mSteps" -> args(0), "fSteps" -> args(1)))
        case "role" => (RolePal.machine(args(0).toInt, args(1).toInt, 2), s"role ${args(0)}/${args(1)}", Seq("machine_variant" -> "RolePal", "mSteps" -> args(0), "fSteps" -> args(1)))
        case _ => (LpsPal2.machine(args(0).toInt, args(1).toInt), s"pal2 ${args(0)}/${args(1)}", Seq("machine_variant" -> "LpsPal2", "mSteps" -> args(0), "fSteps" -> args(1)))
      }
      val out = args.last
      val (g, meta) = Lps.compileMeta(m)
      val comp = (n: String) => meta.get(n).map(_.slot).getOrElse(PegMetrics.componentOfName(n))
      val before = PegMetrics.of(g, comp)
      val t0 = System.nanoTime()
      val r = PegOptimize.compact(g, PegOptimize.Options(inlineSingleUse = inl))
      val secs = (System.nanoTime() - t0) / 1e9
      val after = PegMetrics.of(r.grammar, comp)
      val commit = scala.sys.process.Process(Seq("git", "rev-parse", "--short", "HEAD")).!!.trim
      val json = after.json(extra ++ Seq("source_commit" -> commit, "compiler_variant" -> (if (inl) { "Lps+compact+inline" } else { "Lps+compact" }),
        "N_raw" -> before.rules.toString, "rhs_nodes_raw" -> before.rhsNodes.toString, "optimize_seconds" -> f"$secs%.2f"))
      java.nio.file.Files.write(java.nio.file.Paths.get(out), json.getBytes("UTF-8"))
      println(PegMetrics.markdownHeader)
      println(before.markdownRow(label + " raw"))
      println(after.markdownRow(label + (if (inl) { " compact+inline" } else { " compact" })))
    case "lps-export" :: which :: rest =>
      // lps-export kmp2 <n> <path> [inline] | lps-export pal2 <m> <f> <path> [inline]: the compacted grammar, to a file
      val inl = rest.contains("inline")
      val args = rest.filter(_ != "inline")
      val m = which match { case "kmp2" => LpsKmp2.machine(args(0).toInt); case "pal3" => LpsPal3.machine(args(0).toInt, args(1).toInt); case "overlap" => OverlapPal.machine(args(0).toInt, args(1).toInt, 2); case "gap" => OverlapPal.machine(args(0).toInt, args(1).toInt, 2, OverlapPal.GapCore); case "role" => RolePal.machine(args(0).toInt, args(1).toInt, 2); case _ => LpsPal2.machine(args(0).toInt, args(1).toInt) }
      val r = PegOptimize.compact(Lps.compile(m), PegOptimize.Options(inlineSingleUse = inl))
      java.nio.file.Files.write(java.nio.file.Paths.get(args.last), r.grammar.render.getBytes("UTF-8"))
      val (n, sz) = r.grammar.size
      println(s"exported ${args.last}: rules=$n symbols=$sz")
    case "lps-profile" :: which :: rest =>
      // lps-profile pal3 <m> <f> <word>  |  lps-profile pal3 <m> <f> structured <maxLen>: jobs, units, deadline checks
      val m = palMachine(s"lps-$which", rest(0).toInt, rest(1).toInt)
      val words = rest(2) match { case "structured" => LpsProfile.structuredWords(rest(3).toInt); case "adversarial" => LpsProfile.adversarialWords(rest(3).toInt); case w => Vector(w) }
      var bad = 0
      var maxUnits = 0
      for (w <- words) {
        val pr = LpsProfile.profile(m, w)
        maxUnits = math.max(maxUnits, pr.maxFlagsUnitsPerTick)
        if (words.size == 1) { println(pr.table) }
        if (pr.violations.nonEmpty) { bad += 1; println(s"'${w.take(40)}${if (w.length > 40) "..." else ""}' (${w.length}): " + pr.violations.take(3).mkString("; ")) }
      }
      println(s"${words.size} words, $bad with violations, max flags units in one tick $maxUnits")
    case "lps-rules" :: which :: rest =>
      // lps-rules kmp2 <n> <w> <regex> | lps-rules pal3 <m> <f> <w> <regex>: rule values per position ("0" = success, "-" = failure)
      val (m, w, pat) = which match { case "kmp2" => (LpsKmp2.machine(rest(0).toInt), rest(1), rest(2)); case _ => (palMachine(s"lps-$which", rest(0).toInt, rest(1).toInt), rest(2), rest(3)) }
      val g = Lps.compile(m)
      val mt = g.matcher(w)
      val re = pat.r
      for ((n, e) <- g.rules if re.findFirstIn(n).isDefined) {
        val vals = (0 to w.length).map(pos => mt.rule(n, pos).map(x => (x - pos).toString).getOrElse("-")).mkString(" ")
        println(s"$n = ${Expr.render(e).take(300)}   @[$vals]")
      }
    case "lps-trace" :: which :: rest =>
      // lps-trace kmp2 <n> <w> <regex>: interpreter state after every slot (true bits and non-null registers)
      val (m, w, pat) = which match { case "kmp2" => (LpsKmp2.machine(rest(0).toInt), rest(1), rest(2)); case _ => (palMachine(s"lps-$which", rest(0).toInt, rest(1).toInt), rest(2), rest(3)) }
      val re = pat.r
      Lps.traceSlots(m, w) { (tick, slot, st) =>
        val bits = st.bits.toVector.sorted.filter(b => re.findFirstIn(b).isDefined).mkString(" ")
        val regs = st.regs.toVector.sortBy(_._1).filter { case (n, c) => c != null && re.findFirstIn(n).isDefined }.map { case (n, c) => s"$n=t${c.tag}@${c.layer}" }.mkString(" ")
        println(s"tick $tick $slot: $bits | $regs")
      }
    case "peg-cse-improve" :: path :: factorS :: maxRhsS :: kS :: thresholdS :: roundsS :: rest =>
      // peg-cse-improve <compact.peg> <factor> <maxRhs> <k> <threshold> <rounds> [out.peg]: the budgeted inliner at factor x
      // the compacted input (as lps-inline-curve), then rounds of naming common sub-expressions and inlining again (PegCse)
      val g = PegOptimize.compact(Grammar.parse(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8"))).grammar
      val base = PegMetrics.of(g, PegMetrics.componentOfName)
      val budget = (base.rhsNodes * factorS.toDouble).toLong
      val t0 = System.nanoTime()
      val (g2, trace) = PegCse.improve(g, budget, maxRhsS.toInt, kS.toInt, thresholdS.toLong, roundsS.toInt)
      val after = PegMetrics.of(g2, PegMetrics.componentOfName)
      println(s"budget $budget (x$factorS of ${base.rhsNodes}); rounds (rules, nodes): ${trace.mkString(" ")}; " +
        f"final ${after.rules} rules, ${after.rhsNodes} rhs nodes, ${after.renderedUtf8Bytes} bytes (${(System.nanoTime() - t0) / 1e9}%.0f s)")
      rest.headOption.foreach { out => java.nio.file.Files.write(java.nio.file.Paths.get(out), g2.render.getBytes("UTF-8")) }
      rest.lift(1).foreach { outJson =>
        val rendered = g2.render
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(rendered.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString
        val json = after.json(Seq("source_file" -> path, "pass" -> s"PegOptimize.compact+PegBudgetInline+PegCse (factor $factorS, k $kS, threshold $thresholdS)",
          "node_budget" -> budget.toString, "max_rhs_budget" -> maxRhsS, "N_before" -> base.rules.toString, "rhs_nodes_before" -> base.rhsNodes.toString,
          "rounds" -> trace.map { case (n, nodes) => s"$n/$nodes" }.mkString(" "), "sha256_uncompressed" -> sha))
        java.nio.file.Files.write(java.nio.file.Paths.get(outJson), json.getBytes("UTF-8"))
      }
    case "peg-cse-adaptive" :: path :: factorS :: maxRhsS :: kS :: roundsS :: rest =>
      // peg-cse-adaptive <compact.peg> <factor> <maxRhs> <k> <rounds> [out.peg out.json]: as peg-cse-improve, the threshold of each
      // round being the price of the next elimination (PegCse.improveAdaptive)
      val g = PegOptimize.compact(Grammar.parse(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8"))).grammar
      val base = PegMetrics.of(g, PegMetrics.componentOfName)
      val budget = (base.rhsNodes * factorS.toDouble).toLong
      val t0 = System.nanoTime()
      val (g2, trace) = PegCse.improveAdaptive(g, budget, maxRhsS.toInt, kS.toInt, roundsS.toInt)
      val after = PegMetrics.of(g2, PegMetrics.componentOfName)
      println(s"budget $budget (x$factorS of ${base.rhsNodes}); rounds (rules, nodes, price): ${trace.mkString(" ")}; " +
        f"final ${after.rules} rules, ${after.rhsNodes} rhs nodes, ${after.renderedUtf8Bytes} bytes (${(System.nanoTime() - t0) / 1e9}%.0f s)")
      rest.headOption.foreach { out => java.nio.file.Files.write(java.nio.file.Paths.get(out), g2.render.getBytes("UTF-8")) }
      rest.lift(1).foreach { outJson =>
        val rendered = g2.render
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(rendered.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString
        val json = after.json(Seq("source_file" -> path, "pass" -> s"PegOptimize.compact+PegBudgetInline+PegCse adaptive (factor $factorS, k $kS)",
          "node_budget" -> budget.toString, "max_rhs_budget" -> maxRhsS, "N_before" -> base.rules.toString, "rhs_nodes_before" -> base.rhsNodes.toString,
          "rounds" -> trace.map { case (n, nodes, p) => s"$n/$nodes/$p" }.mkString(" "), "sha256_uncompressed" -> sha))
        java.nio.file.Files.write(java.nio.file.Paths.get(outJson), json.getBytes("UTF-8"))
      }
    case "alloc-profile" :: kyS :: koS :: w0S :: maxS :: rest0 =>
      // alloc-profile <ky> <ko> <W0> <maxLen> [role flags] [beta=..] (STRATEGY.md §3f): per tick, the cells born in the tick
      // that outlive it (reachable from a register at its end), by tag, against the sites of the tag. A pointer addressed by
      // (tick, index within the tick) needs at least that many alternatives, so this bounds any re-addressing of the cells
      val sc = OverlapPal.schedArg(rest0)
      val ro = RolePal.Options(youngReport = !rest0.contains("noacc"), fuseIn = rest0.contains("fuse"), compact = rest0.contains("compact"),
        endAsEntry = rest0.contains("endent"), endOnce = rest0.contains("endonce"), sched = sc, unfold = rest0.contains("unfold"), mergeTmp = rest0.contains("tmp"),
        lgap = rest0.contains("lgap"), merge2 = rest0.contains("merge2"),
        merge3 = rest0.contains("merge3"), m2off = Set("U", "G", "R", "R1", "R2").filter(x => rest0.contains(s"no$x")))
      val maxLen = maxS.toInt
      val m = RolePal.machine(kyS.toInt, koS.toInt, w0S.toInt, ro)
      val words = OverlapProfile.switchWords(maxLen, sc) ++ OverlapProfile.adversarialWords(maxLen, sc).take(150) ++ OverlapProfile.everyLengthWords(maxLen)
      val tags = m.sitesOfTag.keys.toVector.sorted
      val hist = scala.collection.mutable.HashMap.empty[(Int, Int), Long].withDefaultValue(0L)
      for (w <- words) {
        Lps.trace(m, w) { (tick, st) =>
          val seen = scala.collection.mutable.HashSet.empty[Lpm.RCell]
          def visit(c: Lpm.RCell): Unit = if (c != null && c.tick == tick && c.tag > m.alphabet.length && seen.add(c)) { (1 to 4).foreach(f => visit(c.fld(f))) }
          st.regs.values.foreach(visit)
          val byTag = seen.toVector.groupBy(_.tag).view.mapValues(_.size).toMap
          for (tag <- tags) { hist((tag, byTag.getOrElse(tag, 0))) += 1 }
        }
      }
      println(s"alloc-profile ${kyS}/${koS} W0 $w0S, ${words.size} words <= $maxLen: cells born in a tick that outlive it, per tick")
      for (tag <- tags) {
        val counts = hist.collect { case ((t, k), n) if t == tag => k -> n }.toVector.sortBy(_._1)
        val ticks = counts.map(_._2).sum.toDouble
        val mean = counts.map { case (k, n) => k * n }.sum / ticks
        println(f"  tag $tag%2d: sites ${m.sitesOfTag(tag).size}%2d, max per tick ${counts.filter(_._2 > 0).map(_._1).max}%2d, mean $mean%.2f; histogram ${counts.map { case (k, n) => s"$k:$n" }.mkString(" ")}")
      }
    case "step-bigrams" :: kyS :: koS :: w0S :: family :: rest0 =>
      // step-bigrams <ky> <ko> <W0> random <count> <maxLen> | every <maxLen> [role flags] [beta=..]: the branch of every young step
      // (YU/YUF slots, from the guards on the state before it) and the pairs of consecutive busy young steps (STRATEGY.md §3e, E3)
      val sc = OverlapPal.schedArg(rest0)
      val ro = RolePal.Options(youngReport = !rest0.contains("noacc"), fuseIn = rest0.contains("fuse"), compact = rest0.contains("compact"),
        endAsEntry = rest0.contains("endent"), endOnce = rest0.contains("endonce"), sched = sc, unfold = rest0.contains("unfold"), mergeTmp = rest0.contains("tmp"),
        lgap = rest0.contains("lgap"), merge2 = rest0.contains("merge2"),
        merge3 = rest0.contains("merge3"), m2off = Set("U", "G", "R", "R1", "R2").filter(x => rest0.contains(s"no$x")))
      val nums = rest0.filter(_.forall(_.isDigit)).map(_.toInt)
      val words = family match {
        case "random" => OverlapProfile.randomWords(nums(0), nums(1))
        case _ => OverlapProfile.everyLengthWords(nums(0))
      }
      val m = RolePal.machine(kyS.toInt, koS.toInt, w0S.toInt, ro)
      val br = RolePal.youngBranches(ro)
      val uni = scala.collection.mutable.HashMap.empty[String, Long]
      val bi = scala.collection.mutable.HashMap.empty[(String, String), Long]
      for (w <- words) {
        var prev: Lpm.State = null
        var last: String = ""
        Lps.traceSlots(m, w) { (_, slot, st) =>
          if ((slot == "YU" || slot == "YUF") && prev != null) {
            val k = br.collectFirst { case (name, g) if Lpm.holds(g, prev) => name }.getOrElse("idle")
            uni(k) = uni.getOrElse(k, 0L) + 1
            if (k == "popG") {
              import Lpm.E.{Fld, Reg}
              import Lpm.C.{IsNull, And, TagIs}
              val gE = Fld(Reg("y_L"), 1)
              val empty = And(IsNull(Fld(gE, 2)), And(IsNull(Fld(gE, 3)), IsNull(Fld(gE, 4))))
              val kk = if (Lpm.holds(empty, prev)) { "+popG gE gap empty" } else { "+popG gE gap nonempty" }
              uni(kk) = uni.getOrElse(kk, 0L) + 1
              val ktag = if (Lpm.holds(TagIs(Fld(gE, 2), GapKmp.roleTagsP3.GNODE), prev)) { "+popG gE.hL is GNODE" } else { "+popG gE.hL other" }
              uni(ktag) = uni.getOrElse(ktag, 0L) + 1
            }
            if (k != "idle") { if (last.nonEmpty) { bi((last, k)) = bi.getOrElse((last, k), 0L) + 1 }; last = k }
          }
          if (slot == "CT" && (st.bits("b0") || st.bits("b1"))) { last = "" }   // a new young life
          prev = st
        }
      }
      val busy = uni.filter(k => k._1 != "idle" && !k._1.startsWith("+")).values.sum
      println(f"${words.size} words; young busy steps $busy, idle ${uni.getOrElse("idle", 0L)}")
      for ((k, c) <- uni.toVector.sortBy(-_._2)) { println(f"  $k%-15s $c%10d ${100.0 * c / math.max(busy, 1)}%6.2f%%") }
      println("pairs of consecutive busy young steps (top 25):")
      for (((a, b), c) <- bi.toVector.sortBy(-_._2).take(25)) { println(f"  $a%-15s -> $b%-15s $c%10d ${100.0 * c / math.max(busy, 1)}%6.2f%%") }
    case "peg-cse-probe" :: path :: topS :: thresholdS :: _ =>
      // peg-cse-probe <grammar.peg> <top> <threshold>: the sub-expressions whose naming as a rule saves the most nodes (PegCse)
      val g = Grammar.parse(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8"))
      println(PegCse.probe(g, topS.toInt, thresholdS.toLong))
    case "lps-inline-curve" :: path :: maxFactorS :: maxRhsS :: outMd :: rest =>
      // lps-inline-curve <in.peg> <maxFactor> <maxRhs> <out.md> [<N> <out.peg> <out.json>]: one greedy PegBudgetInline run
      // with the node budget maxFactor x the compacted input; the N reached at node factors 1 .. maxFactor; optionally the
      // first grammar of the run with fewer than N rules is rebuilt (the same run stopped at its node total) and exported
      val text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8")
      val g = PegOptimize.compact(Grammar.parse(text)).grammar
      val before = PegMetrics.of(g, PegMetrics.componentOfName)
      val maxRhs = maxRhsS.toInt
      val t0 = System.nanoTime()
      // an optional "la=<lambda>" after the other arguments: the look-ahead priority (PegBudgetInline.Lookahead)
      val prio: PegBudgetInline.Priority = rest.collectFirst { case x if x.startsWith("la=") => PegBudgetInline.Lookahead(x.drop(3).toDouble) }.getOrElse(PegBudgetInline.Nodes)
      val runOpts = PegBudgetInline.Options(nodeBudget = (before.rhsNodes * maxFactorS.toDouble).toLong, maxRhs = maxRhs, priority = prio)
      val r = PegBudgetInline.inline(g, runOpts)
      val secs = (System.nanoTime() - t0) / 1e9
      val factors = Vector(1.0, 1.25, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0, 24.0, 32.0, 48.0, 64.0).filter(_ <= maxFactorS.toDouble)
      val rows = factors.map { f =>
        val cap = (before.rhsNodes * f).toLong
        val last = r.trace.takeWhile(_._2 <= cap).lastOption.getOrElse((before.rules, before.rhsNodes, before.renderedUtf8Bytes))
        f"| x$f%.2f | ${last._1} | ${last._2} | ${last._3} |"
      }
      val firstBelow = (n: Int) => r.trace.find(_._1 < n)
      val md = s"# PegBudgetInline curve of `$path` (maxRhs $maxRhs)\n\nOne greedy run (the eliminations under a smaller node budget are a prefix of those under a larger one). " +
        s"Input: ${before.rules} rules, ${before.rhsNodes} rhs nodes, ${before.renderedUtf8Bytes} bytes. Final: ${r.grammar.rules.size} rules, ${r.nodes} nodes (${f"$secs%.0f"} s).\n\n" +
        "| node budget | N | rhs nodes | byte estimate |\n|---|---:|---:|---:|\n" + rows.mkString("\n") + "\n" +
        Vector(1000, 1100, 1200).map(n => firstBelow(n).map { case (k, nodes, bytes) => s"\nfirst N < $n: $k rules at $nodes nodes (x${f"${nodes.toDouble / before.rhsNodes}%.2f"}), byte estimate $bytes" }.getOrElse(s"\nN < $n not reached")).mkString + "\n"
      java.nio.file.Files.write(java.nio.file.Paths.get(outMd), md.getBytes("UTF-8"))
      println(md)
      rest match {
        case nS :: outPeg :: outJson :: _ =>
          val idx = r.trace.indexWhere(_._1 < nS.toInt)
          if (idx >= 0) {
            val nodes = r.trace(idx)._2
            val r2 = PegBudgetInline.inline(g, runOpts.copy(maxEliminations = idx + 1))   // the same run stopped after idx + 1 eliminations
            val after = PegMetrics.of(r2.grammar, PegMetrics.componentOfName)
            val rendered = r2.grammar.render
            java.nio.file.Files.write(java.nio.file.Paths.get(outPeg), rendered.getBytes("UTF-8"))
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(rendered.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString
            val commit = scala.sys.process.Process(Seq("git", "rev-parse", "--short", "HEAD")).!!.trim
            val json = after.json(Seq("source_file" -> path, "source_commit" -> commit, "pass" -> "PegOptimize.compact+PegBudgetInline",
              "node_budget" -> nodes.toString, "max_rhs_budget" -> maxRhs.toString, "priority" -> prio.toString, "N_before" -> before.rules.toString,
              "rhs_nodes_before" -> before.rhsNodes.toString, "bytes_before" -> before.renderedUtf8Bytes.toString, "sha256_uncompressed" -> sha))
            java.nio.file.Files.write(java.nio.file.Paths.get(outJson), json.getBytes("UTF-8"))
            println(PegMetrics.markdownHeader); println(after.markdownRow(s"inline to N < $nS")); println(s"sha256 $sha")
          }
        case _ => ()
      }
    case "lps-inline-sweep" :: path :: outDir :: rest =>
      // lps-inline-sweep <in.peg> <outDir> [preset-xfactor-rmaxRhs ...]: the Pareto table of PegBudgetInline on one grammar
      // (nodes x {1, 1.25, 1.5, 2, 3, 4} at maxRhs 4096, nodes x {3, 4} at maxRhs 16384, bytes x 1 at 4096); one json per
      // row in outDir, the table in outDir/under1000-inline-pareto.md, the grammars named in the arguments exported to out/
      val text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8")
      val g = PegOptimize.compact(Grammar.parse(text)).grammar
      val before = PegMetrics.of(g, PegMetrics.componentOfName)
      val commit = scala.sys.process.Process(Seq("git", "rev-parse", "--short", "HEAD")).!!.trim
      val configs = Vector(("nodes", 1.0, 4096), ("nodes", 1.25, 4096), ("nodes", 1.5, 4096), ("nodes", 2.0, 4096), ("nodes", 3.0, 4096),
        ("nodes", 4.0, 4096), ("nodes", 3.0, 16384), ("nodes", 4.0, 16384), ("bytes", 1.0, 4096))
      val rows = for ((preset, factor, maxRhs) <- configs) yield {
        val tag = s"$preset-x$factor-r$maxRhs"
        val opts = preset match {
          case "bytes" => PegBudgetInline.Options(nodeBudget = Long.MaxValue, byteBudget = (before.renderedUtf8Bytes * factor).toLong, maxRhs = maxRhs, priority = PegBudgetInline.Bytes)
          case _ => PegBudgetInline.Options(nodeBudget = (before.rhsNodes * factor).toLong, maxRhs = maxRhs)
        }
        val t0 = System.nanoTime()
        val r = PegBudgetInline.inline(g, opts)
        val secs = (System.nanoTime() - t0) / 1e9
        val after = PegMetrics.of(r.grammar, PegMetrics.componentOfName)
        val json = after.json(Seq("source_file" -> path, "source_commit" -> commit, "pass" -> "PegOptimize.compact+PegBudgetInline",
          "preset" -> preset, "factor" -> factor.toString, "max_rhs_budget" -> maxRhs.toString, "eliminated" -> r.eliminated.size.toString,
          "N_before" -> before.rules.toString, "rhs_nodes_before" -> before.rhsNodes.toString, "bytes_before" -> before.renderedUtf8Bytes.toString,
          "bytes_estimate" -> r.bytesEstimate.toString, "seconds" -> f"$secs%.1f"))
        java.nio.file.Files.write(java.nio.file.Paths.get(s"$outDir/under1000-inline-$tag.json"), json.getBytes("UTF-8"))
        if (rest.contains(tag)) { java.nio.file.Files.write(java.nio.file.Paths.get(s"out/under1000-inline-$tag.peg"), r.grammar.render.getBytes("UTF-8")) }
        val row = after.markdownRow(s"inline $preset x$factor maxRhs $maxRhs") + f" ${r.eliminated.size} | $secs%.0f |"
        println(row)
        row
      }
      val hs = PegMetrics.markdownHeader.split("\n")
      val md = s"# PegBudgetInline on `$path` (source commit $commit)\n\nBudgets are relative to the compacted input; every retained rule keeps its extents (PegBudgetInlineSuite).\n\n" +
        hs(0) + " eliminated | seconds |\n" + hs(1) + "---|---|\n" + before.markdownRow("compact (input)") + " 0 | 0 |\n" + rows.mkString("\n") + "\n"
      java.nio.file.Files.write(java.nio.file.Paths.get(s"$outDir/under1000-inline-pareto.md"), md.getBytes("UTF-8"))
      println(s"wrote $outDir/under1000-inline-pareto.md")
    case "lps-inline" :: path :: preset :: factorS :: maxRhsS :: outJson :: rest =>
      // lps-inline <in.peg> <nodes|bytes> <factor> <maxRhs> <out.json> [out.peg]: parse, compact, then eliminate named rules
      // greedily (PegBudgetInline) while the total rhs nodes (bytes) stay within factor x the compacted total and no rhs
      // exceeds maxRhs nodes; metrics of the compacted input and of the result, the exported grammar optional
      val text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8")
      val g = PegOptimize.compact(Grammar.parse(text)).grammar
      val before = PegMetrics.of(g, PegMetrics.componentOfName)
      val factor = factorS.toDouble
      val maxRhs = maxRhsS.toInt
      val opts = preset match {
        case "bytes" => PegBudgetInline.Options(nodeBudget = Long.MaxValue, byteBudget = (before.renderedUtf8Bytes * factor).toLong, maxRhs = maxRhs, priority = PegBudgetInline.Bytes)
        case _ => PegBudgetInline.Options(nodeBudget = (before.rhsNodes * factor).toLong, maxRhs = maxRhs)
      }
      val t0 = System.nanoTime()
      val r = PegBudgetInline.inline(g, opts)
      val secs = (System.nanoTime() - t0) / 1e9
      val after = PegMetrics.of(r.grammar, PegMetrics.componentOfName)
      val commit = scala.sys.process.Process(Seq("git", "rev-parse", "--short", "HEAD")).!!.trim
      val json = after.json(Seq("source_file" -> path, "source_commit" -> commit, "pass" -> "PegOptimize.compact+PegBudgetInline",
        "preset" -> preset, "factor" -> factorS, "max_rhs_budget" -> maxRhsS, "eliminated" -> r.eliminated.size.toString,
        "N_before" -> before.rules.toString, "rhs_nodes_before" -> before.rhsNodes.toString, "bytes_before" -> before.renderedUtf8Bytes.toString,
        "bytes_estimate" -> r.bytesEstimate.toString, "seconds" -> f"$secs%.1f"))
      java.nio.file.Files.write(java.nio.file.Paths.get(outJson), json.getBytes("UTF-8"))
      rest.headOption.foreach(p => java.nio.file.Files.write(java.nio.file.Paths.get(p), r.grammar.render.getBytes("UTF-8")))
      println(PegMetrics.markdownHeader)
      println(before.markdownRow(s"$path compact"))
      println(after.markdownRow(s"inline $preset x$factorS maxRhs $maxRhsS"))
      println(f"eliminated ${r.eliminated.size} rules in $secs%.1f s; byte estimate ${r.bytesEstimate}")
    case "lps-check-file" :: path :: rest =>
      // lps-check-file <path.peg> <maxLen>: re-parse a printed grammar and compare it with the palindrome oracle on all words
      val maxLen = rest.headOption.map(_.toInt).getOrElse(8)
      val text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8")
      val g = Grammar.parse(text)
      val it = Exhaust.words(maxLen, "ab")
      var bad: Option[String] = None
      var count = 0
      while (bad.isEmpty && it.hasNext) {
        val w = it.next()
        count += 1
        if (g.accepts(w) != LpsPal.isPal(w)) { bad = Some(s"'$w': PEG ${g.accepts(w)}, oracle ${LpsPal.isPal(w)}") }
      }
      val (n, sz) = g.size
      println(bad.getOrElse(s"ok: $path re-parsed (rules=$n symbols=$sz) agrees with the palindrome oracle on all $count words of length <= $maxLen"))
    case "lps-code" :: sub :: kind :: rest =>
      // lps-code sizes <pal3|overlap> <m> <f> | optimize <kind> <m> <f> <W0|auto> <out.json> [trie] | export <kind> <m> <f> <W0|auto> <path> [trie]
      //          | peg <kind> <maxLen> <m> <f> <W0> [trie] | check <kind> <m> <f> <W0> <words...> [trie]
      // pal3: LpsWarmup.machine(m, f, W0), fallback below 2 W0; overlap: OverlapPal.machine(a, b, W0), fallback below 3 W0 / 2
      val trie = rest.contains("trie")
      // role options: noacc (no young acc), tmp (rv/ks merged), fuse (first old and young steps fused into IN)
      val roleFlags = Set("noacc", "tmp", "fuse", "compact", "rk", "endent", "endonce", "p3", "star", "unfold", "mirror", "pairs", "recolour", "lgap", "merge2", "merge3", "noU", "noG", "noR", "noR1", "noR2", "sound", "pts")
      val ropts = RolePal.Options(youngReport = !rest.contains("noacc"), mergeTmp = rest.contains("tmp"), fuseIn = rest.contains("fuse"), compact = rest.contains("compact"),
        sharedRK = rest.contains("rk"), endAsEntry = rest.contains("endent"), endOnce = rest.contains("endonce"), rearInP3 = rest.contains("p3"),
        sched = OverlapPal.schedArg(rest), unfold = rest.contains("unfold"), pairs = rest.contains("pairs"), lgap = rest.contains("lgap"),
        merge2 = rest.contains("merge2"), merge3 = rest.contains("merge3"), m2off = Set("U", "G", "R", "R1", "R2").filter(x => rest.contains(s"no$x")))
      val args = rest.filter(x => x != "trie" && !roleFlags(x) && !x.startsWith("beta="))
      val isOverlap = kind == "overlap" || kind == "gap" || kind == "role"
      val coreKind: OverlapPal.CoreKind = if (kind == "gap") { OverlapPal.GapCore } else { OverlapPal.Kmp2Core }
      def mk(ms: Int, fs: Int, w0: Int): Lps.Machine =
        if (kind == "role") { RolePal.machine(ms, fs, w0, ropts) } else if (isOverlap) { OverlapPal.machine(ms, fs, w0, coreKind) } else { LpsWarmup.machine(ms, fs, w0) }
      def firstAnswer(w0: Int): Int = if (isOverlap) { OverlapPal.firstAnswer(w0, ropts.sched) } else { LpsWarmup.firstAnswer(w0) }
      def opts(w0: Int) = LpsCode.Options(fallbackBelow = Some(firstAnswer(w0)), minBirth = w0, dispatch = if (trie) { LpsCode.Trie } else { LpsCode.Direct },
        codeByStar = rest.contains("star"), mirrorFallback = rest.contains("mirror"), recolour = rest.contains("recolour"),
        sound = rest.contains("sound"), pointsTo = rest.contains("pts"))
      // the colours do not depend on the controller; the staged clock needs its smallest W0
      def colours(ms: Int, fs: Int): Int = LpsCode.compileMeta(mk(ms, fs, if (ropts.sched.half) { 2 } else { math.max(8, ropts.sched.minW0) })).coloring.count
      // W0: "auto" = the power of two >= colours; "auto3" = the smallest 3 * 2^k (k >= 2) >= colours (a base-3 clock, RolePal)
      def w0Of(ms: Int, fs: Int, spec: String): Int = spec match {
        case "auto" => SiteCode.nextPow2(math.max(colours(ms, fs), 2))
        case "auto3" => { val c = colours(ms, fs); Iterator.iterate(12)(_ * 2).find(_ >= c).get }
        case x => x.toInt
      }
      sub match {
        case "sizes" =>
          val c = colours(args(0).toInt, args(1).toInt)
          val w0 = SiteCode.nextPow2(math.max(c, 2))
          println(s"$kind colours=$c W0=$w0 firstAnswer=${firstAnswer(w0)}")
        case "optimize" | "export" =>
          val (ms, fs) = (args(0).toInt, args(1).toInt)
          val w0 = w0Of(ms, fs, args(2))
          val m = mk(ms, fs, w0)
          val t0 = System.nanoTime()
          val res = LpsCode.compileMeta(m, opts(w0))
          val gen = (System.nanoTime() - t0) / 1e9
          val comp = (n: String) => res.meta.get(n).map(_.slot).getOrElse(PegMetrics.componentOfName(n))
          val r = PegOptimize.compact(res.grammar)
          if (sub == "export") {
            java.nio.file.Files.write(java.nio.file.Paths.get(args(3)), r.grammar.render.getBytes("UTF-8"))
            val (nn, sz) = r.grammar.size
            println(s"exported ${args(3)}: rules=$nn symbols=$sz colours=${res.coloring.count} W0=$w0 firstAnswer=${firstAnswer(w0)}")
          } else {
            val before = PegMetrics.of(res.grammar, comp)
            val after = PegMetrics.of(r.grammar, comp)
            val kept = r.grammar.rules.map(_._1).toSet
            val codeRules = r.grammar.rules.count(_._1.endsWith("_c"))
            val helperRules = kept.count(n => res.meta.get(n).exists(_.slot == "helper"))
            val fallbackRules = kept.count(n => res.meta.get(n).exists(_.slot == "fallback"))
            val commit = scala.sys.process.Process(Seq("git", "rev-parse", "--short", "HEAD")).!!.trim
            val variant = if (trie) { "LpsCode+trie+compact" } else { "LpsCode+direct+compact" }
            val json = after.json(Seq("machine_variant" -> (if (kind == "role") { "RolePal" } else if (kind == "gap") { "OverlapPal+GapKmp" } else if (isOverlap) { "OverlapPal" } else { "LpsWarmup(PAL3)" }), "compiler_variant" -> variant,
              "mSteps" -> ms.toString, "fSteps" -> fs.toString, "source_commit" -> commit, "generation_seconds" -> f"$gen%.2f",
              "site_color_count" -> res.coloring.count.toString, "warmup_W0" -> w0.toString, "first_answer" -> firstAnswer(w0).toString,
              "site_code_rules" -> codeRules.toString, "helper_rules" -> helperRules.toString, "fallback_rules" -> fallbackRules.toString,
              "dispatch" -> (if (trie) { "trie" } else { "direct" }), "N_raw" -> before.rules.toString, "rhs_nodes_raw" -> before.rhsNodes.toString))
            java.nio.file.Files.write(java.nio.file.Paths.get(args(3)), json.getBytes("UTF-8"))
            println(PegMetrics.markdownHeader)
            println(before.markdownRow(s"$kind+code $ms/$fs W0=$w0 raw"))
            println(after.markdownRow(s"$kind+code $ms/$fs W0=$w0 compact"))
            println(s"colours=${res.coloring.count} W0=$w0 firstAnswer=${firstAnswer(w0)} code rules=$codeRules helper=$helperRules fallback=$fallbackRules")
          }
        case "peg" =>
          val (maxLen, ms, fs, w0) = (args(0).toInt, args(1).toInt, args(2).toInt, args(3).toInt)
          val msg = s"ok: $kind+code $ms/$fs W0=$w0 agrees (fallback below ${firstAnswer(w0)}, machine above) on all words <= $maxLen"
          println(LpsCode.checkSim(mk(ms, fs, w0), maxLen, opts(w0)).getOrElse(msg))
        case "check" =>
          val (ms, fs, w0) = (args(0).toInt, args(1).toInt, args(2).toInt)
          val g = LpsCode.compile(mk(ms, fs, w0), opts(w0))
          for (w <- args.drop(3)) { val got = g.accepts(w); println(s"${w.length} ${if (got == LpsPal.isPal(w)) "ok" else "WRONG"} PEG=$got oracle=${LpsPal.isPal(w)}") }
        case _ => println("unknown lps-code subcommand")
      }
    case "lps-warmup" :: sub :: rest =>
      // lps-warmup sim <maxLen> <m> <f> <W0> | rand <n> <len> <m> <f> <W0> | profile <m> <f> <W0> boundary | profile <m> <f> <W0> structured <maxLen>
      sub match {
        case "sim" =>
          val (maxLen, ms, fs, w0) = (rest(0).toInt, rest(1).toInt, rest(2).toInt, rest(3).toInt)
          val m = LpsWarmup.machine(ms, fs, w0)
          val bad = Exhaust.words(maxLen, "ab").filter(_.nonEmpty).find(w => Lps.run(m, w).outputs != LpsWarmup.expectedOutputs(w0, w))
          println(bad.map(w => s"'$w': got ${Lps.run(m, w).outputs.map(b => if (b) "1" else "0").mkString}").getOrElse(s"ok: warmup $ms/$fs W0=$w0 agrees on all words <= $maxLen"))
        case "rand" =>
          val (n, len, ms, fs, w0) = (rest(0).toInt, rest(1).toInt, rest(2).toInt, rest(3).toInt, rest(4).toInt)
          val m = LpsWarmup.machine(ms, fs, w0)
          val rnd = new scala.util.Random(1)
          var bad: Option[String] = None
          var i = 0
          while (bad.isEmpty && i < n) {
            val base = Vector.fill(1 + rnd.nextInt(len))(if (rnd.nextBoolean()) 'a' else 'b').mkString
            val w = rnd.nextInt(3) match { case 0 => base; case 1 => base + base.reverse; case _ => base + base.reverse.drop(1) + "b" }
            if (Lps.run(m, w).outputs != LpsWarmup.expectedOutputs(w0, w)) { bad = Some(s"'$w'") }
            i += 1
          }
          println(bad.getOrElse(s"ok: warmup $ms/$fs W0=$w0 agrees on $n random words of length <= ${2 * len + 1}"))
        case "profile" =>
          val (ms, fs, w0) = (rest(0).toInt, rest(1).toInt, rest(2).toInt)
          val m = LpsWarmup.machine(ms, fs, w0)
          val words = rest(3) match { case "boundary" => LpsWarmup.boundaryWords(w0); case "adversarial" => LpsProfile.adversarialWords(rest(4).toInt); case _ => LpsProfile.structuredWords(rest(4).toInt) }
          var bad = 0
          for (w <- words) {
            val pr = LpsProfile.profile(m, w, 2 * w0)
            val reach = LpsWarmup.checkReachable(m, w, w0)
            if (pr.violations.nonEmpty || reach.nonEmpty) { bad += 1; println(s"'${w.take(40)}' (${w.length}): ${(pr.violations.take(2) ++ reach).mkString("; ")}") }
          }
          println(s"${words.size} words, $bad with violations")
        case _ => println("unknown lps-warmup subcommand")
      }
    case "pool-grid" :: minAnchorS :: advS :: switchS :: structS :: randCountS :: randMaxS :: threadsS :: configs =>
      // pool-grid <minAnchor> <adversarial maxLen> <switch maxLen> <structured maxLen> <random count> <random maxLen> <threads> a/b/old ...
      // the pools plan's rate grid on the old-limit model (OverlapPal gap core; old = 0 means no limit, strict handover check
      // when limited); per configuration pass/fail, the first violating word and the demand summary
      val minAnchor = minAnchorS.toInt
      // an optional "seed=<n>" among the configurations: the seed of the random words (default 21)
      val seed = configs.collectFirst { case x if x.startsWith("seed=") => x.drop(5).toLong }.getOrElse(21L)
      // an optional "beta=<num>/<den>": the anchor schedule (STRATEGY S3; default 1/2)
      val sc = OverlapPal.schedArg(configs)
      val families = Vector("random" -> OverlapProfile.randomWords(randCountS.toInt, randMaxS.toInt, seed), "adversarial" -> OverlapProfile.adversarialWords(advS.toInt, sc),
        "switch" -> OverlapProfile.switchWords(switchS.toInt, sc), "structured" -> LpsProfile.structuredWords(structS.toInt)) ++
        // an optional "every=<maxLen>": the runner words at every length (OverlapProfile.everyLengthWords)
        // (its random palindromes use seed 5 unless a seed is given)
        configs.collectFirst { case x if x.startsWith("every=") =>
          "every" -> OverlapProfile.everyLengthWords(x.drop(6).toInt, configs.collectFirst { case y if y.startsWith("seed=") => y.drop(5).toLong }.getOrElse(5L)) }.toVector ++
        // an optional "lengths=<n1>,<n2>,…": the same kinds of words at the given lengths only (sampled long words)
        configs.collectFirst { case x if x.startsWith("lengths=") =>
          "lengths" -> OverlapProfile.lengthWords(x.drop(8).split(",").map(_.toInt).toVector, configs.collectFirst { case y if y.startsWith("seed=") => y.drop(5).toLong }.getOrElse(5L)) }.toVector
      for (cfg <- configs if !cfg.startsWith("seed=") && !cfg.startsWith("beta=") && !cfg.startsWith("every=") && !cfg.startsWith("lengths=")) {
        // a/b/old: the old-limit model; r<ky>/<ko>: the role machine
        val t0 = System.nanoTime()
        val r = if (cfg.startsWith("r")) {
          // r<ky>/<ko>[:flag,flag] with the role options noacc, tmp, fuse
          val (sizes, flags) = cfg.drop(1).split(":", 2) match { case Array(a) => (a, Set.empty[String]); case Array(a, f) => (a, f.split(",").toSet) }
          val Array(ky, ko) = sizes.split("/").map(_.toInt)
          val ro = RolePal.Options(youngReport = !flags("noacc"), mergeTmp = flags("tmp"), fuseIn = flags("fuse"), compact = flags("compact"),
            sharedRK = flags("rk"), endAsEntry = flags("endent"), endOnce = flags("endonce"), rearInP3 = flags("p3"), sched = sc, unfold = flags("unfold"),
            lgap = flags("lgap"), merge2 = flags("merge2"), merge3 = flags("merge3"), m2off = Set("U", "G", "R", "R1", "R2").filter(x => flags(s"no$x")))
          OverlapProfile.grid(cfg, RolePal.machine(ky, ko, minAnchor, ro), minAnchor, true, families, threadsS.toInt, OverlapProfile.Roles, ro.fuseIn, sc)
        } else {
          // a/b/old[:unfold]
          val (sizes, mflags) = cfg.split(":", 2) match { case Array(x) => (x, Set.empty[String]); case Array(x, f) => (x, f.split(",").toSet) }
          val Array(a, b, o) = sizes.split("/").map(_.toInt)
          val limit = if (o <= 0) { Int.MaxValue } else { o }
          OverlapProfile.grid(cfg, OverlapPal.machine(a, b, minAnchor, OverlapPal.GapCore, limit, sc, mflags("unfold")), minAnchor, limit < Int.MaxValue, families, threadsS.toInt, sc = sc)
        }
        val secs = (System.nanoTime() - t0) / 1e9
        val verdict = if (r.pass) { s"PASS (${r.words} words)" } else { s"FAIL after ${r.words} words: ${r.firstBad.map { case (w, v) => s"${w.take(60)}${if (w.length > 60) "…" else ""} [len ${w.length - w.indexOf(": ") - 2}] $v" }.getOrElse("")}" }
        println(f"grid $cfg minAnchor $minAnchor${sc.label}: $verdict ($secs%.0f s)")
        if (r.pass) { println(s"  ${r.demand.summary}") }
        r.firstBad.foreach { case (w, _) => java.nio.file.Files.write(java.nio.file.Paths.get(s"/tmp/pool-grid-bad-${cfg.replace('/', '-')}.txt"), (w.drop(w.indexOf(": ") + 2) + "\n").getBytes("UTF-8")) }
      }
    case "overlap-profile" :: rest0 =>
      // overlap-profile <a> <b> <word> [minAnchor] [gap] | overlap-profile <a> <b> structured|switch|adversarial <maxLen> [minAnchor] [gap]
      // an optional "old=<k>": the answering bank runs only its first k copies per tick (the pools plan's small old pool)
      val oldLimit = rest0.collectFirst { case x if x.startsWith("old=") => x.drop(4).toInt }.getOrElse(Int.MaxValue)
      // an optional "dump=<path>": the violating words, one per line
      val dump = rest0.collectFirst { case x if x.startsWith("dump=") => x.drop(5) }
      val role = rest0.contains("role")
      // role options for the role machine (as in lps-code): noacc fuse compact endent endonce
      val roleOpt = Set("noacc", "fuse", "compact", "endent", "endonce", "unfold")
      val sc = OverlapPal.schedArg(rest0)
      val ro = RolePal.Options(youngReport = !rest0.contains("noacc"), fuseIn = rest0.contains("fuse"), compact = rest0.contains("compact"),
        endAsEntry = rest0.contains("endent"), endOnce = rest0.contains("endonce"), sched = sc, unfold = rest0.contains("unfold"))
      val rest = rest0.filterNot(x => x == "gap" || x == "role" || roleOpt(x) || x.startsWith("old=") || x.startsWith("dump=") || x.startsWith("beta="))
      val dumped = Vector.newBuilder[String]
      val coreKind: OverlapPal.CoreKind = if (rest0.contains("gap")) { OverlapPal.GapCore } else { OverlapPal.Kmp2Core }
      val (a, b) = (rest(0).toInt, rest(1).toInt)
      val (words, minAnchor) = rest(2) match {
        case "structured" => (LpsProfile.structuredWords(rest(3).toInt), rest.lift(4).map(_.toInt).getOrElse(2))
        case "switch" => (OverlapProfile.switchWords(rest(3).toInt, sc), rest.lift(4).map(_.toInt).getOrElse(2))
        case "adversarial" => (OverlapProfile.adversarialWords(rest(3).toInt, sc), rest.lift(4).map(_.toInt).getOrElse(2))
        case "random" => (OverlapProfile.randomWords(rest(3).toInt, rest(4).toInt), rest.lift(5).map(_.toInt).getOrElse(2))
        case w => (Vector(w), rest.lift(3).map(_.toInt).getOrElse(2))
      }
      val m = if (role) { RolePal.machine(a, b, minAnchor, ro) } else { OverlapPal.machine(a, b, minAnchor, coreKind, oldLimit, sc, rest0.contains("unfold")) }
      val layout = if (role) { OverlapProfile.Roles } else { OverlapProfile.Banks }
      var bad = 0
      var maxUnits = 0
      var maxBacklog = 0
      var demand = OverlapProfile.Demand()
      var kinds = OverlapProfile.StepKinds()
      for (w <- words) {
        val pr = OverlapProfile.profile(m, w, minAnchor, role || oldLimit < Int.MaxValue, layout, role && ro.fuseIn, sc)
        demand = demand.merge(pr.demand)
        kinds = kinds.merge(pr.kinds)
        maxUnits = math.max(maxUnits, pr.maxUnitsPerTick)
        maxBacklog = math.max(maxBacklog, pr.lives.map(_.maxBacklog).maxOption.getOrElse(0))
        if (words.size == 1) { println(pr.table) }
        if (pr.violations.nonEmpty) { dumped += w }
        if (pr.violations.nonEmpty) { bad += 1; if (bad <= 5) { println(s"'${w.take(40)}${if (w.length > 40) "..." else ""}' (${w.length}): " + pr.violations.take(3).mkString("; ")) } }
      }
      println(s"overlap${if (role) "+role" else if (coreKind == OverlapPal.GapCore) "+gap" else ""}${if (oldLimit < Int.MaxValue) s" old<=$oldLimit" else ""} $a/$b minAnchor $minAnchor: ${words.size} words, $bad with violations, max units in a tick $maxUnits, max backlog $maxBacklog")
      println(demand.summary)
      println(kinds.summary)
      dump.foreach(p => java.nio.file.Files.write(java.nio.file.Paths.get(p), dumped.result().mkString("", "\n", "\n").getBytes("UTF-8")))
    case "lps-kmp2" :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(6)
      val n = rest.drop(1).headOption.map(_.toInt).getOrElse(6)
      println(Lps.check(LpsKmp2.machine(n), maxLen, LpsKmp2.oracle).getOrElse(s"ok: universal-step KMP (n=$n): interpreter and PEG agree with the oracle on all words of length <= $maxLen"))
    case "lps-kmp2-sim" :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(8)
      val n = rest.drop(1).headOption.map(_.toInt).getOrElse(6)
      val m = LpsKmp2.machine(n)
      val it = Exhaust.words(maxLen, "ab#")
      var bad: Option[String] = None
      var count = 0
      while (bad.isEmpty && it.hasNext) {
        val w = it.next()
        count += 1
        if (w.nonEmpty) {
          val run = Lps.run(m, w)
          val rd = w.reverse
          val want = (1 to w.length).map(k => LpsKmp2.oracle(rd.take(k).reverse))
          if (run.outputs != want) { bad = Some(s"'$w' (reading $rd): got ${run.outputs.map(b => if (b) "1" else "0").mkString} want ${want.map(b => if (b) "1" else "0").mkString}") }
        }
      }
      println(bad.getOrElse(s"ok: universal-step KMP (n=$n, budget=${m.budget}) agrees on all $count words of length <= $maxLen"))
    case "lps-kmp2-size" :: rest =>
      val n = rest.headOption.map(_.toInt).getOrElse(6)
      val g = Lps.compile(LpsKmp2.machine(n))
      val (rules, symbols) = g.size
      println(s"lps kmp2: n $n, PEG rules=$rules symbols=$symbols")
      if (rest.contains("print")) { println(g.render) }
    case "lps-kmp-eval" :: w :: pairsS :: file :: Nil =>
      val exprs = scala.io.Source.fromFile(file).getLines().map(_.trim).filter(_.nonEmpty).toList
      val g = Lps.compile(LpsKmp.machine(pairsS.toInt))
      val extra = exprs.zipWithIndex.map { case (e, i) => s"ZZ$i = $e;" }.mkString("\n")
      val g2 = Grammar.parse(g.render + "\n" + extra)
      val mt = g2.matcher(w)
      for ((e, i) <- exprs.zipWithIndex) {
        val vals = (0 to w.length).map(pos => mt.rule(s"ZZ$i", pos).map(x => (x - pos).toString).getOrElse("-")).mkString(" ")
        println(s"$e   @[$vals]")
      }
    case "lps-kmp-rules" :: w :: pat :: rest =>
      val pairs = rest.headOption.map(_.toInt).getOrElse(2)
      val g = Lps.compile(LpsKmp.machine(pairs))
      val mt = g.matcher(w)
      val re = pat.r
      for ((n, e) <- g.rules if re.findFirstIn(n).isDefined) {
        val vals = (0 to w.length).map(pos => mt.rule(n, pos).map(x => (x - pos).toString).getOrElse("-")).mkString(" ")
        println(s"$n = ${Expr.render(e).take(300)}   @[$vals]")
      }
    case "lps-kmp-sim" :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(8)
      val pairs = rest.drop(1).headOption.map(_.toInt).getOrElse(3)
      val m = LpsKmp.machine(pairs)
      val it = Exhaust.words(maxLen, "ab#")
      var bad: Option[String] = None
      var count = 0
      while (bad.isEmpty && it.hasNext) {
        val w = it.next()
        count += 1
        if (w.nonEmpty) {
          val run = Lps.run(m, w)
          val rd = w.reverse
          val want = (1 to w.length).map(k => LpsKmp.oracle(rd.take(k).reverse))
          if (run.outputs != want) { bad = Some(s"'$w' (reading $rd): got ${run.outputs.map(b => if (b) "1" else "0").mkString} want ${want.map(b => if (b) "1" else "0").mkString}") }
        }
      }
      println(bad.getOrElse(s"ok: slot kmp (pairs=$pairs, budget=${m.budget}) agrees on all $count words of length <= $maxLen"))
    case "lps-kmp-size" :: rest =>
      val pairs = rest.headOption.map(_.toInt).getOrElse(3)
      val m = LpsKmp.machine(pairs)
      val g = Lps.compile(m)
      val (rules, symbols) = g.size
      println(s"lps kmp: pairs $pairs budget ${m.budget}, PEG rules=$rules symbols=$symbols")
      // histogram: rules per layer, split into registers/bits, named pointers (X), conditions (K), cells (C)
      val byLayer = g.rules.groupBy { case (n, _) => n.split("_").lift(1).flatMap(_.toIntOption).getOrElse(-1) }
      for (t <- 0 to m.budget) {
        val rs = byLayer.getOrElse(t, Vector())
        def cnt(p: String) = rs.count { case (n, _) => n.startsWith(p) }
        val sz = rs.map { case (_, e) => Expr.size(e) }.sum
        println(f"  layer $t%2d ${if (t == 0) "-" else m.schedule(t - 1)}%-5s rules=${rs.size}%4d symbols=$sz%6d  X=${cnt("X")}%3d K=${cnt("K")}%3d C=${cnt("C_")}%3d other=${rs.size - cnt("X") - cnt("K") - cnt("C_")}%3d")
      }
      if (rest.contains("top")) {
        for ((n, e) <- g.rules.sortBy { case (_, e) => -Expr.size(e) }.take(12)) { println(s"  BIG ${Expr.size(e)} $n = ${Expr.render(e).take(400)}") }
      }
      if (rest.contains("print")) { println(g.render) }
    case "lps-kmp" :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(6)
      val pairs = rest.drop(1).headOption.map(_.toInt).getOrElse(3)
      val m = LpsKmp.machine(pairs)
      println(Lps.check(m, maxLen, LpsKmp.oracle).getOrElse(s"ok: slot kmp interpreter and PEG agree with the oracle on all words of length <= $maxLen"))
    case "lpm-kmp-sim" :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(8)
      val budget = rest.drop(1).headOption.map(_.toInt).getOrElse(4000)
      val m = LpmKmp.machine
      def perTick(w: String): Option[String] = {
        val run = Lpm.run(m, w, budget)
        val rd = w.reverse
        val want = (1 to w.length).map(k => LpmKmp.oracle(rd.take(k).reverse))
        if (run.outputs == want) { None } else { Some(s"'$w' (reading ${rd}): got ${run.outputs.map(b => if (b) "1" else "0").mkString} want ${want.map(b => if (b) "1" else "0").mkString}; units ${run.unitsNeeded.mkString(",")}") }
      }
      val it = Exhaust.words(maxLen, "ab#")
      var bad: Option[String] = None
      var count = 0
      var worst = 0
      while (bad.isEmpty && it.hasNext) {
        val w = it.next()
        count += 1
        if (w.nonEmpty) { bad = perTick(w); worst = math.max(worst, Lpm.run(m, w, budget).unitsNeeded.max) }
      }
      println(bad.getOrElse(s"ok: kmp interpreter agrees on all $count words of length <= $maxLen at budget $budget; max units per tick = $worst"))
    case "lpm-kmp-size" :: rest =>
      val budget = rest.headOption.map(_.toInt).getOrElse(8)
      val g = Lpm.compile(LpmKmp.machine, budget)
      val (rules, symbols) = g.size
      println(s"lpm kmp: budget $budget, PEG rules=$rules symbols=$symbols (per layer: rules=${rules / budget} symbols=${symbols / budget})")
      if (rest.contains("top")) {
        for ((n, e) <- g.rules.sortBy { case (_, e) => -Expr.size(e) }.take(12)) { println(s"  BIG ${Expr.size(e)} $n = ${Expr.render(e).take(400)}") }
      }
      if (rest.contains("print")) { println(g.render) }
    case "lpm-kmp" :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(6)
      val budget = rest.drop(1).headOption.map(_.toInt).getOrElse(8)
      val g = Lpm.compile(LpmKmp.machine, budget)
      val (rules, symbols) = g.size
      println(s"lpm kmp: budget $budget, PEG rules=$rules symbols=$symbols")
      println(Lpm.check(LpmKmp.machine, budget, maxLen, LpmKmp.oracle).getOrElse(s"ok: interpreter and PEG agree with the oracle on all words of length <= $maxLen"))
    case "lpm-slisenko-word" :: w :: rest =>
      val budget = rest.headOption.map(_.toInt).getOrElse(4000)
      val run = Lpm.run(LpmSlisenko.machine, w, budget)
      val want = (1 to w.length).map(k => Exhaust.isPal(w.substring(w.length - k)))
      println(s"word $w budget $budget")
      println("  got  " + run.outputs.map(b => if (b) { "1" } else { "0" }).mkString)
      println("  want " + want.map(b => if (b) { "1" } else { "0" }).mkString)
      println("  units " + run.unitsNeeded.mkString(","))
    case "lpm-slisenko-budget" :: rest =>
      // smallest budget with which every word of length <= maxLen is classified correctly at every tick
      val maxLen = rest.headOption.map(_.toInt).getOrElse(10)
      val m = LpmSlisenko.machine
      def okAt(b: Int): Option[String] = {
        val it = Exhaust.words(maxLen)
        var bad: Option[String] = None
        while (bad.isEmpty && it.hasNext) {
          val w = it.next()
          if (w.nonEmpty) {
            val run = Lpm.run(m, w, b)
            val want = (1 to w.length).map(k => Exhaust.isPal(w.substring(w.length - k)))
            if (run.outputs != want) { bad = Some(w) }
          }
        }
        bad
      }
      var lo = 8
      var hi = 4096
      while (lo < hi) { val mid = (lo + hi) / 2; if (okAt(mid).isEmpty) { hi = mid } else { lo = mid + 1 } }
      println(s"minimal budget for all words of length <= $maxLen: $lo (first failure at ${lo - 1}: ${okAt(lo - 1).getOrElse("-")})")
    case "lpm-slisenko-long" :: rest =>
      val len = rest.headOption.map(_.toInt).getOrElse(200)
      val budget = rest.drop(1).headOption.map(_.toInt).getOrElse(64)
      val seeds = rest.drop(2).headOption.map(_.toInt).getOrElse(3)
      val m = LpmSlisenko.machine
      def check(name: String, w: String): Unit = {
        val run = Lpm.run(m, w, budget)
        val want = (1 to w.length).map(k => Exhaust.isPal(w.substring(w.length - k)))
        val firstBad = run.outputs.zip(want).indexWhere { case (a, b) => a != b }
        println(f"$name%-22s |w|=${w.length}%5d budget=$budget " + (if (firstBad < 0) { s"ok (max units ${run.unitsNeeded.max})" } else { s"WRONG at tick ${firstBad + 1}" }))
      }
      for (seed <- 0 until seeds) {
        val rng = new scala.util.Random(seed)
        val half = Vector.fill(len / 2)("ab".charAt(rng.nextInt(2))).mkString
        check(s"random pal seed $seed", half + half.reverse)
        check(s"random seed $seed", Vector.fill(len)("ab".charAt(rng.nextInt(2))).mkString)
      }
      check("a^n", "a" * len)
      check("(ab)^n", "ab" * (len / 2))
      check("fibonacci", { var a = "a"; var b = "ab"; while (b.length < len) { val c = b + a; a = b; b = c }; b.take(len) })
    case "lpm-slisenko-sim" :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(8)
      val budget = rest.drop(1).headOption.map(_.toInt).getOrElse(4000)
      val m = LpmSlisenko.machine
      val it = Exhaust.words(maxLen)
      var bad: Option[String] = None
      var worst = 0
      var count = 0
      while (bad.isEmpty && it.hasNext) {
        val w = it.next()
        count += 1
        if (w.nonEmpty) {
          val run = Lpm.run(m, w, budget)
          val want = (1 to w.length).map(k => Exhaust.isPal(w.substring(w.length - k)))   // prefix k of the reversed word
          if (run.outputs != want) { bad = Some(s"'$w': outputs ${run.outputs.mkString(",")} want ${want.mkString(",")}; units ${run.unitsNeeded.mkString(",")}") }
          worst = math.max(worst, run.unitsNeeded.max)
        }
      }
      println(bad.getOrElse(s"ok: interpreter agrees on all $count words of length <= $maxLen; max units per tick = $worst"))
    case "lpm-slisenko-size" :: rest =>
      val budget = rest.headOption.map(_.toInt).getOrElse(32)
      val g = Lpm.compile(LpmSlisenko.machine, budget)
      val (rules, symbols) = g.size
      println(s"lpm slisenko: budget $budget, PEG rules=$rules symbols=$symbols (per layer: rules=${rules / budget} symbols=${symbols / budget})")
    case "lpm-slisenko" :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(6)
      val budget = rest.drop(1).headOption.map(_.toInt).getOrElse(64)
      val m = LpmSlisenko.machine
      val g = Lpm.compile(m, budget)
      val (rules, symbols) = g.size
      println(s"lpm slisenko: budget $budget, PEG rules=$rules symbols=$symbols")
      println(Lpm.check(m, budget, maxLen, LpmSlisenko.oracle).getOrElse(s"ok: interpreter and PEG agree with the oracle on all words of length <= $maxLen"))
      if (rest.contains("top")) {
        for ((n, e) <- g.rules.sortBy { case (_, e) => -Expr.size(e) }.take(12)) { println(s"  BIG ${Expr.size(e)} $n = ${Expr.render(e).take(400)}") }
      }
      if (rest.contains("print")) { println(g.render) }
    case "lpm-marked" :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(7)
      val budget = rest.drop(1).headOption.map(_.toInt).getOrElse(1)
      val m = LpmMarked.machine
      val g = Lpm.compile(m, budget)
      val (rules, symbols) = g.size
      println(s"lpm marked: budget $budget, PEG rules=$rules symbols=$symbols")
      println(Lpm.check(m, budget, maxLen, LpmMarked.oracle).getOrElse(s"ok: interpreter and PEG agree with the oracle on all words of length <= $maxLen"))
      if (rest.contains("top")) {
        for ((n, e) <- g.rules.sortBy { case (_, e) => -Expr.size(e) }.take(12)) { println(s"  BIG ${Expr.size(e)} $n = ${Expr.render(e).take(400)}") }
      }
      if (rest.contains("print")) { println(g.render) }
    case "slisenko-profile" :: w :: _ =>
      SlisenkoRT.profile(w)
    case "slisenko-trace" :: w :: _ =>
      println(Slisenko.trace(w).map(_.fold(_.toString, l => s"L(${l.first},+${l.step}x${l.count})")).mkString(" "))
    case "slisenko-cost" :: rest =>
      Slisenko.costReport(rest.headOption.map(_.toInt).getOrElse(12))
    case _ =>
      println("usage: run sanity | run rttm <anbn|marked> [maxLen] [print] | run slisenko [maxLen]")
  }
}
