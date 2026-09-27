package research

import scala.collection.mutable

/** Points-to sets of a slot machine (STRATEGY.md §3f): for every register and every (site, field), the allocation sites whose
  * cells it may hold. An Andersen-style fixpoint, insensitive to the layer and the tick: `MadeAt(i)` at layer t is the site
  * (t, i), `Reg(r)` is pts(r), `Fld(x, f)` the union of ptsF(s, f) over pts(x), `Ite` the union of its arms, the input and
  * null nothing; every register update and every site field of every layer feeds the sets. The sets are cut to the sites
  * of the declared kinds, so a points-to domain is never larger than the domain of the kinds. `LpsCode` (option
  * `pointsTo`) dispatches a read over these sets instead of every site of the pointer's tags: a young register, reset at
  * every birth, never branches to the old role's sites. */
object PointsTo {
  import Lpm.E
  import Lps.{Machine, K}
  type Site = (Int, Int)

  final case class Sets(regs: Map[String, Set[Site]], fields: Map[(Site, Int), Set[Site]]) {
    def reg(r: String): Set[Site] = regs.getOrElse(r, Set())
    def field(s: Site, f: Int): Set[Site] = fields.getOrElse((s, f), Set())
  }

  def apply(m: Machine): Sets = {
    val kindsOf: Map[String, Set[K]] = m.regs.toMap
    val regs = mutable.HashMap.empty[String, Set[Site]].withDefaultValue(Set())
    val fields = mutable.HashMap.empty[(Site, Int), Set[Site]].withDefaultValue(Set())
    def eval(e: E, t: Int): Set[Site] = e match {
      case E.Null | E.In | E.Sep => Set()
      case E.Reg(r) => regs(r)
      case E.Made => Set((t, 0))
      case E.MadeAt(i) => Set((t, i))
      case E.Fld(x, f) => eval(x, t).flatMap(s => fields((s, f)))
      case E.Ite(_, a, b) => eval(a, t) ++ eval(b, t)
    }
    var changed = true
    while (changed) {
      changed = false
      for (t <- 1 to m.budget) {
        val step = m.slotAt(t)
        for ((r, e) <- step.regs) {
          val v = regs(r) ++ (eval(e, t).toVector.filter(s => m.sitesOf(kindsOf(r)).contains(s)))
          if (v != regs(r)) { regs(r) = v; changed = true }
        }
        for (((_, cl), i) <- m.sitesAt(t).zipWithIndex; f <- 1 to 4) {
          val src: E = f match { case 1 => cl.p1; case 2 => cl.p2; case 3 => cl.p3; case _ => cl.p4 }
          val allowed = m.sitesOf(m.tagsAt(t, i).flatMap(tag => m.fieldKinds(tag, f)).toSet)
          val v = fields(((t, i), f)) ++ eval(src, t).toVector.filter(allowed.contains)
          if (v != fields(((t, i), f))) { fields(((t, i), f)) = v; changed = true }
        }
      }
    }
    Sets(regs.toMap, fields.toMap)
  }

  /** the sites a cell's register or field holds that its points-to set misses, after every slot of `w` (a soundness check) */
  def violations(m: Machine, sets: Sets, w: String): Vector[String] = {
    val out = Vector.newBuilder[String]
    def site(c: Lpm.RCell): Option[Site] = if (c == null || c.tag <= m.alphabet.length) { None } else { Some((c.layer, c.site)) }
    Lps.traceSlots(m, w) { (tick, slot, st) =>
      for ((r, _) <- m.regs; c <- Option(st.regs.getOrElse(r, null)); s <- site(c)) {
        if (!sets.reg(r).contains(s)) { out += s"tick $tick after $slot: $r holds a cell of site $s, not in ${sets.reg(r).toVector.sorted}" }
        for (f <- 1 to 4; x <- site(c.fld(f)) if !sets.field(s, f).contains(x)) {
          out += s"tick $tick after $slot: field $f of site $s (in $r) holds site $x, not in ${sets.field(s, f).toVector.sorted}"
        }
      }
    }
    out.result()
  }
}
