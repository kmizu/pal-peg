package research

/** `run sanity` checks the harness; `run rttm <anbn|marked> [maxLen]` compiles a machine and checks it. */
object Main {
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
