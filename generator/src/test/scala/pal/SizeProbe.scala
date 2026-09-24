package pal

import Expr.TRUE
import ScaffoldCircuit.{choose, conjunction as AND, disjunction as OR, neg}
import scala.collection.mutable

/** Temporary measurement: which construction phase creates the rules of the window-pal source. */
object SizeProbe {
  import Expr.*

  private def children(e: Expr): Iterator[Expr] = e match {
    case Not(v) => Iterator(v)
    case And(vs) => vs.iterator
    case Or(vs) => vs.iterator
    case Select(c, y, n) => Iterator(c, y, n)
    case Read(t, _) => Iterator(t)
    case Edge(t, _) => Iterator(t)
    case Present(t) => Iterator(t)
    case _ => Iterator.empty
  }

  private def reach(roots: Iterable[Expr]): java.util.IdentityHashMap[Expr, Boolean] = {
    val seen = new java.util.IdentityHashMap[Expr, Boolean]()
    val stack = mutable.ArrayDeque.empty[Expr]
    roots.foreach(stack.append)
    while (stack.nonEmpty) {
      val e = stack.removeLast()
      if (!seen.containsKey(e)) { seen.put(e, true); children(e).foreach(stack.append) }
    }
    seen
  }

  def main(args: Array[String]): Unit = {
    val qm = args.lift(0).map(_.toInt).getOrElse(1)
    val qf = args.lift(1).map(_.toInt).getOrElse(1)
    val phaseOf = new java.util.IdentityHashMap[Expr, String]()
    var phase = "init"
    Expr.onIntern = e => phaseOf.put(e, phase)
    def in[A](name: String)(body: => A): A = { phase = name; body }
    var cRef: WindowPAL = null
    val (circuit, machine) = Expr.share {
      val circuit = new Circuit("ab")
      val c = in("construct") {
        new WindowPAL(circuit, GsBatchClock.DEFAULT_BATCH,
          matcherFactory = (ci, p, _) => ScaffoldWindowWorkers.matcher(ci, p, qm),
          flagsFactory = (ci, p, _) => ScaffoldWindowWorkers.flags(ci, p, qf))
      }
      cRef = c
      // The body of WindowPAL.tick, with phase markers.
      in("ctl.head") {
        val current = circuit.input().eqTo('b')
        c.first = Value.select(c.small.eqTo(0), Value.select(current, Value.constant(true), Value.constant(false)), c.first)
        c.small = c.small.map(n => math.min(4, n + 1))
        c.history.push()
        val firstRound = neg(c.powerReady)
        c.nextBirth.drop(c.powerReady)
        val birth = AND(c.powerReady, c.nextBirth.empty())
        for ((stage, stageIndex) <- c.stages.zipWithIndex) {
          in(s"stage.advance") { stage.advance(AND(birth, c.slot.eqTo(stageIndex)), c.power) }
        }
        c.power.copyFrom(c.history, OR(firstRound, birth))
        c.nextBirth.copyFrom(c.history, OR(firstRound, birth))
        c.powerReady = TRUE
        c.slot = Value.select(birth, c.slot.cycle(), c.slot)
        for (((stage, matching), flagging) <- c.stages.zip(c.matchers).zip(c.flags)) {
          in("match.arrive") { matching.arrive() }
          in("flags.arrive") { flagging.arrive() }
          in("flags.reset") { flagging.resetFlags(stage.birth) }
          in("match.start") { matching.start(stage.birth) }
          in("flags.start") { flagging.start(stage.release) }
          in("flags.mark") { flagging.mark(stage.release) }
          in("stage.consume") { stage.consume() }
          in("match.service") { matching.service() }
          in("flags.service") { flagging.service() }
          in("stage.capture") { stage.capture() }
        }
        val active = c.stages.map(_.answering())
        circuit.require(AND(OR(active*), neg(AND(active*))), c.small.eqTo(4))
        val ordinary = OR(active.zip(c.stages).zip(c.matchers).map { case ((enabled, stage), matching) =>
          AND(enabled, stage.middle, matching.output)
        }*)
        val short = OR(c.small.eqTo(1), AND(OR(c.small.eqTo(2), c.small.eqTo(3)),
          choose(current, c.first.eqTo(true), c.first.eqTo(false))))
        c.output = choose(c.small.eqTo(4), ordinary, short)
      }
      for (((matching, flagging), stage) <- c.matchers.zip(c.flags).zip(c.stages)) {
        in("match.commit") { matching.commit() }
        in("flags.commit") { flagging.commit() }
        in("stage.commit") { stage.commit() }
      }
      in("ctl.commit") {
        Vector(c.history, c.power, c.nextBirth).foreach(_.commit())
        circuit.put("pal.slot", c.slot)
        circuit.put("pal.small", c.small)
        circuit.put("pal.first", c.first)
        circuit.put("pal.power_ready", Value.select(c.powerReady, Value.constant(true), Value.constant(false)))
      }
      val m = in("machine") { circuit.machine(c.output, initialAccepting = true) }
      (circuit, m)
    }
    Expr.onIntern = null
    val c = cRef
    val all = reach(machine.labels.values ++ machine.pointers.values)
    println(s"== qm=$qm qf=$qf labels=${machine.labels.size} pointers=${machine.pointers.size} nodes=${all.size} created=${phaseOf.size}")
    val byPhase = mutable.LinkedHashMap.empty[String, mutable.LinkedHashMap[String, Int]]
    all.keySet.forEach { e =>
      val p = Option(phaseOf.get(e)).getOrElse("unshared")
      val h = byPhase.getOrElseUpdate(p, mutable.LinkedHashMap.empty)
      h(e.tag) = h.getOrElse(e.tag, 0) + 1
    }
    for ((p, h) <- byPhase.toVector.sortBy(-_._2.values.sum)) {
      println(f"  ${p}%-16s ${h.values.sum}%9d  " + h.toVector.sortBy(-_._2).map { case (t, n) => s"$t=$n" }.mkString(" "))
    }
    // Which cells do the reads touch?  label name -> key -> pool prefix / field.
    val labelKey: Map[String, String] = circuit.labelIds.map { case ((key, _), name) => name -> key }.toMap
    val poolDesc: Map[String, String] = circuit.pools.map(pool => pool.prefix -> pool.layout.keys.take(2).mkString("|")).toMap
    val readHist = mutable.LinkedHashMap.empty[String, Int]
    all.keySet.forEach {
      case Read(_, label) =>
        val key = labelKey.getOrElse(label, label)
        val parts = key.split('.')
        val g = if (parts.head.startsWith("pool")) { s"${parts.head}(${poolDesc.getOrElse(parts.head, "?")}).${parts.last}" } else { key.split('.').take(2).mkString(".") }
        readHist(g) = readHist.getOrElse(g, 0) + 1
      case _ => ()
    }
    println("  reads by cell field:")
    for ((g, n) <- readHist.toVector.sortBy(-_._2).take(40)) { println(f"    $n%8d  $g") }
    if (args.lift(2).contains("stats")) {
      for (w <- (c.matchers ++ c.flags).collect { case w: WindowWorker => w }) {
        val hist = w.program.code.groupBy(_.event.getClass.getSimpleName).map { case (k, v) => s"$k=${v.size}" }.toVector.sorted.mkString(" ")
        val maxMove = w.program.code.flatMap(_.event match { case Event.Move(ms) => ms.map(m => math.abs(m.delta)); case _ => Nil }).maxOption.getOrElse(0)
        println(s"  worker ${w.prefix}: instructions=${w.program.code.size} registers=${w.readers.registers} maxMove=$maxMove stream.names=${w.stream.names.size} distances=${w.distances.keys.size}")
        println(s"    events: $hist")
      }
    }
    if (args.lift(2).contains("emit")) {
      val path = java.nio.file.Paths.get("/tmp/claude-1000/-home-mizushima-repo-lean4-peg/5c01479a-665e-4163-a739-d2c09d1a0e37/scratchpad/q11.peg")
      val writer = java.nio.file.Files.newBufferedWriter(path)
      var n = 0
      try { machine.iterRules().foreach { r => writer.write(r); writer.newLine(); n += 1 } } finally { writer.close() }
      println(s"  emitted rules=$n bytes=${java.nio.file.Files.size(path)}")
    }
    if (args.lift(2).contains("optimize")) {
      val started = System.nanoTime()
      val (optimized, stats) = ScaffoldOptimize.optimize(machine)
      val after = reach(optimized.labels.values ++ optimized.pointers.values)
      println(f"  optimized: labels=${optimized.labels.size} pointers=${optimized.pointers.size} nodes=${after.size} rules=${optimized.iterRules().size} stats=$stats  ${(System.nanoTime() - started) / 1e9}%.1fs")
    }
    val _ = circuit
  }
}
