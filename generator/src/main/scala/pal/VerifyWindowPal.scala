package pal

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.{AccessDeniedException, Files, NoSuchFileException, Path}
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import scala.collection.immutable.VectorMap
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Check a concrete ordinary PEG on unchanged binary words with Rust.
  *
  * The runner receives words directly, without --repeat or a work protocol.
  * The complete runner output and a compact verification manifest are retained.
  * The manifest status is running, passed, or failed; only passed is evidence of
  * a completed check. Failures retain the checked count and offending report.
  *
  * A failure is rethrown after it is recorded; the manifest's `error` field holds a
  * kind label and the message (see [[describe]]).
  */
object VerifyWindowPal {

  // ------------------------------------------------------------------ manifest JSON

  /** The JSON values a manifest holds (all flat). */
  sealed trait Json
  object Json {
    final case class Str(value: String) extends Json
    final case class Int(value: Long) extends Json
    final case class Num(value: Double) extends Json
    final case class Bool(value: Boolean) extends Json
  }

  private def jsonValue(value: Json): String = value match {
    case Json.Str(s)  => Render.jsonString(s, ensureAscii = true) // ASCII-only output
    case Json.Int(n)  => n.toString
    case Json.Num(d)  => Render.floatRepr(d)
    case Json.Bool(b) => if (b) "true" else "false"
  }

  /** A JSON object: one entry per line, indented by `indent` spaces, when `indent` is
    * given; otherwise the compact one-line form with `, ` and `: ` separators.
    */
  def renderJson(fields: Iterable[(String, Json)], indent: Option[Int] = None): String = {
    val entries = fields.map { case (k, v) => Render.jsonString(k, ensureAscii = true) + ": " + jsonValue(v) }
    indent match {
      case Some(width) if entries.nonEmpty =>
        val pad = " " * width
        entries.mkString("{\n" + pad, ",\n" + pad, "\n}")
      case _ => entries.mkString("{", ", ", "}")
    }
  }

  /** The manifest written next to the log; keeps insertion order. */
  type Manifest = VectorMap[String, Json]

  // ------------------------------------------------------------------ words

  /** The word list of `main`: all binary words up to `maxLength`, then hand-picked
    * extras; duplicates removed keeping the first occurrence.
    */
  def words(maxLength: Int): Vector[String] = {
    val exhaustive = (0 to maxLength).flatMap(n => Twoway.product("ab", n))
    val extras = Vector("abbba", "abbbba", "ababa", "abaaba", "abbaabba", "abbaabab",
      "abbbbbbbbbbba", "abbbbbbbbbbab", "a" * 17, "a" * 33,
      ("a" * 8 + "b") * 2 + "a" * 8, "ab" * 9,
      "abba" * 8, "abba" * 7 + "abab", "#", "a!a", "a.a", "abcba")
    (exhaustive ++ extras).distinct.toVector
  }

  // ------------------------------------------------------------------ errors

  /** A failure with its own kind label for the manifest's `error` line. */
  final class ToolError(val kind: String, message: String) extends RuntimeException(message)

  private def invalid(message: String): Nothing = throw new IllegalArgumentException(message)

  /** The error for a missing file, with the path quoted. */
  private def noSuchFile(path: Path): ToolError = {
    new ToolError("NoSuchFile", s"No such file or directory: ${Render.strRepr(path.toString)}")
  }

  /** The tool error for a failed file operation on `path`. */
  private def fileError(error: IOException, path: Path): ToolError = {
    val shown = Render.strRepr(path.toString)
    error match {
      case _: NoSuchFileException => noSuchFile(path)
      case _: AccessDeniedException => new ToolError("AccessDenied", s"Permission denied: $shown")
      case _ if Files.isDirectory(path) => // the JDK's message is the OS strerror in the current locale
        new ToolError("IsDirectory", s"Is a directory: $shown")
      case e => new ToolError("FileError", Option(e.getMessage).getOrElse(""))
    }
  }

