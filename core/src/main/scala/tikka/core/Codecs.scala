package tikka.core

import doobie.*
import tikka.shared.*

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Column mappings for the domain's opaque types.
  *
  * Values read back are `trusted`: they were validated on the way in, and the database's own `CHECK` constraints are
  * the backstop against hand edits.
  */
object Codecs:
  given Meta[ProjectKey] = Meta[String].timap(ProjectKey.trusted)(_.value)
  given Meta[IssueNumber] = Meta[Int].timap(IssueNumber.trusted)(_.value)
  given Meta[Title] = Meta[String].timap(Title.trusted)(_.value)
  given Meta[Body] = Meta[String].timap(Body.apply)(_.value)
  given Meta[Assignee] = Meta[String].timap(Assignee.trusted)(_.value)
  given Meta[Label] = Meta[String].timap(Label.trusted)(_.value)
  given Meta[Rank] = Meta[Double].timap(Rank.trusted)(_.value)
  given Meta[Version] = Meta[Int].timap(Version.trusted)(_.value)
  given Meta[EventSeq] = Meta[Long].timap(EventSeq.trusted)(_.value)
  given Meta[Timestamp] = Meta[String].timap(Timestamp.trusted)(_.value)

  given Meta[Resolution] = Meta[String].timap(text => Resolution.parse(text).fold(fail, identity))(_.value)

  given Meta[ChangeField] = Meta[String].timap(text => ChangeField.parse(text).fold(fail, identity))(_.value)

  given Meta[ChangeOp] = Meta[String].timap(text => ChangeOp.parse(text).fold(fail, identity))(_.value)

  given Meta[Actor] = Meta[String].timap(text => Actor.parse(text).fold(fail, identity))(_.render)

  private def fail(reason: String): Nothing =
    throw IllegalStateException(s"the store holds a value tikka cannot read: $reason")

/** The daemon's clock, the only source of time a write may use. Injected so tests can hold it still. */
trait Clock:
  def now: cats.effect.IO[Timestamp]

object Clock:
  private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  def system: Clock = new Clock:
    def now: cats.effect.IO[Timestamp] = cats.effect.IO.realTimeInstant.map(render)

  def render(instant: Instant): Timestamp = Timestamp.trusted(format.format(instant))
