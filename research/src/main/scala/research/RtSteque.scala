package research

/** STRATEGY.md §3l: a reference model of Kaplan and Tarjan's purely functional, real-time catenable steque (JACM 46(5), 1999,
  * §5): push, pop, inject and catenate, each in O(1) worst-case time, every cell pointing only to older cells. It is the
  * sequence structure a de-amortised young role needs (STRATEGY.md §3k: the forward sequences of the matcher are catenations
  * of stored ranges), written to count what one operation stores, by kind, not to be compiled.
  *
  * A steque is empty, a suffix only, or a triple (prefix, child, suffix); the child is a steque of pairs, a pair is a prefix
  * and a steque of pairs. Buffers are non-catenable steques with no size bound (a stack for pushes and a Hood–Melville
  * real-time queue for injects). A prefix holds at least two elements and is red with two, yellow with three, green with four
  * or more; a suffix only steque is green. A top-level steque is regular (between two reds of a descendant sequence there is a
  * green, and the first non-yellow one is green); a pop repairs the nearest red descendant. The descendant sequence is stored
  * as a stack of substacks: a node points to its child when the child is yellow (`yc`), and a node that is non-yellow or at the
  * top points to its nearest non-yellow proper descendant (`nny`); so the nearest red is at most two pointers away and the
  * repair copies O(1) nodes. The integer sizes and counters of the buffers stand for bounded checks (a size up to four) and for
  * the queue's rotation bookkeeping; they are not cells.
  */
object RtSteque {
  /** cells stored by kind; the structures are immutable, the meter only counts */
  final class Meter {
    val cells: scala.collection.mutable.Map[String, Int] = scala.collection.mutable.Map().withDefaultValue(0)
    def alloc(kind: String): Unit = cells(kind) += 1
    def total: Int = cells.values.sum
    def snapshot: Map[String, Int] = cells.toMap
    def reset(): Unit = cells.clear()
  }

  private def cons(x: Any, t: List[Any])(using m: Meter): List[Any] = { m.alloc("cons"); x :: t }

  /** Hood–Melville real-time queue (Okasaki, Purely Functional Data Structures, §8.2.1), strict */
  sealed trait Rot
  case object Idle extends Rot
  final case class Reversing(ok: Int, f: List[Any], f1: List[Any], r: List[Any], r1: List[Any]) extends Rot
  final case class Appending(ok: Int, f1: List[Any], r1: List[Any]) extends Rot
  final case class Done(f: List[Any]) extends Rot
  final case class HQ(lenf: Int, f: List[Any], st: Rot, lenr: Int, r: List[Any]) {
    def size: Int = lenf + lenr
  }
  val emptyQ: HQ = HQ(0, Nil, Idle, 0, Nil)

  private def rot(r: Rot)(using m: Meter): Rot = { m.alloc("rot"); r }
  private def hq(q: HQ)(using m: Meter): HQ = { m.alloc("hq"); q }
  private def exec(st: Rot)(using Meter): Rot = st match {
    case Reversing(ok, x :: f, f1, y :: r, r1) => rot(Reversing(ok + 1, f, cons(x, f1), r, cons(y, r1)))
    case Reversing(ok, Nil, f1, y :: Nil, r1) => rot(Appending(ok, f1, cons(y, r1)))
    case Appending(0, _, r1) => rot(Done(r1))
    case Appending(ok, x :: f1, r1) => rot(Appending(ok - 1, f1, cons(x, r1)))
    case s => s
  }
  private def invalidate(st: Rot)(using Meter): Rot = st match {
    case Reversing(ok, f, f1, r, r1) => rot(Reversing(ok - 1, f, f1, r, r1))
    case Appending(0, _, _ :: r1) => rot(Done(r1))
    case Appending(ok, f1, r1) => rot(Appending(ok - 1, f1, r1))
    case s => s
  }
  private def exec2(q: HQ)(using Meter): HQ = exec(exec(q.st)) match {
    case Done(nf) => hq(HQ(q.lenf, nf, Idle, q.lenr, q.r))
    case s => hq(HQ(q.lenf, q.f, s, q.lenr, q.r))
  }
  private def check(q: HQ)(using Meter): HQ =
    if (q.lenr <= q.lenf) { exec2(q) } else { exec2(HQ(q.lenf + q.lenr, q.f, rot(Reversing(0, q.f, Nil, q.r, Nil)), 0, Nil)) }
  def snoc(q: HQ, x: Any)(using Meter): HQ = check(HQ(q.lenf, q.f, q.st, q.lenr + 1, cons(x, q.r)))
  def qhead(q: HQ): Any = q.f.head
  def qtail(q: HQ)(using Meter): HQ = check(HQ(q.lenf - 1, q.f.tail, invalidate(q.st), q.lenr, q.r))