  /** Run a file operation on `path`, translating an `IOException` into a [[ToolError]]. */
  private def onFile[A](path: Path)(body: => A): A = {
    try { body } catch { case e: IOException => throw fileError(e, path) }
  }

  /** The manifest's `error` line: `<kind>: <message>`, where the kind is the
    * [[ToolError.kind]] of a tool error and the exception's class name otherwise.
    */
  def describe(error: Throwable): String = {
    val kind = error match {
      case e: ToolError => e.kind
      case e            => e.getClass.getSimpleName
    }
    s"$kind: ${Option(error.getMessage).getOrElse("")}"
  }

  /** The message of a report that disagrees with its word. */
  private def mismatchMessage(word: String, expected: Boolean, report: String): String = {
    s"word ${Render.strRepr(word)} (expected $expected): report ${Render.strRepr(report)}"
  }

  // ------------------------------------------------------------------ runner output

  /** Strip trailing whitespace. */
  private def rstrip(s: String): String = {
    var end = s.length
    while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) { end -= 1 }
    s.substring(0, end)
  }

  private def invalidJson(message: String): Nothing = throw new ToolError("InvalidJson", message)

  /** Decode the word field: `Some(string)` for a JSON string literal, `None` for any
    * other value (which then simply differs from the word). Empty text, an unterminated
    * string, a bad escape, a raw control character or trailing data raise a [[ToolError]].
    */
  def decodeJsonString(text: String): Option[String] = {
    val t = text.trim
    if (!t.startsWith("\"")) {
      if (t.isEmpty) { invalidJson("expected a JSON value") }
      return None
    }
    val sb = new StringBuilder
    var i = 1
    var closed = false
    while (!closed) {
      if (i >= t.length) { invalidJson("unterminated string") }
      val c = t.charAt(i)
      if (c == '"') { closed = true; i += 1 }
      else if (c == '\\') {
        if (i + 1 >= t.length) { invalidJson("unterminated string") }
        t.charAt(i + 1) match {
          case '"'  => sb.append('"')
          case '\\' => sb.append('\\')
          case '/'  => sb.append('/')
          case 'b'  => sb.append('\b')
          case 'f'  => sb.append('\f')
          case 'n'  => sb.append('\n')
          case 'r'  => sb.append('\r')
          case 't'  => sb.append('\t')
          case 'u' =>
            if (i + 5 >= t.length) { invalidJson(s"invalid \\uXXXX escape at char $i") }
            sb.append(Integer.parseInt(t.substring(i + 2, i + 6), 16).toChar)
            i += 4
          case _ => invalidJson(s"invalid escape at char $i")
        }
        i += 2
      } else if (c < 0x20) {
        invalidJson(s"invalid control character at char $i")
      } else { sb.append(c); i += 1 }
    }
    if (i != t.length) { invalidJson(s"extra data at char $i") }
    Some(sb.toString)
  }

  /** The `key=value` fields of a report as a map (each field is split at its first `=`). */
  private def details(fields: Seq[String]): Map[String, String] = {
    fields.zipWithIndex.map { case (field, i) =>
      field.indexOf('=') match {
        case -1 => invalid(s"report field #$i is not of the form key=value: ${Render.strRepr(field)}")
        case at => field.substring(0, at) -> field.substring(at + 1)
      }
    }.toMap
  }

  /** A decimal integer; surrounding whitespace, a leading `+`/`-` and underscores are accepted. */
  private def parseCount(text: String): Int = {
    try { text.trim.replace("_", "").toInt }
    catch { case _: NumberFormatException => invalid(s"not a decimal integer: ${Render.strRepr(text)}") }
  }

  /** The value of a required report field. */
  private def requiredField(info: Map[String, String], key: String): String = {
    info.getOrElse(key, throw new NoSuchElementException(s"report has no '$key' field"))
  }

  /** Check one `match` report line against the word it must describe. */
  private def checkReport(line: String, word: String): Unit = {
    val fields = rstrip(line).split("\t", -1).toVector
    if (fields.length < 3) { throw new IndexOutOfBoundsException("report line has fewer than 3 fields") }
    val actual = fields(2) == "true"
    val expected = word.forall(c => c == 'a' || c == 'b') && word == word.reverse
    val info = details(fields.drop(3))
    val bad = !(fields(2) == "true" || fields(2) == "false") ||
      !decodeJsonString(fields(1)).contains(word) ||
      actual != expected ||
      parseCount(requiredField(info, "characters")) != word.length ||
      requiredField(info, "repeat") != "1"
    if (bad) { throw new AssertionError(mismatchMessage(word, expected, rstrip(line))) }
  }

  /** Read one line including its terminator, translating `\r\n` and `\r` to `\n`;
    * `None` at end of stream.
    */
  private def readLine(reader: BufferedReader): Option[String] = {
    val sb = new StringBuilder
    var ch = reader.read()
    while (ch != -1) {
      if (ch == '\n') { return Some(sb.append('\n').toString) }
      if (ch == '\r') {
        reader.mark(1)
        val next = reader.read()
        if (next != '\n' && next != -1) { reader.reset() }
        return Some(sb.append('\n').toString)
      }
      sb.append(ch.toChar)
      ch = reader.read()
    }
    if (sb.isEmpty) None else Some(sb.toString)
  }

  private def sha256Hex(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(Files.readAllBytes(path))
    digest.digest().map(b => f"$b%02x").mkString
  }

  private def launch(runner: Path, grammar: Path, words: Seq[String]): Process = {
    val command = (runner.toString +: grammar.toString +: words).asJava
    try {
      new ProcessBuilder(command).redirectErrorStream(true).start()
    } catch {
      case _: IOException if !Files.exists(runner) =>
        throw noSuchFile(runner)
    }
  }

  // ------------------------------------------------------------------ verify

  /** Run `runner grammar words...`, log its output, and write the manifest.  `report`
    * receives the progress lines and the final compact manifest (printed to stdout by
    * default).  Raises on any failure after recording it in the manifest.
    */
  def verify(grammar: Path, runner: Path, words: Seq[String], logPath: Path, maxLength: Int,
      report: String => Unit = line => Console.out.println(line)): Manifest = {
    val manifestPath = withSuffix(logPath, ".json")
    val paths = Vector(grammar, runner, logPath, manifestPath).map(_.toAbsolutePath.normalize)
    if (paths.distinct.size != paths.size) {
      invalid("grammar, runner, log and manifest must use distinct paths")
    }
    val started = System.nanoTime()
    var checked = 0
    var process: Process = null
    val manifest = mutable.LinkedHashMap[String, Json](
      "status" -> Json.Str("running"), "grammar" -> Json.Str(grammar.toString), "checked" -> Json.Int(0),
      "total" -> Json.Int(words.size), "exhaustive_max_length" -> Json.Int(maxLength),
      "input_transform" -> Json.Str("none"), "log" -> Json.Str(logPath.toString))

    def save(): Unit = {
      manifest("checked") = Json.Int(checked)
      manifest("seconds") = Json.Num((System.nanoTime() - started) / 1e9)
      onFile(manifestPath) { Files.writeString(manifestPath, renderJson(manifest, Some(2)) + "\n", StandardCharsets.UTF_8) }
      ()
    }

    def handleLine(log: BufferedWriter, line: String): Unit = {
      log.write(line)
      log.flush()
      if (line.startsWith("loaded\t")) { report(rstrip(line)) }
      if (line.startsWith("match\t")) {
        manifest("last_report") = Json.Str(rstrip(line))
        if (checked >= words.size) { invalid("runner reported more matches than requested") }
        val word = words(checked)
        manifest("current_word") = Json.Str(word)
        checkReport(line, word)
        checked += 1
        if (checked % 16 == 0 || checked == words.size) { report(s"checked $checked/${words.size} unchanged inputs") }
      }
    }

    // Invalidate an earlier success before hashing, launching, or parsing output.
    save()
    try {
      val log = onFile(logPath)(Files.newBufferedWriter(logPath, StandardCharsets.UTF_8))
      try {
        onFile(grammar) {
          manifest("sha256") = Json.Str(sha256Hex(grammar))
          manifest("bytes") = Json.Int(Files.size(grammar))
        }
        save()
        process = launch(runner, grammar, words)
        val reader = new BufferedReader(new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
        var line = readLine(reader)
        while (line.isDefined) {
          handleLine(log, line.get)
          line = readLine(reader)
        }
      } finally {
        log.close()
      }
      val code = process.waitFor()
      if (code != 0 || checked != words.size) {
        throw new RuntimeException(s"runner exited with $code; checked $checked/${words.size}")
      }
      manifest("status") = Json.Str("passed")
      manifest.remove("current_word")
      manifest.remove("last_report")
    } catch {
      case error: Throwable =>
        manifest("status") = Json.Str("failed")
        manifest("error") = Json.Str(describe(error))
        throw error
    } finally {
      try {
        if (process != null) {
          if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
              process.destroyForcibly()
              process.waitFor()
            }
          }
          manifest("returncode") = Json.Int(process.exitValue())
          process.getInputStream.close()
        }
      } finally {
        save()
      }
    }
    report(renderJson(manifest))
    VectorMap.from(manifest)
  }

  /** Replace the final suffix of the file name (or append one when there is none). */
  def withSuffix(path: Path, suffix: String): Path = {
    val name = path.getFileName.toString
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    path.resolveSibling(stem + suffix)
  }

  // ------------------------------------------------------------------ CLI

  final case class Config(grammar: Path, runner: Path, maxLength: Int, log: Path)

  /** The command line: `grammar --runner R [--max-length N] --log L`; `--opt=value` is
    * accepted too.
    */
  def parseArgs(args: Seq[String]): Either[String, Config] = {
    var grammar: Option[String] = None
    var runner: Option[String] = None
    var log: Option[String] = None
    var maxLength = 5
    var error: Option[String] = None
    var rest = args.toList
    while (rest.nonEmpty && error.isEmpty) {
      rest match {
        case "--runner" :: v :: tail => runner = Some(v); rest = tail
        case "--log" :: v :: tail => log = Some(v); rest = tail
        case "--max-length" :: v :: tail =>
          rest = tail
          v.toIntOption match {
            case Some(n) => maxLength = n
            case None => error = Some(s"--max-length: not an integer: ${Render.strRepr(v)}")
          }
        case opt :: tail if opt.startsWith("--") && opt.contains('=') =>
          val at = opt.indexOf('=')
          rest = opt.substring(0, at) :: opt.substring(at + 1) :: tail
        case opt :: _ if opt.startsWith("--") =>
          error = Some(s"unrecognized argument: $opt")
        case positional :: tail =>
          if (grammar.isDefined) { error = Some(s"unrecognized argument: $positional") }
          grammar = Some(positional); rest = tail
        case Nil => ()
      }
    }
    val missing = Vector(("grammar", grammar), ("--runner", runner), ("--log", log)).collect {
      case (name, None) => name
    }
    error match {
      case Some(message) => Left(message)
      case None if missing.nonEmpty => Left(s"missing required arguments: ${missing.mkString(", ")}")
      case None if maxLength < 0 || maxLength > 12 => Left("the exhaustive test range must be between 0 and 12")
      case None => Right(Config(Path.of(grammar.get), Path.of(runner.get), maxLength, Path.of(log.get)))
    }
  }

  def main(args: Array[String]): Unit = {
    parseArgs(args.toSeq) match {
      case Left(message) =>
        Console.err.println("usage: VerifyWindowPal grammar --runner RUNNER [--max-length MAX_LENGTH] --log LOG")
        Console.err.println(s"VerifyWindowPal: error: $message")
        sys.exit(2)
      case Right(config) =>
        verify(config.grammar, config.runner, words(config.maxLength), config.log, config.maxLength)
        ()
    }
  }
}
