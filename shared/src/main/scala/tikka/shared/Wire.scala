package tikka.shared

import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import io.circe.HCursor
import io.circe.Json
import io.circe.JsonObject
import io.circe.derivation.Configuration
import io.circe.derivation.ConfiguredCodec
import io.circe.syntax.*
import sttp.tapir.FieldName
import sttp.tapir.Schema
import sttp.tapir.SchemaType

/** A field that may be absent, explicitly null, or set.
  *
  * `parent: null` clears a parent while an absent `parent` leaves it alone, and only the cursor can tell those apart.
  */
enum Tri[+A]:
  case Missing
  case Cleared
  case Set(value: A)

object Tri:
  given [A](using Decoder[A]): Decoder[Tri[A]] = new Decoder[Tri[A]]:
    def apply(cursor: HCursor): Decoder.Result[Tri[A]] =
      if cursor.value.isNull then Right(Cleared) else cursor.as[A].map(Set(_))

    override def tryDecode(cursor: io.circe.ACursor): Decoder.Result[Tri[A]] = cursor match
      case value: HCursor => apply(value)
      case _              => Right(Missing)

  /** On the wire a tri-state field is just an optional, nullable value. */
  given [A](using schema: Schema[A]): Schema[Tri[A]] = schema.asOption.as[Tri[A]]

  given [A](using Encoder[A]): Encoder[Tri[A]] =
    case Missing    => Json.Null
    case Cleared    => Json.Null
    case Set(value) => value.asJson

/** The shapes on the wire, for both HTTP and MCP.
  *
  * They are flat and snake_case exactly as the tool contract states them, which is also what makes a derived tapir
  * `Schema` agree with the derived codec: the MCP client validates `structuredContent` against the schema tikka
  * declared, so a rich domain type behind a flattened encoder would fail there.
  */