  /** a buffer: a non-catenable steque, a stack of pushed elements over a queue of injected ones */
  final case class Buf(stack: List[Any], sn: Int, q: HQ) {
    def size: Int = sn + q.size
    def elems: List[Any] = stack ++ qElems(q)
  }
  /** the elements of a queue, by popping a copy (during a rotation they are spread over the rotation state) */
  private def qElems(q: HQ): List[Any] = {
    given Meter = new Meter
    if (q.size == 0) { Nil } else { qhead(q) :: qElems(qtail(q)) }
  }
  private def buf(b: Buf)(using m: Meter): Buf = { m.alloc("buf"); b }
  def bufOf(xs: List[Any])(using Meter): Buf = xs.foldRight(Buf(Nil, 0, emptyQ)) { (x, b) => bpush(b, x) }
  def bpush(b: Buf, x: Any)(using Meter): Buf = buf(Buf(cons(x, b.stack), b.sn + 1, b.q))
  def binject(b: Buf, x: Any)(using Meter): Buf = buf(Buf(b.stack, b.sn, snoc(b.q, x)))
  def bpop(b: Buf)(using Meter): (Any, Buf) = {
    require(b.size > 0, "pop of an empty buffer")
    if (b.sn > 0) { (b.stack.head, buf(Buf(b.stack.tail, b.sn - 1, b.q))) } else { (qhead(b.q), buf(Buf(Nil, 0, qtail(b.q)))) }
  }

  /** a node of a descendant sequence; `pre == null` for a suffix only steque */
  final class Node(val pre: Buf, val suf: Buf, val yc: Node, val nny: Node)
  /** a node together with its nearest non-yellow proper descendant (a stored inner yellow node does not keep it): registers */
  final case class V(n: Node, nny: Node)
  final case class Pair(p: Buf, s: Node)

  val Red = 0; val Yellow = 1; val Green = 2
  def color(n: Node): Int = if (n.pre == null) { Green } else {
    val z = n.pre.size
    require(z >= 2, s"a prefix of $z elements")
    if (z == 2) { Red } else if (z == 3) { Yellow } else { Green }
  }
  private def node(pre: Buf, suf: Buf, yc: Node, nny: Node)(using m: Meter): Node = { m.alloc("node"); new Node(pre, suf, yc, nny) }
  private def pair(p: Buf, s: Node)(using m: Meter): Pair = { m.alloc("pair"); Pair(p, s) }
  def view(s: Node): V = if (s == null) { null } else { V(s, s.nny) }
  def child(v: V): V = if (v == null) { null } else if (v.n.yc != null) { V(v.n.yc, v.nny) } else if (v.nny != null) { V(v.nny, v.nny.nny) } else { null }
  /** the node of a triple (or suffix only when `pre` is null) over the child `c` */
  private def mk(pre: Buf, suf: Buf, c: V)(using Meter): Node =
    if (c == null) { node(pre, suf, null, null) }
    else if (color(c.n) == Yellow) { node(pre, suf, c.n, c.nny) }
    else { require(c.n.nny eq c.nny, "a head keeps its own nny"); node(pre, suf, null, c.n) }
  /** a top-level value for a view (a fresh node when a stored inner yellow node has a stale nny) */
  def toTop(v: V)(using Meter): Node = if (v == null) { null } else if (v.n.nny eq v.nny) { v.n } else { node(v.n.pre, v.n.suf, v.n.yc, v.nny) }
  private def suffixOnly(b: Buf)(using Meter): V = if (b.size == 0) { null } else { V(node(null, b, null, null), null) }

