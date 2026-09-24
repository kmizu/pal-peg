package pal

import java.math.{BigDecimal => JBigDecimal, MathContext, RoundingMode}

/** Formatting helpers: fixed-point and shortest round-trip float formatting,
  * repr-like string quoting, bracketed lists and JSON string literals.
  *
  * 浮動小数の桁は `java.math.BigDecimal` で求める（正確な 2 進値からの丸めと、最短往復桁の
  * 探索）。JDK の `Double.toString` には依存しない（JDK 19 未満は最短桁を出さず、19 以降も
  * 非正規化数を 2 桁以上で出す）。頼るのは `BigDecimal.doubleValue` が正しく丸めることだけで、
  * これは JDK 8 以降で保証されている。
  */
object Render {

  private def negative(x: Double): Boolean = java.lang.Double.doubleToRawLongBits(x) < 0

  /** `x` を小数点以下 `digits` 桁で書く。2 進の正確な値を丸め（同点は偶数へ）、負の値と
    * `-0.0` は符号を保つ（`fixed(-0.0, 2) == "-0.00"`）。NaN と無限大は `nan` / `inf` / `-inf`。
    */
  def fixed(x: Double, digits: Int): String = {
    if (x.isNaN) { return "nan" }
    if (x.isInfinite) { return if (x > 0) "inf" else "-inf" }
    val magnitude = new JBigDecimal(math.abs(x)).setScale(digits, RoundingMode.HALF_EVEN).toPlainString
    if (negative(x)) "-" + magnitude else magnitude
  }

  /** The shortest decimal digit string that parses back to `x` (`x` finite, > 0), as
    * (digits, decpt) with `x = 0.d1 d2 ... dn × 10^decpt`.  Among several shortest strings
    * the one closest to `x` wins (David Gay's mode 0).
    */
  private def shortestDigits(x: Double): (String, Int) = {
    val exact = new JBigDecimal(x)
    var precision = 1
    var chosen: JBigDecimal = null
    while (chosen == null) {
      val lo = exact.round(new MathContext(precision, RoundingMode.FLOOR))
      val hi = exact.round(new MathContext(precision, RoundingMode.CEILING))
      val loOk = lo.doubleValue() == x
      val hiOk = hi.doubleValue() == x
      if (loOk && hiOk) {
        val gap = exact.subtract(lo).compareTo(hi.subtract(exact))
        chosen =
          if (gap < 0) lo
          else if (gap > 0) hi
          else if (lo.unscaledValue().testBit(0)) hi else lo // a tie: even last digit
      } else if (loOk) {
        chosen = lo
      } else if (hiOk) {
        chosen = hi
      }
      precision += 1 // 17 significant digits always round-trip, so this terminates
    }
    val stripped = chosen.stripTrailingZeros()
    val digits = stripped.unscaledValue().toString
    (digits, digits.length - stripped.scale())
  }

  /** The shortest round-trip decimal form of `x`: positional when the decimal exponent
    * is in [-4, 16), otherwise scientific with a signed exponent of at least two digits
    * (`1e+16`, `1e-05`); integral values keep a trailing `.0`.
    */
  def floatRepr(x: Double): String = {
    if (x.isNaN) { return "nan" }
    if (x.isInfinite) { return if (x > 0) "inf" else "-inf" }
    if (x == 0.0) { return if (negative(x)) "-0.0" else "0.0" }
    val (digits, decpt) = shortestDigits(math.abs(x))
    val body =
      if (decpt <= -4 || decpt > 16) {
        val exp = decpt - 1
        val rest = if (digits.length > 1) "." + digits.substring(1) else ""
        val expSign = if (exp < 0) "-" else "+"
        f"${digits.charAt(0)}${rest}e$expSign${math.abs(exp)}%02d"
      } else if (decpt <= 0) {
        "0." + ("0" * (-decpt)) + digits
      } else if (decpt >= digits.length) {
        digits + ("0" * (decpt - digits.length)) + ".0"
      } else {
        digits.substring(0, decpt) + "." + digits.substring(decpt)
      }
    if (negative(x)) "-" + body else body
  }

  /** `s` quoted repr-style: single quotes (double quotes when `s` contains `'` but no
    * `"`), with the quote, `\\`, `\n`, `\r`, `\t` escaped and other control characters
    * written as `\xNN`.
    */
  def strRepr(s: String): String = {
    val quote = if (s.contains('\'') && !s.contains('"')) '"' else '\''
    val sb = new StringBuilder
    sb.append(quote)
    s.foreach { c =>
      if (c == quote || c == '\\') { sb.append('\\').append(c) }
      else if (c == '\n') { sb.append("\\n") }
      else if (c == '\r') { sb.append("\\r") }
      else if (c == '\t') { sb.append("\\t") }
      else if (c < 0x20 || c == 0x7f) { sb.append(f"\\x${c.toInt}%02x") }
      else { sb.append(c) }
    }
    sb.append(quote)
    sb.toString
  }

  /** A byte string (given as Latin-1, 1 文字 = 1 バイト) quoted as `b'...'`: like
    * [[strRepr]], but every byte outside printable ASCII is written as `\xNN`.
    */
  def bytesRepr(s: String): String = {
    val quote = if (s.contains('\'') && !s.contains('"')) '"' else '\''
    val sb = new StringBuilder
    sb.append('b').append(quote)
    s.foreach { c =>
      if (c == quote || c == '\\') { sb.append('\\').append(c) }
      else if (c == '\n') { sb.append("\\n") }
      else if (c == '\r') { sb.append("\\r") }
      else if (c == '\t') { sb.append("\\t") }
      else if (c < 0x20 || c >= 0x7f) { sb.append(f"\\x${c.toInt}%02x") }
      else { sb.append(c) }
    }
    sb.append(quote)
    sb.toString
  }

  /** `[a, b, c]` from the already rendered elements. */
  def listRepr(items: Seq[String]): String = items.mkString("[", ", ", "]")

  /** `[1, 2, 3]` for a list of integers. */
  def intListRepr(items: Seq[Int]): String = listRepr(items.map(_.toString))

  /** A JSON string literal.  With `ensureAscii = true` every character outside
    * `' '..'~'` becomes `\uXXXX` (UTF-16 units, so astral characters become surrogate
    * pairs); with `false` only control characters, `"` and `\` are escaped.
    */
  def jsonString(s: String, ensureAscii: Boolean = false): String = {
    val sb = new StringBuilder
    sb.append('"')
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case '\b' => sb.append("\\b")
      case '\f' => sb.append("\\f")
      case c if c < 0x20 || (ensureAscii && c > 0x7e) => sb.append(f"\\u${c.toInt}%04x")
      case c => sb.append(c)
    }
    sb.append('"')
    sb.toString
  }
}
