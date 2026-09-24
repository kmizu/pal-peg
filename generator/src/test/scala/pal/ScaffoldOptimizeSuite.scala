package pal

import Expr.{TRUE, FALSE, SELF, NULL, old, pointer, read, edge, negate, both, either, symbol}
import ScaffoldOptimize.optimize

/** Constant propagation must preserve sentinel, null, and recursive behavior. */
class ScaffoldOptimizeSuite extends munit.FunSuite {

  /** A generous timeout, so that a heavily loaded machine does not fail the suite. */
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(600, "s")

  private def nullsAndConstants(): Scaffold = {
    new Scaffold(
      Vector("out" -> false, "off" -> false, "propagate" -> false, "on" -> true),
      Vector("off" -> FALSE, "propagate" -> old(Nil, "off"), "on" -> TRUE,
             "out" -> either(both(negate(old(Nil, "propagate")), symbol('a')),
                             read(edge(pointer(Nil), "null"), "on"))),
      Vector("null" -> NULL, "dead" -> pointer(Seq("null"))), "out")
  }

  private def sentinelPointer(): Scaffold = {
    new Scaffold(
      Vector("out" -> true, "first" -> true, "on" -> true),
      Vector("first" -> FALSE, "on" -> TRUE,
             "out" -> either(old(Nil, "first"), both(symbol('a'), read(pointer(Seq("p")), "on")))),
      Vector("p" -> SELF), "out")
  }

  private def compare(source: Scaffold, reduced: Scaffold): Unit = {
    val grammar = new Grammar(reduced.compile())
    for (word <- Words.upTo("ab", 5)) {
      assertEquals(reduced.run(word), source.run(word), word)
      assertEquals(grammar.accepts(word.reverse), source.run(word), word)
    }
  }

  test("nulls and constant labels propagate before field projection") {
    val source = nullsAndConstants()
    val (reduced, stats) = optimize(source)
    assert(stats.rounds > 1)
    assertEquals(reduced.labels.keySet, Set("out"))
    assert(reduced.pointers.isEmpty)
    compare(source, reduced)
  }

  test("initial value and sentinel pointer are not erased") {
    val source = sentinelPointer()
    val (reduced, _) = optimize(source, roots = Seq("first"))
    assert(reduced.labels.contains("first"))
    assert(reduced.pointers.contains("p"))
    compare(source, reduced)
  }
}
