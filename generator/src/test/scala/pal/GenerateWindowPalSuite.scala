package pal

import java.nio.file.Files

class GenerateWindowPalSuite extends munit.FunSuite {
  private def fixture(): Scaffold = {
    new Scaffold(Vector("answer" -> true, "constant" -> true),
      Vector("answer" -> Expr.symbol('a'), "constant" -> Expr.TRUE), Vector.empty, "answer")
  }

  test("saved source resumes without rebuilding") {
    val folder = Files.createTempDirectory("generate-window-pal-")
    val original = folder.resolve("original.peg")
    val resumed = folder.resolve("resumed.peg")
    val saved = folder.resolve("source.sca")
    val machine = fixture()
    GenerateWindowPal.run(GenerateWindowPal.Options(original, checkpoint = Some(saved)), Some(() => machine))
    assertEquals(Files.readString(original), machine.compile())
    GenerateWindowPal.run(GenerateWindowPal.Options(resumed, resume = Some(saved)),
      Some(() => fail("resume rebuilt the source")))
    assertEquals(Files.readString(resumed), machine.compile())
    GenerateWindowPal.run(GenerateWindowPal.Options(resumed, resume = Some(saved), skipOptimize = false),
      Some(() => fail("resume rebuilt the source")))
    assertEquals(Files.readString(resumed), ScaffoldOptimize.optimize(machine)._1.compile())
  }

  test("CLI option parsing preserves direct emission default") {
    val output = Files.createTempFile("window-pal-options-", ".peg")
    assert(GenerateWindowPal.parse(Array(output.toString)).skipOptimize)
    assert(!GenerateWindowPal.parse(Array(output.toString, "--optimize")).skipOptimize)
    intercept[IllegalArgumentException] {
      GenerateWindowPal.parse(Array("--optimize", output.toString, "--skip-optimize"))
    }
  }

  test("--verified selects the Lean-verified quanta, above the derived ones") {
    val output = Files.createTempFile("window-pal-options-", ".peg")
    assertEquals(GenerateWindowPal.parse(Array(output.toString)).rates, GsBatchClock.DEFAULT_BATCH)
    val verified = GenerateWindowPal.parse(Array("--verified", output.toString)).rates
    assertEquals(verified, GsBatchClock.VERIFIED_BATCH)
    assertEquals((verified.matching, verified.flags), (2048, 32768))
    assertEquals(verified.k, GsBatchClock.DEFAULT_BATCH.k)
    assert(verified.matching >= GsBatchClock.DEFAULT_BATCH.matching)
    assert(verified.flags >= GsBatchClock.DEFAULT_BATCH.flags)
  }
}
