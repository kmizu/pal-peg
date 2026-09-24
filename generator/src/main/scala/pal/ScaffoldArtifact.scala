package pal

import java.io.{BufferedInputStream, BufferedOutputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
import scala.collection.immutable.VectorMap
import scala.collection.mutable

/** Flat, data-only checkpoints for large finite scaffold expression DAGs.
  *
  * Nodes are written in topological order with integer child references. Loading
  * does not recurse through the expression graph or execute construction code.
  * These files are compiler intermediates, not ordinary PEG deliverables.
  *
  * Each record is serialized with `ArtifactCodec` below, a small type-tagged
  * binary format for the value kinds involved (none, Boolean, integer, string,
  * sequence, map). A file whose name ends in `.gz` holds the same stream
  * compressed with `java.util.zip`'s GZIP streams.
  */
object ScaffoldArtifact {
  import Expr.*

  val MAGIC: Array[Byte] = "finite-scaffold-dag-1\n".getBytes(StandardCharsets.US_ASCII)

  /** Operation tags in artifact order; a row starts with the tag's index. */
  val OPS: Vector[String] = Vector("const", "symbol", "self", "null", "old", "exists", "pointer",
                                   "read", "edge", "not", "present", "and", "or", "select")

  private val CHILD_FIRST = Set("read", "edge")
  private val ALL_CHILDREN = Set("not", "present", "and", "or", "select")

  /** Direct sub-expressions: the computed pointer of a read/edge, else every argument. */
  def children(expression: Expr): Vector[Expr] = ScaffoldOptimize.children(expression)

  private def suffix(path: Path): String = {
    val name = path.getFileName.toString
    val dot = name.lastIndexOf('.')
    if (dot > 0) { name.substring(dot) } else { "" }
  }

  private def isGzip(path: Path): Boolean = suffix(path) == ".gz"

  /** Write `machine` (and `metadata`, any value `ArtifactCodec` can encode) to `path`; returns the node count. */
  def write(machine: Scaffold, path: Path, metadata: Any = None): Int = {
    val temporary = path.resolveSibling(path.getFileName.toString + ".partial")
    val seen = new java.util.IdentityHashMap[Expr, Int]()
    val raw = Files.newOutputStream(temporary)
    val stream = new BufferedOutputStream(if (isGzip(path)) { new GZIPOutputStream(raw) } else { raw })
    try {
      stream.write(MAGIC)
      val writer = new ArtifactCodec.Writer(stream)
      writer.dump(Vector(machine.initial, machine.accepting, machine.alphabet.toVector, metadata))
      for (expression <- machine.labels.values ++ machine.pointers.values) {
        writeNodes(expression, seen, writer)
      }
      writer.dump(None)
      writer.dump(Vector(
        machine.labels.map { case (key, expr) => key -> seen.get(expr) },
        machine.pointers.map { case (key, expr) => key -> seen.get(expr) }
      ))
    } finally {
      stream.close()
    }
    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    seen.size
  }

  /** Emit every not yet written node below `expression`, children before parents. */
  private def writeNodes(expression: Expr, seen: java.util.IdentityHashMap[Expr, Int], writer: ArtifactCodec.Writer): Unit = {
    val work = mutable.Stack[(Expr, Boolean)]((expression, false))
    while (work.nonEmpty) {
      val (current, done) = work.pop()
      if (!seen.containsKey(current)) {
        if (!done) {
          work.push((current, true))
          children(current).foreach(child => work.push((child, false)))
        } else {
          writer.dump(OPS.indexOf(current.tag) +: rowArguments(current, seen))
          seen.put(current, seen.size)
        }
      }
    }
  }

  /** The node's arguments with child expressions replaced by their row indices. */
  private def rowArguments(current: Expr, seen: java.util.IdentityHashMap[Expr, Int]): Vector[Any] = {
    current match {
      case Read(target, label) => Vector(seen.get(target), label)
      case Edge(target, field) => Vector(seen.get(target), field)
      case _ if ALL_CHILDREN.contains(current.tag) => children(current).map(child => seen.get(child))
      case _ => current.args
    }
  }

  private def reject(message: String): Nothing = throw new IllegalArgumentException(message)

  /** Load a checkpoint written by `write`. */
  def read(path: Path): (Scaffold, Any) = {
    val raw = Files.newInputStream(path)
    val stream = new BufferedInputStream(if (isGzip(path)) { new GZIPInputStream(raw) } else { raw })
    try {
      val head = stream.readNBytes(MAGIC.length)
      if (!java.util.Arrays.equals(head, MAGIC)) { reject("not a finite scaffold DAG artifact") }
      val reader = new ArtifactCodec.Reader(stream)
      val (initial, accepting, alphabet, metadata) = reader.load() match {
        case Vector(initial: collection.Map[?, ?], accepting: String, alphabet, metadata) =>
          (initial, accepting, alphabet, metadata)
        case _ => reject("invalid scaffold artifact header")
      }
      val nodes = mutable.ArrayBuffer.empty[Expr]
      def child(index: Any): Expr = {
        index match {
          case i: Int if i >= 0 && i < nodes.length => nodes(i)
          case _ => reject("artifact child must precede its parent")
        }
      }
      var finished = false
      while (!finished) {
        reader.load() match {
          case None => finished = true
          case row: Vector[?] if row.nonEmpty && validOperation(row.head) =>
            val op = OPS(row.head.asInstanceOf[Int])
            val args = row.tail.toVector
            val resolved = if (CHILD_FIRST.contains(op)) {
              child(args.head) +: args.tail
            } else if (ALL_CHILDREN.contains(op)) {
              args.map(child)
            } else {
              args
            }
            nodes += Expr.make(op, resolved)
          case _ => reject("invalid scaffold artifact node")
        }
      }
      val (labels, pointers) = reader.load() match {
        case Vector(labels: collection.Map[?, ?], pointers: collection.Map[?, ?]) => (labels, pointers)
        case _ => reject("invalid scaffold artifact field table")
      }
      if (stream.read() >= 0) { reject("trailing scaffold artifact data") }
      val machine = new Scaffold(
        initial.toVector.map { case (key, value) => (key.asInstanceOf[String], value.asInstanceOf[Boolean]) },
        labels.toVector.map { case (key, index) => (key.asInstanceOf[String], child(index)) },
        pointers.toVector.map { case (key, index) => (key.asInstanceOf[String], child(index)) },
        accepting,
        alphabetText(alphabet)
      )
      (machine, metadata)
    } finally {
      stream.close()
    }
  }

  private def validOperation(value: Any): Boolean = {
    value match {
      case index: Int => index >= 0 && index < OPS.length
      case _ => false
    }
  }

  /** The alphabet is stored as a sequence of one-character strings; a plain string is also accepted. */
  private def alphabetText(value: Any): String = {
    value match {
      case chars: Vector[?] => chars.map(_.toString).mkString
      case text: String => text
      case _ => reject("invalid scaffold artifact alphabet")
    }
  }
}

/** A reader/writer for the type-tagged binary format of scaffold artifacts.
  *
  * Every value starts with a one-byte type code; multi-byte numbers are little-endian.
  * Supported values: `None` (`N`), `Boolean` (`T`/`F`), `Int`/`Long`/`BigInt`
  * (`i` for 32 bits, else `l` with 15-bit digits), `String`/`Char` (`z`/`a` for
  * ASCII, `u` for UTF-8), `Double` (`g`, 8-byte IEEE 754), `Array[Byte]` (`s`),
  * `Seq` (`)`/`(`, loaded as `Vector`) and insertion-ordered `Map` (`{` ... `0`).
  * Bit 0x80 of a type code marks a value that a later `r` code may refer back to by
  * index. The reader resolves such back-references and also accepts the variant
  * codes `A`/`t`/`Z` (strings) and `[` (sequences); the writer never emits them.
  */
object ArtifactCodec {
  private val FLAG_REF = 0x80

  /** The `'0'` code, which terminates a map. */
  private case object NullMarker

  final class Writer(out: OutputStream) {

    def dump(value: Any): Unit = {
      value match {
        case None | null => out.write('N')
        case true => out.write('T')
        case false => out.write('F')
        case c: Char => string(c.toString)
        case s: String => string(s)
        case i: Int => int(BigInt(i))
        case l: Long => int(BigInt(l))
        case b: BigInt => int(b)
        case d: Double =>
          out.write('g')
          val bits = java.lang.Double.doubleToLongBits(d)
          for (i <- 0 until 8) { out.write(((bits >>> (8 * i)) & 0xff).toInt) }
        case bytes: Array[Byte] =>
          out.write('s')
          int32(bytes.length)
          out.write(bytes)
        case map: collection.Map[?, ?] =>
          out.write('{')
          for ((key, item) <- map) {
            dump(key)
            dump(item)
          }
          out.write('0')
        case items: Iterable[?] =>
          if (items.size < 256) {
            out.write(')')
            out.write(items.size)
          } else {
            out.write('(')
            int32(items.size)
          }
          items.foreach(dump)
        case other => throw new IllegalArgumentException(s"value cannot be encoded in an artifact: $other")
      }
    }

    private def int32(value: Int): Unit = {
      for (i <- 0 until 4) { out.write((value >>> (8 * i)) & 0xff) }
    }

    private def int(value: BigInt): Unit = {
      if (value.isValidInt) {
        out.write('i')
        int32(value.toInt)
      } else {
        // Beyond 32 bits: sign-carrying digit count, then 15-bit digits, least significant first.
        val digits = mutable.ArrayBuffer.empty[Int]
        var magnitude = value.abs
        while (magnitude != 0) {
          digits += (magnitude & 0x7fff).toInt
          magnitude >>= 15
        }
        out.write('l')
        int32(if (value.signum < 0) { -digits.length } else { digits.length })
        for (digit <- digits) {
          out.write(digit & 0xff)
          out.write((digit >>> 8) & 0xff)
        }
      }
    }

    private def string(text: String): Unit = {
      if (text.forall(_ < 128)) {
        val ascii = text.getBytes(StandardCharsets.US_ASCII)
        if (ascii.length < 256) {
          out.write('z')
          out.write(ascii.length)
        } else {
          out.write('a')
          int32(ascii.length)
        }
        out.write(ascii)
      } else {
        val utf8 = text.getBytes(StandardCharsets.UTF_8)
        out.write('u')
        int32(utf8.length)
        out.write(utf8)
      }
    }
  }

  final class Reader(in: InputStream) {
    private val refs = mutable.ArrayBuffer.empty[Any]

    private def reject(message: String): Nothing = throw new IllegalArgumentException(message)

    private def byte(): Int = {
      val value = in.read()
      if (value < 0) { reject("artifact data too short") }
      value
    }

    private def int32(): Int = {
      var value = 0
      for (i <- 0 until 4) { value |= byte() << (8 * i) }
      value
    }

    private def bytes(count: Int): Array[Byte] = {
      if (count < 0) { reject("bad artifact data (negative length)") }
      val data = in.readNBytes(count)
      if (data.length != count) { reject("artifact data too short") }
      data
    }

    /** Read one value; a map terminator outside a map is rejected. */
    def load(): Any = {
      value() match {
        case NullMarker => reject("bad artifact data (unexpected map terminator)")
        case other => other
      }
    }

    private def remember(flag: Boolean, value: Any): Any = {
      if (flag) { refs += value }
      value
    }

    private def value(): Any = {
      val code = byte()
      val flag = (code & FLAG_REF) != 0
      (code & ~FLAG_REF).toChar match {
        case '0' => NullMarker
        case 'N' => None
        case 'T' => true
        case 'F' => false
        case 'i' => remember(flag, int32())
        case 'l' => remember(flag, long())
        case 'g' =>
          var bits = 0L
          for (i <- 0 until 8) { bits |= byte().toLong << (8 * i) }
          remember(flag, java.lang.Double.longBitsToDouble(bits))
        case 's' => remember(flag, bytes(int32()))
        case 'a' | 'A' | 't' | 'u' => remember(flag, new String(bytes(int32()), StandardCharsets.UTF_8))
        case 'z' | 'Z' => remember(flag, new String(bytes(byte()), StandardCharsets.US_ASCII))
        case ')' => sequence(flag, byte())
        case '(' | '[' => sequence(flag, int32())
        case '{' => mapping(flag)
        case 'r' =>
          val index = int32()
          if (index < 0 || index >= refs.length) { reject("bad artifact data (invalid reference)") }
          refs(index)
        case other => reject(s"unsupported artifact type code '$other'")
      }
    }

    /** A container's back-reference slot is registered before its items are read. */
    private def reserve(flag: Boolean): Int = {
      if (flag) {
        refs += null
        refs.length - 1
      } else {
        -1
      }
    }

    private def sequence(flag: Boolean, count: Int): Vector[Any] = {
      val slot = reserve(flag)
      val items = Vector.fill(count)(load())
      if (slot >= 0) { refs(slot) = items }
      items
    }

    private def mapping(flag: Boolean): VectorMap[Any, Any] = {
      val slot = reserve(flag)
      val builder = VectorMap.newBuilder[Any, Any]
      var finished = false
      while (!finished) {
        value() match {
          case NullMarker => finished = true
          case key => builder += key -> load()
        }
      }
      val result = builder.result()
      if (slot >= 0) { refs(slot) = result }
      result
    }

    private def long(): Any = {
      val count = int32()
      var magnitude = BigInt(0)
      for (i <- 0 until math.abs(count)) {
        val digit = byte() | (byte() << 8)
        magnitude += BigInt(digit) << (15 * i)
      }
      val value = if (count < 0) { -magnitude } else { magnitude }
      if (value.isValidInt) { value.toInt } else if (value.isValidLong) { value.toLong } else { value }
    }
  }
}
