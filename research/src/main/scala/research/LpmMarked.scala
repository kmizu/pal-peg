package research

/** Marked palindromes `u # uᴿ` as a layered pointer machine: a stack of letter cells. */
object LpmMarked {
  import Lpm.*
  import Lpm.E.*
  import Lpm.C.*

  val CellTag = 4   // input tags: a=0, b=1, #=2, separator=3

  def machine: Machine = {
    val stack = Reg("stack")
    val top = Fld(stack, 1)
    val below = Fld(stack, 2)
    val isHash = TagIs(In, 2)
    val popping = Bit("popping")
    val bad = Bit("bad")
    val acc = Bit("acc")
    val matches = all(popping, Not(bad), Not(IsNull(stack)), SameTag(top, In))
    val step = Step(
      regs = Map(
        "stack" -> Ite(And(First, Not(popping)), Ite(isHash, stack, Made), Ite(And(First, matches), below, stack))),
      bits = Map(
        "popping" -> Or(popping, And(First, isHash)),
        "bad" -> Or(bad, all(First, popping, Or(IsNull(stack), Not(SameTag(top, In))))),
        "acc" -> Or(And(Not(First), acc), And(First, Or(all(Not(popping), isHash, IsNull(stack)), And(matches, IsNull(below)))))),
      cell = Some(Cell(TE.Const(CellTag), In, stack, Null)))
    Machine("ab#", Vector("stack" -> Mach), Vector("popping", "bad", "acc"), Set(), "acc", step)
  }

  def oracle(w: String): Boolean = w.count(_ == '#') == 1 && Exhaust.isPal(w)
}