object Wire:
  // Named: anonymous givens of two types both called Configuration get the same synthesized name and shadow each other.
  given codecConfiguration: Configuration =
    Configuration.default.withSnakeCaseMemberNames.withSnakeCaseConstructorNames

  // The schema must name fields exactly as the codec does, or a client validating results against it will refuse them.
  given schemaConfiguration: sttp.tapir.generic.Configuration =
    sttp.tapir.generic.Configuration.default.withSnakeCaseMemberNames

  // Responses

  final case class RowOut(
      id: String,
      title: String,
      status: String,
      resolution: Option[String],
      assignee: Option[String],
      labels: List[String],
      parent: Option[String],
      blocked: Boolean,
      rank: Double,
      updated: String
  ) derives ConfiguredCodec,
        Schema

  object RowOut:
    def from(row: Row): RowOut = RowOut(
      row.id.render,
      row.title.value,
      if row.state.isOpen then "open" else "closed",
      row.state.resolution.map(_.value),
      row.assignee.map(_.value),
      row.labels.map(_.value),
      row.parent.map(_.render),
      row.blocked,
      row.rank.value,
      row.updated.value
    )

  final case class RefOut(id: String, title: String, status: String) derives ConfiguredCodec, Schema

  object RefOut:
    def from(ref: IssueRef): RefOut =
      RefOut(ref.id.render, ref.title.value, if ref.state.isOpen then "open" else "closed")

  final case class CommentOut(seq: Long, at: String, actor: String, text: String) derives ConfiguredCodec, Schema

  object CommentOut:
    def from(comment: Comment): CommentOut =
      CommentOut(comment.seq.value, comment.at.value, comment.actor.render, comment.text)

  /** `before` and `after` rather than `old` and `new`: one is a Scala keyword, and the contract speaks of a change's
    * before and after text anyway.
    */
  final case class ChangeOut(field: String, op: String, before: Option[String], after: Option[String])
      derives ConfiguredCodec,
        Schema

  object ChangeOut:
    def from(change: Change): ChangeOut =
      ChangeOut(change.field.value, change.op.value, change.old, change.updated)

  final case class EventOut(
      seq: Long,
      at: String,
      actor: String,
      issue: String,
      changes: List[ChangeOut],
      comment: Option[String]
  ) derives ConfiguredCodec,
        Schema

  object EventOut:
    def from(event: Event): EventOut = EventOut(
      event.seq.value,
      event.at.value,
      event.actor.render,
      event.subject.render,
      event.changes.map(ChangeOut.from),
      event.comment
    )

  final case class EventsOut(events: List[EventOut], total: Int, truncated: Boolean) derives ConfiguredCodec, Schema

  object EventsOut:
    def from(page: EventPage): EventsOut =
      EventsOut(page.events.map(EventOut.from), page.total, page.truncated)

  final case class IssueOut(
      id: String,
      title: String,
      status: String,
      resolution: Option[String],
      assignee: Option[String],
      labels: List[String],
      parent: Option[String],
      blocked: Boolean,
      rank: Double,
      updated: String,
      body: String,
      version: Int,
      created: String,
      claimedAt: Option[String],
      children: List[RefOut],
      blockers: List[RefOut],
      blocking: List[RefOut],
      mentions: List[RefOut],
      backlinks: List[RefOut],
      comments: List[CommentOut],
      events: Option[EventsOut]
  ) derives ConfiguredCodec,
        Schema

  object IssueOut:
    def from(view: IssueView): IssueOut =
      val detail = view.detail
      val row = RowOut.from(detail.row)
      IssueOut(
        row.id,
        row.title,
        row.status,
        row.resolution,
        row.assignee,
        row.labels,
        row.parent,
        row.blocked,
        row.rank,
        row.updated,
        detail.body.value,
        detail.version.value,
        detail.created.value,
        detail.claimedAt.map(_.value),
        detail.children.map(RefOut.from),
        detail.blockers.map(RefOut.from),
        detail.blocking.map(RefOut.from),
        detail.mentions.map(RefOut.from),
        detail.backlinks.map(RefOut.from),
        detail.comments.map(CommentOut.from),
        view.events.map(EventsOut.from)
      )

  final case class SearchOut(
      effectiveQuery: String,
      issues: List[RowOut],
      nextCursor: Option[String],
      hasMore: Boolean
  ) derives ConfiguredCodec,
        Schema

  object SearchOut:
    def from(page: SearchPage): SearchOut =
      SearchOut(page.effectiveQuery, page.issues.map(RowOut.from), page.nextCursor, page.hasMore)

  final case class WrittenOut(row: RowOut, version: Int) derives ConfiguredCodec, Schema

  object WrittenOut:
    def from(written: Written): WrittenOut = WrittenOut(RowOut.from(written.row), written.version.value)

  final case class ClaimedOut(row: RowOut, claimedAt: String) derives ConfiguredCodec, Schema

  object ClaimedOut:
    def from(claimed: Claimed): ClaimedOut = ClaimedOut(RowOut.from(claimed.row), claimed.claimedAt.value)

  final case class ClosedOutWire(row: RowOut, newlyUnblocked: List[RowOut]) derives ConfiguredCodec, Schema

  object ClosedOutWire:
    def from(closed: ClosedOut): ClosedOutWire =
      ClosedOutWire(RowOut.from(closed.row), closed.newlyUnblocked.map(RowOut.from))

  final case class RowOnly(row: RowOut) derives ConfiguredCodec, Schema

  final case class ProjectOut(key: String, name: String, created: String) derives ConfiguredCodec, Schema

  object ProjectOut:
    def from(project: Project): ProjectOut = ProjectOut(project.key.value, project.name, project.created.value)

  final case class MetaOut(version: String, protocols: List[String]) derives ConfiguredCodec, Schema

  final case class FeedOut(events: List[EventOut], nextAfter: Long, hasMore: Boolean) derives ConfiguredCodec, Schema

  final case class HistoryOut(events: List[EventOut], nextCursor: Option[String], hasMore: Boolean)
      derives ConfiguredCodec,
        Schema

  // Requests

  final case class ProjectIn(key: String, name: String) derives ConfiguredCodec, Schema

  final case class BodyEditIn(old: String, replacement: String) derives ConfiguredCodec, Schema

  final case class CreateIn(
      title: String,
      project: Option[String] = None,
      body: Option[String] = None,
      labels: Option[List[String]] = None,
      parent: Option[String] = None,
      blockedBy: Option[List[String]] = None,
      rank: Option[Double] = None,
      rankBefore: Option[String] = None,
      rankAfter: Option[String] = None,
      comment: Option[String] = None
  ) derives ConfiguredCodec,
        Schema

  final case class UpdateIn(
      expectedVersion: Option[Int] = None,
      title: Option[String] = None,
      body: Option[String] = None,
      bodyEdits: Option[List[BodyEditIn]] = None,
      labelsAdd: Option[List[String]] = None,
      labelsRemove: Option[List[String]] = None,
      parent: Tri[String] = Tri.Missing,
      blockedByAdd: Option[List[String]] = None,
      blockedByRemove: Option[List[String]] = None,
      rank: Option[Double] = None,
      rankBefore: Option[String] = None,
      rankAfter: Option[String] = None,
      comment: Option[String] = None
  ) derives ConfiguredCodec,
        Schema

  final case class ClaimIn(assignee: String, comment: Option[String] = None) derives ConfiguredCodec, Schema

  final case class ReassignIn(from: String, to: String, comment: Option[String] = None) derives ConfiguredCodec, Schema

  final case class CloseIn(resolution: String, comment: String) derives ConfiguredCodec, Schema

  final case class ReopenIn(comment: String) derives ConfiguredCodec, Schema

  final case class SearchIn(query: Option[String] = None, cursor: Option[String] = None, limit: Option[Int] = None)
      derives ConfiguredCodec,
        Schema

  final case class GetIn(id: String, include: Option[List[String]] = None) derives ConfiguredCodec, Schema

  /** The contract's error body: the code, a sentence for the model, and the facts needed to act, flattened alongside.
    */
  final case class ErrorOut(error: String, message: String, facts: JsonObject)

  object ErrorOut:
    // The code and message lead, so a human reading the raw body sees what happened before the facts.
    given Encoder[ErrorOut] = error =>
      Json.fromFields(List("error" -> error.error.asJson, "message" -> error.message.asJson) ++ error.facts.toList)

    given Decoder[ErrorOut] = cursor =>
      for
        error <- cursor.get[String]("error")
        message <- cursor.get[String]("message")
        facts <- cursor.as[JsonObject]
      yield ErrorOut(error, message, facts.remove("error").remove("message"))

    given Codec[ErrorOut] = Codec.from(summon[Decoder[ErrorOut]], summon[Encoder[ErrorOut]])

    /** Declares the two fields every rejection has. The facts beside them vary by code, so they stay undeclared. */
    given Schema[ErrorOut] = Schema(
      SchemaType.SProduct(
        List(
          SchemaType
            .SProductField[ErrorOut, String](FieldName("error"), Schema.schemaForString, value => Some(value.error)),
          SchemaType
            .SProductField[ErrorOut, String](FieldName("message"), Schema.schemaForString, value => Some(value.message))
        )
      ),
      Some(Schema.SName("ErrorOut"))
    )
