package research

import scala.collection.mutable

/** A pointer machine that runs in real time on a scaffolding automaton.
  *
  * The machine reads one input letter per tick and runs exactly `budget` micro-steps per tick.
  * Every tick creates a node; the node holds an *input cell* (the letter, linked to the previous
  * input cell) and at most one *cell* per micro-step. A cell is an immutable record
  * `(sym, p1, p2)` whose pointers refer to cells created earlier (older cells). Registers hold cell
  * pointers or null. The control is a program of instructions, one per micro-step, with labels.
  *
  * Only older cells are reachable: a pointer field of a cell refers to a cell that existed when the
  * cell was created. This is the discipline of scaffolding automata, so a program of this machine
  * compiles to a PEG with one node per input position.
  */
object Psm {
  /** Cell identity: the node (tick, 0 for the initial node) and the slot (`-1` input cell, else micro-step). */
  final case class Cell(node: Int, slot: Int, sym: Char, p1: Option[Cell], p2: Option[Cell]) {
    override def toString: String = s"Cell($node,$slot,'$sym')"
  }

  sealed trait Src
  object Src {
    case object Null extends Src
    final case class Reg(r: String) extends Src
    final case class Field(r: String, field: Int) extends Src          // r.p1 or r.p2
    case object Input extends Src                                       // the current node's input cell
    final case class New(sym: Char, p1: Src, p2: Src) extends Src       // allocate in the current node
  }

  sealed trait Cond
  object Cond {
    final case class IsNull(r: String) extends Cond
    final case class SymIs(r: String, sym: Char) extends Cond
    final case class SameSym(r: String, s: String) extends Cond         // both non-null and equal symbols
    final case class Not(c: Cond) extends Cond
    final case class And(a: Cond, b: Cond) extends Cond
    final case class Or(a: Cond, b: Cond) extends Cond
    case object True extends Cond
  }

  sealed trait Instr
  object Instr {
    final case class Set(r: String, src: Src) extends Instr
    final case class Branch(cond: Cond, ifTrue: String, ifFalse: String) extends Instr
    final case class Goto(label: String) extends Instr
    /** Idle until the next tick; the next instruction runs when the next letter has arrived. */
    case object Wait extends Instr
    /** The answer for the current prefix (the last `Out` of a tick wins; default false). */
    final case class Out(accept: Boolean) extends Instr
    case object Halt extends Instr
  }

  final case class Program(instrs: Vector[(String, Instr)], registers: Vector[String]) {
    val labels: Map[String, Int] = instrs.zipWithIndex.collect { case ((l, _), i) if l.nonEmpty => l -> i }.toMap
    require(labels.size == instrs.count(_._1.nonEmpty), "duplicate labels")
    def resolve(label: String): Int = labels.getOrElse(label, throw new NoSuchElementException(s"label $label"))
  }

  /** A builder with a small assembly-like notation. */
  final class Builder {
    private val out = Vector.newBuilder[(String, Instr)]
    private var pending = ""
    private val regs = mutable.LinkedHashSet.empty[String]
    def label(l: String): Builder = { require(pending.isEmpty, "two labels on one instruction"); pending = l; this }
    private def emit(i: Instr): Builder = { out += ((pending, i)); pending = ""; this }
    def set(r: String, src: Src): Builder = { regs += r; emit(Instr.Set(r, src)) }
    def branch(c: Cond, t: String, f: String): Builder = emit(Instr.Branch(c, t, f))
    def goto(l: String): Builder = emit(Instr.Goto(l))
    def pause(): Builder = emit(Instr.Wait)
    def out(b: Boolean): Builder = emit(Instr.Out(b))
    def halt(): Builder = emit(Instr.Halt)
    def build(): Program = { require(pending.isEmpty); Program(out.result(), regs.toVector) }
  }

  final class Trace(val ticks: Int, val outputs: Vector[Boolean], val cells: Int, val microSteps: Int)

  /** Run `program` on `word` with `budget` micro-steps per tick. Returns the per-tick outputs. */
  def run(program: Program, word: String, budget: Int, strict: Boolean = true): Trace = {
    val regs = mutable.HashMap.empty[String, Option[Cell]].withDefaultValue(None)
    var pc = 0
    var halted = false
    var input: Option[Cell] = None
    var cells = 0
    var micro = 0
    val outputs = Vector.newBuilder[Boolean]
    def value(src: Src, node: Int, slot: Int): Option[Cell] = src match {
      case Src.Null => None
      case Src.Reg(r) => regs(r)
      case Src.Field(r, 1) => regs(r).flatMap(_.p1)
      case Src.Field(r, _) => regs(r).flatMap(_.p2)
      case Src.Input => input
      case Src.New(sym, a, b) =>
        cells += 1
        Some(Cell(node, slot, sym, value(a, node, slot), value(b, node, slot)))
    }
    def holds(c: Cond): Boolean = c match {
      case Cond.IsNull(r) => regs(r).isEmpty
      case Cond.SymIs(r, s) => regs(r).exists(_.sym == s)
      case Cond.SameSym(r, s) => (regs(r), regs(s)) match { case (Some(x), Some(y)) => x.sym == y.sym; case _ => false }
      case Cond.Not(x) => !holds(x)
      case Cond.And(a, b) => holds(a) && holds(b)
      case Cond.Or(a, b) => holds(a) || holds(b)
      case Cond.True => true
    }
    for ((letter, index) <- word.zipWithIndex) {
      val node = index + 1
      input = Some(Cell(node, -1, letter, input, None))
      var out = false
      var step = 0
      var waiting = false
      while (!waiting && !halted && step < budget) {
        val (_, instr) = program.instrs(pc)
        micro += 1
        instr match {
          case Instr.Set(r, src) => regs(r) = value(src, node, step); pc += 1
          case Instr.Branch(c, t, f) => pc = program.resolve(if (holds(c)) { t } else { f })
          case Instr.Goto(l) => pc = program.resolve(l)
          case Instr.Wait => waiting = true; pc += 1
          case Instr.Out(b) => out = b; pc += 1
          case Instr.Halt => halted = true
        }
        step += 1
      }
      if (strict && !waiting && !halted) {
        throw new IllegalStateException(s"tick $node exhausted its budget of $budget micro-steps at pc=$pc (${program.instrs(pc)._2})")
      }
      outputs += out
    }
    new Trace(word.length, outputs.result(), cells, micro)
  }

  // ---- example programs ----------------------------------------------------

  /** Marked palindromes u # u^R: push letters, then pop and compare. */
  def markedPal: Program = {
    import Src.*
    import Cond.*
    val b = new Builder
    b.label("push").set("in", Input)
      .branch(SymIs("in", '#'), "marker", "doPush")
      .label("doPush").set("stack", New('s', Reg("in"), Reg("stack")))    // cell: (letter cell, below)
      .out(false).pause().goto("push")
      .label("marker").branch(IsNull("stack"), "markerEmpty", "markerFull")
      .label("markerEmpty").out(true).pause().goto("pop")
      .label("markerFull").out(false).pause()
      .label("pop").set("in", Input)
      .branch(IsNull("stack"), "tooLong", "compare")
      .label("compare").set("top", Field("stack", 1))
      .branch(SameSym("top", "in"), "match", "fail")
      .label("match").set("stack", Field("stack", 2))
      .branch(IsNull("stack"), "done", "more")
      .label("done").out(true).pause().goto("pop")
      .label("more").out(false).pause().goto("pop")
      .label("tooLong").out(false).pause().goto("tooLong")
      .label("fail").out(false).pause().goto("fail")
    b.build()
  }
}