  private def vPush(v: V, x: Any)(using Meter): V =
    if (v == null) { suffixOnly(bpush(Buf(Nil, 0, emptyQ), x)) }
    else if (v.n.pre == null) { suffixOnly(bpush(v.n.suf, x)) }
    else { V(node(bpush(v.n.pre, x), v.n.suf, v.n.yc, v.nny), v.nny) }
  private def vInject(v: V, x: Any)(using Meter): V =
    if (v == null) { suffixOnly(binject(Buf(Nil, 0, emptyQ), x)) }
    else if (v.n.pre == null) { suffixOnly(binject(v.n.suf, x)) }
    else { V(node(v.n.pre, binject(v.n.suf, x), v.n.yc, v.nny), v.nny) }
  /** a pop without repair (the caller keeps the result semiregular) */
  private def vNaivePop(v: V)(using Meter): (Any, V) =
    if (v.n.pre == null) { val (x, b) = bpop(v.n.suf); (x, suffixOnly(b)) }
    else { val (x, p) = bpop(v.n.pre); (x, V(node(p, v.n.suf, v.n.yc, v.nny), v.nny)) }

  /** turns a red node green (Lemma 5.2) */
  private def repair(v: V)(using Meter): Node = {
    val c = child(v)
    val (a, p1) = bpop(v.n.pre)
    val (b, _) = bpop(p1)
    if (c == null) { node(null, bpush(bpush(v.n.suf, b), a), null, null) }
    else {
      val (pr, c1) = vNaivePop(c)
      val Pair(p, s2) = pr.asInstanceOf[Pair]
      val s3 = catenate(view(s2), c1)
      mk(bpush(bpush(p, b), a), v.n.suf, s3)
    }
  }

  /** the at most three elements of a small buffer, first first */
  private def small(b: Buf)(using Meter): List[Any] = {
    var xs = List[Any](); var r = b
    while (r.size > 0) { val (x, r1) = bpop(r); xs = x :: xs; r = r1 }
    xs.reverse
  }
  private def pushAll(b: Buf, xs: List[Any])(using Meter): Buf = xs.foldRight(b) { (x, acc) => bpush(acc, x) }

  /** catenation (§5.2, cases 1 to 3) of two semiregular steques; regular when `v1` is */
  def catenate(v1: V, v2: V)(using Meter): V =
    if (v1 == null) { v2 } else if (v2 == null) { v1 }
    else if (v1.n.pre != null) {
      var c1 = child(v1); var s2 = v2
      val z = v1.n.suf.size
      if (z >= 2) { c1 = vInject(c1, pair(v1.n.suf, null)) }
      else if (z == 1) { val (x, _) = bpop(v1.n.suf); s2 = vPush(s2, x) }
      if (s2.n.pre != null) { c1 = vInject(c1, pair(s2.n.pre, toTop(child(s2)))) }
      val n = mk(v1.n.pre, s2.n.suf, c1)
      V(n, n.nny)
    } else if (v2.n.pre != null) {
      val z = v1.n.suf.size
      if (z >= 4) { val n = mk(v1.n.suf, v2.n.suf, vPush(child(v2), pair(v2.n.pre, null))); V(n, n.nny) }
      else { V(node(pushAll(v2.n.pre, small(v1.n.suf)), v2.n.suf, v2.n.yc, v2.nny), v2.nny) }
    } else {
      val z = v1.n.suf.size
      if (z >= 4) { V(node(v1.n.suf, v2.n.suf, null, null), null) }
      else { suffixOnly(pushAll(v2.n.suf, small(v1.n.suf))) }
    }

