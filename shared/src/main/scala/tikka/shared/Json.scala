package tikka.shared

import io.circe.Encoder
import io.circe.Json
import io.circe.syntax.*

/** How the contract's types are written as JSON.
  *
  * The export writes these, and the HTTP and MCP adapters will read the same shapes, so a record on disk and a record
  * on the wire never drift. Names are snake_case, matching the tool contract.
  */
object Codec:
  given Encoder[ProjectKey] = Encoder[String].contramap(_.value)
  given Encoder[IssueNumber] = Encoder[Int].contramap(_.value)
  given Encoder[IssueId] = Encoder[String].contramap(_.render)
  given Encoder[Title] = Encoder[String].contramap(_.value)
  given Encoder[Body] = Encoder[String].contramap(_.value)
  given Encoder[Label] = Encoder[String].contramap(_.value)
  given Encoder[Assignee] = Encoder[String].contramap(_.value)
  given Encoder[Rank] = Encoder[Double].contramap(_.value)
  given Encoder[Version] = Encoder[Int].contramap(_.value)
  given Encoder[EventSeq] = Encoder[Long].contramap(_.value)
  given Encoder[Timestamp] = Encoder[String].contramap(_.value)
  given Encoder[Resolution] = Encoder[String].contramap(_.value)
  given Encoder[ChangeField] = Encoder[String].contramap(_.value)
  given Encoder[ChangeOp] = Encoder[String].contramap(_.value)
  given Encoder[Actor] = Encoder[String].contramap(_.render)

  given Encoder[Project] = project =>
    Json.obj("key" -> project.key.asJson, "name" -> project.name.asJson, "created" -> project.created.asJson)

  given Encoder[Row] = row =>
    Json.obj(
      "id" -> row.id.asJson,
      "title" -> row.title.asJson,
      "status" -> (if row.state.isOpen then "open" else "closed").asJson,
      "resolution" -> row.state.resolution.asJson,
      "assignee" -> row.assignee.asJson,
      "labels" -> row.labels.asJson,
      "parent" -> row.parent.asJson,
      "blocked" -> row.blocked.asJson,
      "rank" -> row.rank.asJson,
      "updated" -> row.updated.asJson
    )

  given Encoder[IssueRef] = ref =>
    Json.obj(
      "id" -> ref.id.asJson,
      "title" -> ref.title.asJson,
      "status" -> (if ref.state.isOpen then "open" else "closed").asJson
    )

  given Encoder[Comment] = comment =>
    Json.obj(
      "seq" -> comment.seq.asJson,
      "at" -> comment.at.asJson,
      "actor" -> comment.actor.asJson,
      "text" -> comment.text.asJson
    )

  given Encoder[Change] = change =>
    Json.obj(
      "field" -> change.field.asJson,
      "op" -> change.op.asJson,
      "old" -> change.old.asJson,
      "new" -> change.updated.asJson
    )

  given Encoder[Event] = event =>
    Json.obj(
      "seq" -> event.seq.asJson,
      "at" -> event.at.asJson,
      "actor" -> event.actor.asJson,
      "issue" -> event.subject.asJson,
      "changes" -> event.changes.asJson,
      "comment" -> event.comment.asJson
    )

  given Encoder[IssueDetail] = detail =>
    detail.row.asJson.deepMerge(
      Json.obj(
        "body" -> detail.body.asJson,
        "version" -> detail.version.asJson,
        "created" -> detail.created.asJson,
        "claimed_at" -> detail.claimedAt.asJson,
        "children" -> detail.children.asJson,
        "blockers" -> detail.blockers.asJson,
        "blocking" -> detail.blocking.asJson,
        "mentions" -> detail.mentions.asJson,
        "backlinks" -> detail.backlinks.asJson,
        "comments" -> detail.comments.asJson
      )
    )
