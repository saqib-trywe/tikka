package tikka.ui

import com.raquo.airstream.core.EventStream
import com.raquo.airstream.core.Signal
import org.scalajs.dom
import tikka.shared.Wire.EventOut
import tikka.shared.Wire.RowOut

/** Which way a reorder moves an issue. */
enum Move:
  case Up, Down

/** Where a reorder puts an issue: just before, or just after, a neighbour. */
final case class Placement(target: String, before: Boolean)

/** The view logic that has nothing to do with the DOM, so it can be tested on its own. */
object Logic:
  /** What a view has to fetch: what the page asks for as soon as anyone is listening, and again on every refresh.
    *
    * Derived from the page rather than pushed into an `EventBus` when the page arrives. A bus drops what it is given
    * before its own subscriber starts, and that is exactly the order a view mounts in: the page signal fires its
    * current value the instant it is subscribed, which can be before the binder that would have acted on it. A view
    * wired that way sits on "loading…" forever.
    */
  def loads[A](page: Signal[A], refresh: EventStream[Any]): EventStream[A] =
    page.flatMapSwitch(current => EventStream.fromValue(current).mergeWith(refresh.mapTo(current)))

  /** An event concerns an issue when it was written to it, or names it among the issues it also touched. */
  def concerns(event: EventOut, id: String): Boolean = event.issue == id || event.related.contains(id)

  /** A reorder places the issue on the far side of its neighbour, which is what the neighbour-relative ranks mean. */
  def placement(rows: List[RowOut], id: String, move: Move): Option[Placement] =
    val index = rows.indexWhere(_.id == id)
    if index < 0 then None
    else
      move match
        case Move.Up   => Option.when(index > 0)(Placement(rows(index - 1).id, before = true))
        case Move.Down => Option.when(index < rows.size - 1)(Placement(rows(index + 1).id, before = false))

  /** How a row reads at a glance: its state, who holds it, and whether anything blocks it. */
  def state(row: RowOut): String =
    val status = row.resolution.fold(row.status)(resolution => s"${row.status} $resolution")
    val claim = row.assignee.map(name => s"claimed by $name")
    val blocked = Option.when(row.blocked)("blocked")
    (status :: claim.toList ++ blocked.toList).mkString(" · ")

  /** The classes a row carries, so the stylesheet can mark open, closed, claimed and blocked work. */
  def rowClasses(row: RowOut): List[String] =
    List("row", row.status) ++ row.assignee.map(_ => "claimed") ++ Option.when(row.blocked)("blocked")

  /** What the update bar says once a list has fallen behind. Lists never reshuffle under the reader. */
  def updateBar(count: Int): Option[String] =
    Option.when(count > 0)(if count == 1 then "1 update — refresh" else s"$count updates — refresh")

  private val lastQueryKey = "tikka.lastQuery"

  /** The landing view is your last query, or `ready` on a first visit. */
  def lastQuery(): String =
    Option(dom.window.localStorage.getItem(lastQueryKey)).filter(_.nonEmpty).getOrElse("ready")

  def rememberQuery(query: String): Unit =
    dom.window.localStorage.setItem(lastQueryKey, query)
