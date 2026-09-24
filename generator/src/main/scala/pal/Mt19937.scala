package pal

/** 整数シードの決定的な MT19937 乱数生成器。
  *
  * デモやテストの乱数系列をシードで固定するために使う。シードは非負の `Long` で、
  * 下位から 32 bit ワード列に分割して `init_by_array` で状態を初期化する。
  * その上に `random()`（53 bit 精度の浮動小数）、`getrandbits(k)`、棄却法による
  * `randbelow(n)` と、それに基づく `randint` / `randrange` / `choice` / `word` を置く。
  */
final class Mt19937(initialSeed: Long) {

  private val N = 624
  private val M = 397
  private val MatrixA = 0x9908b0df
  private val UpperMask = 0x80000000
  private val LowerMask = 0x7fffffff

  private val mt = new Array[Int](N)
  private var index = N + 1

  seed(initialSeed)

  /** 非負整数 `a` で再シードする。`a` を 32 bit ワード列（下位から）にして
    * `init_by_array` に渡す。`a == 0` はワード `[0]` 1 個。
    */
  def seed(a: Long): Unit = {
    require(a >= 0L, s"Mt19937.seed: negative seed $a not supported")
    val low = (a & 0xffffffffL).toInt
    val high = (a >>> 32).toInt
    val key = if (high == 0) { Array(low) } else { Array(low, high) }
    initByArray(key)
  }

  private def initGenrand(s: Int): Unit = {
    mt(0) = s
    var i = 1
    while (i < N) {
      val prev = mt(i - 1)
      mt(i) = 1812433253 * (prev ^ (prev >>> 30)) + i
      i += 1
    }
    index = N
  }

  private def initByArray(key: Array[Int]): Unit = {
    initGenrand(19650218)
    var i = 1
    var j = 0
    var k = math.max(N, key.length)
    while (k > 0) {
      val prev = mt(i - 1)
      mt(i) = (mt(i) ^ ((prev ^ (prev >>> 30)) * 1664525)) + key(j) + j
      i += 1
      j += 1
      if (i >= N) {
        mt(0) = mt(N - 1)
        i = 1
      }
      if (j >= key.length) {
        j = 0
      }
      k -= 1
    }
    k = N - 1
    while (k > 0) {
      val prev = mt(i - 1)
      mt(i) = (mt(i) ^ ((prev ^ (prev >>> 30)) * 1566083941)) - i
      i += 1
      if (i >= N) {
        mt(0) = mt(N - 1)
        i = 1
      }
      k -= 1
    }
    mt(0) = 0x80000000
    index = N
  }

  private def twist(): Unit = {
    var kk = 0
    while (kk < N - M) {
      val y = (mt(kk) & UpperMask) | (mt(kk + 1) & LowerMask)
      mt(kk) = mt(kk + M) ^ (y >>> 1) ^ (if ((y & 1) != 0) { MatrixA } else { 0 })
      kk += 1
    }
    while (kk < N - 1) {
      val y = (mt(kk) & UpperMask) | (mt(kk + 1) & LowerMask)
      mt(kk) = mt(kk + (M - N)) ^ (y >>> 1) ^ (if ((y & 1) != 0) { MatrixA } else { 0 })
      kk += 1
    }
    val y = (mt(N - 1) & UpperMask) | (mt(0) & LowerMask)
    mt(N - 1) = mt(M - 1) ^ (y >>> 1) ^ (if ((y & 1) != 0) { MatrixA } else { 0 })
    index = 0
  }

  /** `genrand_uint32`: 32 bit の一様乱数（0 以上 2^32 未満）を `Long` で返す。 */
  def genrandUint32(): Long = {
    if (index >= N) {
      twist()
    }
    var y = mt(index)
    index += 1
    y ^= (y >>> 11)
    y ^= (y << 7) & 0x9d2c5680
    y ^= (y << 15) & 0xefc60000
    y ^= (y >>> 18)
    y.toLong & 0xffffffffL
  }

  /** 53 bit 精度の `[0.0, 1.0)` の一様乱数（2 ワードの上位 27 bit と 26 bit から作る）。 */
  def random(): Double = {
    val a = genrandUint32() >>> 5
    val b = genrandUint32() >>> 6
    (a * 67108864.0 + b) * (1.0 / 9007199254740992.0)
  }

  /** `k` bit の一様乱数（`1 <= k <= 64`）。32 bit ワードを下位から詰め、
    * 最後のワードだけ上位ビットを残して下位を落とす。
    */
  def getrandbits(k: Int): Long = {
    require(k >= 1 && k <= 64, s"Mt19937.getrandbits: k=$k out of range")
    if (k <= 32) {
      genrandUint32() >>> (32 - k)
    } else {
      val low = genrandUint32()
      val high = genrandUint32() >>> (64 - k)
      (high << 32) | low
    }
  }

  /** `[0, n)` の一様整数（`n` のビット長ぶんの `getrandbits` を棄却法で繰り返す）。 */
  def randbelow(n: Int): Int = {
    require(n > 0, s"Mt19937.randbelow: n=$n must be positive")
    val k = 32 - Integer.numberOfLeadingZeros(n)
    var r = getrandbits(k)
    while (r >= n) {
      r = getrandbits(k)
    }
    r.toInt
  }

  /** `[a, b]` の一様整数（両端を含む）。 */
  def randint(a: Int, b: Int): Int = {
    val width = b + 1 - a
    require(width > 0, s"Mt19937.randint: empty range [$a, $b]")
    a + randbelow(width)
  }

  /** `seq` から一様に 1 要素を選ぶ。 */
  def choice[A](seq: IndexedSeq[A]): A = {
    seq(randbelow(seq.length))
  }

  /** `chars` から一様に 1 文字を選び、`String` として返す。 */
  def choice(chars: String): String = {
    chars.charAt(randbelow(chars.length)).toString
  }

  /** `[0, stop)` の一様整数。 */
  def randrange(stop: Int): Int = {
    require(stop > 0, s"Mt19937.randrange: empty range [0, $stop)")
    randbelow(stop)
  }

  /** `[start, stop)` の一様整数。 */
  def randrange(start: Int, stop: Int): Int = {
    require(stop > start, s"Mt19937.randrange: empty range [$start, $stop)")
    start + randbelow(stop - start)
  }

  /** `alphabet` から `size` 文字を独立に選んで並べた語。 */
  def word(alphabet: String, size: Int): String = {
    val builder = new StringBuilder(size)
    for (_ <- 0 until size) {
      builder ++= choice(alphabet)
    }
    builder.result()
  }
}
