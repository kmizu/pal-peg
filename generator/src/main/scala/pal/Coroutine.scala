package pal

import scala.annotation.tailrec

/** Coroutines as explicit state machines.
  *
  * A coroutine is driven by `send`: it suspends on an event (for example a head
  * instruction), the driver answers with the outcome of a test (`Some(true)` /
  * `Some(false)`) or `None` for a command, and a coroutine may delegate to a
  * sub-coroutine, whose return value becomes available to the parent once the
  * sub-coroutine finishes.
  *
  * Each coroutine is a [[Generator]] subclass. Its body is split at every
  * suspension point (an emitted event or a delegation) into a numbered *site*;
  * `step` receives the response to the suspension at the current site, runs the
  * straight-line code up to the next suspension point and reports it as an
  * [[Action]]. Delegation is handled once, in [[Generator.send]], so the subclasses
  * only ever describe their own frame.
  *
  * The explicit encoding (rather than threads or a monadic encoding) lets
  * [[GsProgram.compileController]] *inspect* the suspended stack: a control key
  * is the [[Frame]] of every coroutine in the delegation chain, i.e. its name, its
  * suspension site and its currently bound locals. One site per suspension point,
  * with locals present only once assigned, fixes the granularity of the control
  * keys, so the construction-state count (not only the minimized table) is a
  * well-defined property of each controller.
  */
object Coroutine {

  /** The value of a bound local. Only integers, booleans and strings occur in the
    * controllers.
    */
  type Local = Int | Boolean | String

  /** One suspended coroutine frame, as it appears in a control key. */
  final case class Frame(name: String, site: Int, locals: Vector[(String, Local)])
}

/** What the driver receives from `send`. */
sealed trait Step[+E, +R]

/** The coroutine suspended on `event`. */
final case class Yielded[E](event: E) extends Step[E, Nothing]

/** The coroutine finished with `value`. */
final case class Returned[R](value: R) extends Step[Nothing, R]

/** What a frame's `step` asks the driver to do next. Invariant in both
  * parameters (a [[Generator]] is invariant in its event type); the type
  * arguments are inferred from the expected `Action[E, R]` of `step`.
  */
sealed trait Action[E, R]

object Action {

  /** Suspend on `event`. */
  final case class Emit[E, R](event: E) extends Action[E, R]

  /** Delegate to `child` and stay suspended until it returns. */
  final case class Call[E, R](child: Generator[E, ?]) extends Action[E, R]

  /** Finish with `value`. */
  final case class Return[E, R](value: R) extends Action[E, R]
}

/** A coroutine as an explicit state machine.
  *
  * @param name the frame name, part of the control key
  * @tparam E the event type it suspends on
  * @tparam R the type of its result
  */
abstract class Generator[E, R](val name: String) {
  import Action.*

  /** The suspension site (index of the suspension point), 0 before the first
    * activation. Subclasses assign it in `step`.
    */
  protected var site: Int = 0

  private var delegate: Option[Generator[E, ?]] = None
  private var outcome: Option[R] = None

  /** The return value, available once `send` returned [[Returned]]. Parents read
    * their child's result here after a delegation completed.
    */
  def result: R = outcome.getOrElse(throw new IllegalStateException(s"$name has not returned"))

  /** The currently bound locals of this frame (any order). */
  def locals: Vector[(String, Coroutine.Local)] = Vector.empty

  /** The current suspension site, for observers. */
  def currentSite: Int = site

  /** Resume with the response to the current suspension and run to the next one.
    * `response` is `None` after a command or on the first activation, and the
    * test outcome after a test.
    */
  protected def step(response: Option[Boolean]): Action[E, R]

  /** Suspend on `event` at site `at`. */
  protected final def emitAt(at: Int, event: E): Action[E, R] = {
    site = at
    Emit(event)
  }

  /** Delegate to `child` at site `at`, suspended until `child` returns. */
  protected final def callAt(at: Int, child: Generator[E, ?]): Action[E, R] = {
    site = at
    Call(child)
  }

  /** Resume with `response` and run to the next suspension or to the end; the first
    * activation passes `None`.
    */
  final def send(response: Option[Boolean]): Step[E, R] = {
    delegate match {
      case Some(child) =>
        resumeChild(child, response) match {
          case Some(event) => Yielded(event)
          case None =>
            delegate = None
            advance(None)
        }
      case None => advance(response)
    }
  }

  /** Resume a delegate; `None` once it returned. */
  private def resumeChild[C](child: Generator[E, C], response: Option[Boolean]): Option[E] = {
    child.send(response) match {
      case Yielded(event) => Some(event)
      case Returned(_) => None
    }
  }

  @tailrec
  private def advance(response: Option[Boolean]): Step[E, R] = {
    step(response) match {
      case Emit(event) => Yielded(event)
      case Return(value) =>
        outcome = Some(value)
        Returned(value)
      case Call(child) =>
        resumeChild(child, None) match {
          case Some(event) =>
            delegate = Some(child)
            Yielded(event)
          case None => advance(None)
        }
    }
  }

  /** The delegation chain, outermost first, with locals sorted by name. */
  final def frames: List[Coroutine.Frame] = {
    val own = Coroutine.Frame(name, site, locals.sortBy(_._1))
    own :: delegate.map(_.frames).getOrElse(Nil)
  }
}
