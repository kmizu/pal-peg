package pal

import scala.collection.immutable.VectorMap

import Expr.{SELF, NULL, symbol, old, pointer, select, read as label, edge, both, either, negate, present}
import TempDir.withTempDir

/** Check that compiler checkpoints preserve both equations and behavior. */
class ScaffoldArtifactSuite extends munit.FunSuite {

  /** A generous timeout, so that a heavily loaded machine does not fail the suite. */
  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(600, "s")

  private def sharedMachine(): Scaffold = {
    val target = select(symbol('a'), pointer(Seq("p")), NULL)
    val query = both(present(target), label(edge(target, "loop"), "out"))
    new Scaffold(Vector("out" -> true, "shared" -> false),
                 Vector("out" -> either(query, negate(old(Nil, "out"))), "shared" -> query),
                 Vector("p" -> SELF, "loop" -> pointer(Nil)), "out")
  }

  private val metadata = VectorMap("purpose" -> "round-trip", "service" -> 3)

  private def sharesQuery(loaded: Scaffold): Boolean = {
    loaded.labels("out") match {
      case Expr.Or(values) => values(0) eq loaded.labels("shared")
      case _ => false
    }
  }

  test("data round trip preserves shared graph and exact PEG") {
    val machine = sharedMachine()
    withTempDir("pal-artifact") { directory =>
      for (suffix <- Seq(".sca", ".sca.gz")) {
        val path = directory.resolve("source" + suffix)
        val count = ScaffoldArtifact.write(machine, path, metadata)
        val (loaded, loadedMetadata) = ScaffoldArtifact.read(path)
        assert(count > 0)
        assertEquals(loadedMetadata, metadata)
        assertEquals(loaded.compile(), machine.compile())
        assert(sharesQuery(loaded))
        for (word <- Seq("", "a", "b", "abba", "ababa")) {
          assertEquals(loaded.run(word), machine.run(word))
        }
      }
    }
  }

  test("deep expression graph uses a flat artifact") {
    var expression = symbol('a')
    for (_ <- 0 until 5000) { expression = negate(expression) }
    val machine = new Scaffold(Vector("out" -> false), Vector("out" -> expression), Nil, "out")
    withTempDir("pal-artifact") { directory =>
      val path = directory.resolve("deep.sca")
      ScaffoldArtifact.write(machine, path)
      val (loaded, _) = ScaffoldArtifact.read(path)
      assert(loaded.run("a"))
      assert(!loaded.run("b"))
    }
  }
}
