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
      if (rest.contains("print")) { println(g.render) }
    case "slisenko" :: rest =>
      val maxLen = rest.headOption.map(_.toInt).getOrElse(10)
      Slisenko.check(maxLen)
    case _ =>
      println("usage: run sanity | run rttm <anbn|marked> [maxLen] [print] | run slisenko [maxLen]")
  }
}
