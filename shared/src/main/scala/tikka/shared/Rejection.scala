package tikka.shared

import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import tikka.shared.Wire.ErrorOut

/** Turns a domain rejection into the contract's error body.
  *
  * The message is written for the model reading it: what happened, the conflicting facts, and what to do instead. The
  * facts travel beside it, so an agent can act without asking a follow-up question.
  */
object Rejection:
  def of(error: DomainError): ErrorOut = error match
    case DomainError.NotFound(id) =>
      body(error, s"${id.render} does not exist.", "id" -> id.render.asJson)
    case DomainError.UnknownProject(requested, known) =>
      body(
        error,
        s"There is no project ${requested.value}. Projects: ${names(known.map(_.value))}.",
        "requested" -> requested.value.asJson,
        "valid_keys" -> known.map(_.value).asJson
      )
    case DomainError.InvalidArgument(argument, reason) =>
      body(error, s"`$argument`: $reason.", "argument" -> argument.asJson, "reason" -> reason.asJson)
    case DomainError.InvalidQuery(token, reason, suggestions) =>
      val hint =
        if suggestions.isEmpty then "" else s" Did you mean ${suggestions.map(name => s"`$name`").mkString(" or ")}?"
      body(
        error,
        s"The query could not be read at `$token`: $reason.$hint",
        "token" -> token.asJson,
        "reason" -> reason.asJson,
        "suggestions" -> suggestions.asJson
      )
    case DomainError.StaleVersion(current, title, body, labels) =>
      val facts = List("current_version" -> current.value.asJson) ++
        title.map(value => "title" -> value.value.asJson) ++
        body.map(value => "body" -> value.value.asJson) ++
        labels.map(values => "labels" -> values.map(_.value).asJson)
      this.body(
        error,
        s"The issue changed since you read it and is now at version ${current.value}. Its current values are included; " +
          "reapply your change to them, or retry without expected_version to overwrite.",
        facts*
      )
    case DomainError.EditMismatch(edit, kind) =>
      val why = kind match
        case EditMismatchKind.Missing   => "does not appear in the body"
        case EditMismatchKind.Ambiguous => "appears more than once, so include more surrounding text"
      body(
        error,
        s"An edit's `old` text $why. No edits were applied.",
        "old" -> edit.old.asJson,
        "kind" -> (if kind == EditMismatchKind.Missing then "missing" else "ambiguous").asJson
      )
    case DomainError.ClaimConflict(holder, since) =>
      val when = since.fold("")(at => s" (since ${at.value})")
      body(
        error,
        s"This issue is already claimed by `${holder.value}`$when. Choose another issue from `search_issues ready`, " +
          s"or if that claim is stale, `reassign_issue` from `${holder.value}`.",
        "holder" -> holder.value.asJson,
        "claimed_at" -> since.map(_.value).asJson
      )
    case DomainError.NotHolder(actual) =>
      val who = actual.fold("nobody holds it")(name => s"it is held by `${name.value}`")
      body(error, s"This write names the wrong holder: $who.", "holder" -> actual.map(_.value).asJson)
    case DomainError.IssueClosed(resolution, at) =>
      body(
        error,
        s"The issue was closed ${resolution.value} at ${at.value}, so its assignee is the record of who resolved it. " +
          "Reopen it first if the work is live again.",
        "resolution" -> resolution.value.asJson,
        "closed_at" -> at.value.asJson
      )
    case DomainError.OpenChildren(children) =>
      body(
        error,
        s"A closed issue has no open children, and these are open: ${refs(children)}. Close or move them first.",
        "children" -> children.map(ref).asJson
      )
    case DomainError.OpenBlockers(blockers) =>
      body(
        error,
        s"An issue closed done has no open blockers, and these are open: ${refs(blockers)}. Close them first, " +
          "or close this one as dropped.",
        "blockers" -> blockers.map(ref).asJson
      )
    case DomainError.Cycle(path) =>
      body(
        error,
        s"That edge would form a cycle: ${path.map(_.render).mkString(" → ")}.",
        "path" -> path.map(_.render).asJson
      )
    case DomainError.CrossProjectEdge(from, to) =>
      body(
        error,
        s"Parent and blocking edges stay within one project, but ${from.render} and ${to.render} are in different ones. " +
          "Mention the other issue in prose instead.",
        "from" -> from.render.asJson,
        "to" -> to.render.asJson
      )
    case DomainError.ResolutionImmutable(current) =>
      body(
        error,
        s"The issue is already closed ${current.value}, and a resolution cannot change while it stays closed.",
        "resolution" -> current.value.asJson
      )
    case DomainError.ReopenBlocked(obstacles) =>
      body(
        error,
        s"Reopening would break an invariant because of ${refs(obstacles)}: a closed parent, or an issue this one blocks " +
          "that is already done.",
        "obstacles" -> obstacles.map(ref).asJson
      )
    case DomainError.RankPrecision(before, after) =>
      body(
        error,
        s"There is no room left between ranks ${before.value} and ${after.value}. Give an explicit rank instead.",
        "before" -> before.value.asJson,
        "after" -> after.value.asJson
      )

  private def body(error: DomainError, message: String, facts: (String, Json)*): ErrorOut =
    ErrorOut(error.code.value, message, JsonObject.fromIterable(facts))

  private def ref(value: IssueRef): Json = Wire.RefOut.from(value).asJson

  private def refs(values: List[IssueRef]): String =
    values.map(value => s"${value.id.render} (${value.title.value})").mkString(", ")

  private def names(values: List[String]): String = if values.isEmpty then "none yet" else values.mkString(", ")