  // the top-level operations on regular steques (null is the empty steque)
  def push(s: Node, x: Any)(using Meter): Node = toTop(vPush(view(s), x))
  def inject(s: Node, x: Any)(using Meter): Node = toTop(vInject(view(s), x))
  def concat(s1: Node, s2: Node)(using Meter): Node = toTop(catenate(view(s1), view(s2)))
  def pop(s: Node)(using Meter): (Any, Node) = {
    require(s != null, "pop of an empty steque")
    val v = view(s)
    if (v.n.pre == null) { val (x, b) = bpop(v.n.suf); (x, toTop(suffixOnly(b))) }
    else {
      val (x, p) = bpop(v.n.pre)
      val t = node(p, v.n.suf, v.n.yc, v.nny)
      val c = color(t)
      val r = if (c == Red) { repair(V(t, t.nny)) }
        else if (c == Yellow && t.nny != null && color(t.nny) == Red) { node(t.pre, t.suf, t.yc, repair(V(t.nny, t.nny.nny))) }
        else { t }
      (x, r)
    }
  }

  // --- checks (tests and probes) ---
  private def seqV(v: V, f: Any => List[Any]): List[Any] = if (v == null) { Nil } else {
    val pre = if (v.n.pre == null) { Nil } else { v.n.pre.elems.flatMap(f) }
    pre ++ seqV(child(v), pairF(f)) ++ v.n.suf.elems.flatMap(f)
  }
  private def pairF(f: Any => List[Any]): Any => List[Any] = {
    case Pair(p, s) => p.elems.flatMap(f) ++ seqV(view(s), pairF(f))
    case x => throw new IllegalStateException(s"not a pair: $x")
  }
  /** the elements of a steque in order */
  def toList(s: Node): List[Any] = seqV(view(s), x => List(x))

  /** the representation invariants: prefixes of at least two elements, yellow-child and nearest-non-yellow pointers, the last
    * descendant alone may be a suffix only, and the regularity of every descendant sequence (`regular` for the top one) */
  def check(s: Node, regular: Boolean = true): Option[String] = {
    def seqOf(v: V): Vector[V] = if (v == null) { Vector() } else { v +: seqOf(child(v)) }
    def chk(top: V, reg: Boolean, depth: Int): Option[String] = {
      val ds = seqOf(top)
      val errs = scala.collection.mutable.ArrayBuffer[String]()
      for ((d, i) <- ds.zipWithIndex) {
        if (d.n.pre != null && d.n.pre.size < 2) { errs += s"prefix of ${d.n.pre.size} at $i" }
        if (d.n.pre == null && i != ds.length - 1) { errs += s"suffix only at $i of ${ds.length}" }
        val next = ds.lift(i + 1)
        next.foreach { c => if (color(c.n) == Yellow && !(d.n.yc eq c.n)) { errs += s"yc missing at $i" } }
        if (next.exists(c => color(c.n) != Yellow) && d.n.yc != null) { errs += s"yc to a non-yellow at $i" }
        val nny = ds.drop(i + 1).find(c => color(c.n) != Yellow).map(_.n).orNull
        if (!(d.nny eq nny)) { errs += s"nny wrong at $i" }
      }
      val cs = ds.map(d => color(d.n)).filter(_ != Yellow)
      if (cs.sliding(2).exists(w => w.length == 2 && w(0) == Red && w(1) == Red)) { errs += "two reds without a green" }
      if (reg && cs.headOption.contains(Red)) { errs += "first non-yellow is red" }
      val pairs = ds.flatMap(d => (if (d.n.pre == null) { Nil } else { d.n.pre.elems }) ++ d.n.suf.elems).collect { case Pair(_, ps) => ps }.filter(_ != null)
      errs.headOption.map(e => s"depth $depth: $e").orElse(pairs.iterator.map(ps => chk(view(ps), false, depth + 1)).collectFirst { case Some(e) => e })
    }
    if (s == null) { None } else { chk(view(s), regular, 0) }
  }
}
