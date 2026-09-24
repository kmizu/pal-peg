package pal

/** Test helper: enumerating the words over an alphabet in lexicographic order. */
object Words {

  /** Every word of exactly `repeat` characters over `alphabet`, in lexicographic (product) order. */
  def product(alphabet: String, repeat: Int): Iterator[String] = {
    if (repeat == 0) {
      Iterator.single("")
    } else {
      product(alphabet, repeat - 1).flatMap(prefix => alphabet.iterator.map(c => prefix + c))
    }
  }

  /** Every word of length 0 to `limit` inclusive. */
  def upTo(alphabet: String, limit: Int): Iterator[String] = {
    (0 to limit).iterator.flatMap(n => product(alphabet, n))
  }

  /** Every word of exactly `size` characters over `alphabet`, as a vector. */
  def ofLength(alphabet: String, size: Int): Vector[String] = product(alphabet, size).toVector

  /** Every word of length `0 until sizes`. */
  def below(alphabet: String, sizes: Int): Vector[String] = (0 until sizes).toVector.flatMap(ofLength(alphabet, _))
}
