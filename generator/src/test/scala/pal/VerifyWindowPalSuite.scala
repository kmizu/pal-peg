package pal

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions

import VerifyWindowPal.*

/** The runner is a stand-in shell script that prints fixed report lines and exits
  * with a given code.
  */
class VerifyWindowPalSuite extends munit.FunSuite {

  private val directory = FunFixture[Path](
    setup = _ => {
      val root = Files.createTempDirectory("verify-window-pal")
      Files.writeString(root.resolve("input.peg"), "S <- !.\n")
      Files.writeString(root.resolve("run.json"), "{\"status\":\"passed\",\"checked\":999}\n")
      root
    },
    teardown = root => {
      Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
    }
  )

  private def grammarOf(root: Path): Path = root.resolve("input.peg")
  private def runnerOf(root: Path): Path = root.resolve("runner")
  private def logOf(root: Path): Path = root.resolve("run.log")
  private def manifestOf(root: Path): Path = root.resolve("run.json")

  /** A runner that ignores its arguments, prints `reports` and exits with `code`. */
  private def installRunner(root: Path, reports: String, code: Int): Unit = {
    val reportsFile = root.resolve("reports.txt")
    Files.writeString(reportsFile, reports, StandardCharsets.UTF_8)
    val script = s"#!/bin/sh\ncat '${reportsFile}'\nexit $code\n"
    Files.writeString(runnerOf(root), script)
    Files.setPosixFilePermissions(runnerOf(root), PosixFilePermissions.fromString("rwxr-xr-x"))
  }

  private def runReports(root: Path, reports: String, code: Int = 0): Manifest = {
    installRunner(root, reports, code)
    verify(grammarOf(root), runnerOf(root), Vector("", "ab"), logOf(root), 1, report = _ => ())
  }

  private def report(root: Path): Map[String, String] = {
    // The manifest is flat JSON written by `renderJson(_, Some(2))`; read it back field by field.
    val text = Files.readString(manifestOf(root))
    val entry = "\\s*\"([^\"]+)\": (.*?),?\\n".r
    entry.findAllMatchIn(text).map(m => m.group(1) -> m.group(2)).toMap
  }

  private def str(value: Json): String = value match {
    case Json.Str(s) => s
    case other => other.toString
  }

  directory.test("success requires all unchanged inputs") { root =>
    val result = runReports(root, "match\t\"\"\ttrue\tcharacters=0\trepeat=1\n" +
      "match\t\"ab\"\tfalse\tcharacters=2\trepeat=1\n")
    assertEquals((str(result("status")), result("checked")), ("passed", Json.Int(2)))
    assertEquals(str(result("sha256")).length, 64)
    assertEquals(result("returncode"), Json.Int(0))
    assert(!result.contains("current_word"))
    assertEquals(report(root)("status"), "\"passed\"")
  }

  directory.test("counterexample replaces old success") { root =>
    val error = intercept[AssertionError] {
      runReports(root, "match\t\"\"\ttrue\tcharacters=0\trepeat=1\n" +
        "match\t\"ab\"\ttrue\tcharacters=2\trepeat=1\n")
    }
    assertEquals(error.getMessage,
      "word 'ab' (expected false): report 'match\\t\"ab\"\\ttrue\\tcharacters=2\\trepeat=1'")
    val result = report(root)
    assertEquals((result("status"), result("checked"), result("current_word")), ("\"failed\"", "1", "\"ab\""))
    assert(Files.readString(logOf(root)).contains("\"ab\""))
    assert(result("error").startsWith("\"AssertionError: word 'ab'"), result("error"))
  }

  directory.test("truncated or repeated input reports fail") { root =>
    for (reports <- Vector("", "match\t\"\"\ttrue\tcharacters=0\trepeat=2\n")) {
      val error = intercept[Throwable](runReports(root, reports))
      assert(error.isInstanceOf[RuntimeException] || error.isInstanceOf[AssertionError], error.toString)
      assertEquals(report(root)("status"), "\"failed\"", reports)
    }
    // The JSON encoding escapes the backslash of the quoted tab and the quotes of the report
    assertEquals(report(root)("error"), "\"AssertionError: word '' (expected true): report 'match\\\\t\\\"\\\"\\\\ttrue\\\\tcharacters=0\\\\trepeat=2'\"")
    intercept[RuntimeException](runReports(root, ""))
    assertEquals(report(root)("error"), "\"RuntimeException: runner exited with 0; checked 0/2\"")
  }

  directory.test("launch failure replaces old success") { root =>
    val error = intercept[ToolError] {
      verify(grammarOf(root), runnerOf(root), Vector(""), logOf(root), 0, report = _ => ())
    }
    assertEquals(error.kind, "NoSuchFile")
    val result = report(root)
    assertEquals(result("status"), "\"failed\"")
    assert(result("error").contains("No such file or directory"), result("error"))
    assert(result("error").contains("runner"), result("error"))
    assert(!result.contains("returncode"))
  }

  directory.test("output collision does not overwrite grammar") { root =>
    val before = Files.readAllBytes(grammarOf(root))
    intercept[IllegalArgumentException] {
      verify(grammarOf(root), runnerOf(root), Vector(""), grammarOf(root), 0, report = _ => ())
    }
    assertEquals(Files.readAllBytes(grammarOf(root)).toVector, before.toVector)
  }

  test("renderJson writes compact and indented JSON objects") {
    val fields = Vector("status" -> Json.Str("running"), "checked" -> Json.Int(0), "seconds" -> Json.Num(0.5), "ok" -> Json.Bool(true))
    assertEquals(renderJson(fields), "{\"status\": \"running\", \"checked\": 0, \"seconds\": 0.5, \"ok\": true}")
    assertEquals(renderJson(fields, Some(2)), "{\n  \"status\": \"running\",\n  \"checked\": 0,\n  \"seconds\": 0.5,\n  \"ok\": true\n}")
    assertEquals(renderJson(Vector.empty, Some(2)), "{}")
  }

  test("decodeJsonString decodes JSON string literals") {
    assertEquals(decodeJsonString("\"ab\""), Some("ab"))
    assertEquals(decodeJsonString("\"a\\tb\\u0041\""), Some("a\tbA"))
    assertEquals(decodeJsonString("5"), None)
    intercept[ToolError](decodeJsonString("\"unterminated"))
    intercept[ToolError](decodeJsonString(""))
  }

  test("parseArgs parses the command line") {
    assertEquals(parseArgs(Vector("g.peg", "--runner", "r", "--log", "l")),
      Right(Config(Path.of("g.peg"), Path.of("r"), 5, Path.of("l"))))
    assertEquals(parseArgs(Vector("--runner=r", "--max-length", "3", "--log=l", "g.peg")),
      Right(Config(Path.of("g.peg"), Path.of("r"), 3, Path.of("l"))))
    assertEquals(parseArgs(Vector("g.peg", "--runner", "r", "--log", "l", "--max-length", "13")),
      Left("the exhaustive test range must be between 0 and 12"))
    assertEquals(parseArgs(Vector("g.peg")), Left("missing required arguments: --runner, --log"))
    assert(parseArgs(Vector("g.peg", "--runner", "r", "--log", "l", "--max-length", "x")).isLeft)
  }

  test("withSuffix replaces or appends the file suffix") {
    assertEquals(withSuffix(Path.of("/x/run.log"), ".json"), Path.of("/x/run.json"))
    assertEquals(withSuffix(Path.of("/x/run"), ".json"), Path.of("/x/run.json"))
  }
}
