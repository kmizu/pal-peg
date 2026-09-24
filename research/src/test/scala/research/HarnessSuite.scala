package research

class HarnessSuite extends munit.FunSuite {

  test("the interpreter agrees with known grammars") {
    val anbn = Grammar.parse("S = \"a\" S \"b\" / \"\";")
    assert(Exhaust.words(10).forall(w => anbn.accepts(w) == (w.length % 2 == 0 && w == "a" * (w.length / 2) + "b" * (w.length / 2))))
    val marked = Grammar.parse("S = \"a\" S \"a\" / \"b\" S \"b\" / \"#\";")
    assert(Exhaust.words(8, "ab#").forall(w => marked.accepts(w) == (w.count(_ == '#') == 1 && Exhaust.isPal(w))))
    val parity = Grammar.parse("Even = !. / . Odd; Odd = . Even;")
    assert(Exhaust.words(9).forall(w => parity.accepts(w, "Even") == (w.length % 2 == 0)))
  }

  test("the naive palindrome grammar fails on 'aa'") {
    val naive = Grammar.parse("S = P !.; P = \"a\" P \"a\" / \"b\" P \"b\" / \"a\" / \"b\" / \"\";")
    assertEquals(Exhaust.checkLanguage(naive, 6), Some("aa"))
  }

  test("non-consuming recursion is reported") {
    val g = Grammar.parse("S = S \"a\" / \"\";")
    intercept[NonTerminating] { g.accepts("a") }
  }

  test("rendering round-trips") {
    val text = "S = \"a\" S \"b\" / &(. .) [ab]* !\"x\";\nT = S / \"\";"
    val g = Grammar.parse(text)
    val again = Grammar.parse(g.render)
    assertEquals(again.rules, g.rules)
  }

  test("the example machines recognize their languages") {
    assert(Exhaust.words(10).forall(w => Rttm.anbn.run(w).contains(w.length % 2 == 0 && w == "a" * (w.length / 2) + "b" * (w.length / 2))))
    assert(Exhaust.words(8, "ab#").forall(w => Rttm.markedPal.run(w).contains(w.count(_ == '#') == 1 && Exhaust.isPal(w))))
  }

  test("compiled machines agree with their simulation") {
    assertEquals(Rttm2Peg.check(Rttm.anbn, 9), None)
    assertEquals(Rttm2Peg.check(Rttm.markedPal, 7), None)
  }

  test("Slisenko's recursion finds exactly the centres of the initial palindromes") {
    assertEquals(Slisenko.check(10), None)
  }

  test("the real-time head machine certifies every initial palindrome on time at a bounded speed") {
    val it = Exhaust.words(11)
    var worst = 0
    while (it.hasNext) {
      val w = it.next()
      val s = SlisenkoRT.minSpeed(w)
      assert(s.isDefined, s"machine fails on '$w'")
      worst = math.max(worst, s.get)
    }
    assert(worst <= 32, s"worst speed $worst")
  }
}
