package tikka.shared

import tikka.shared.Wire.*

/** Turns wire requests into the core's commands. HTTP and MCP both go through here, so an argument means the same thing
  * on either surface and is refused in the same words.
  */
object Requests:
  def issueId(text: String): Either[DomainError, IssueId] = argument("id")(IssueId.parse(text))

  def projectKey(name: String, text: String): Either[DomainError, ProjectKey] = argument(name)(ProjectKey.parse(text))

  def assignee(name: String, text: String): Either[DomainError, Assignee] = argument(name)(Assignee.parse(text))

  /** `none` names nobody, so reassigning to it clears a stale claim. */
  def holder(name: String, text: String): Either[DomainError, Option[Assignee]] =
    if text == "none" then Right(None) else assignee(name, text).map(Some(_))

  def resolution(text: String): Either[DomainError, Resolution] = argument("resolution")(Resolution.parse(text))

  /** Closing and reopening always say why. */
  def requiredComment(text: String): Either[DomainError, String] =
    Either.cond(text.trim.nonEmpty, text, DomainError.InvalidArgument("comment", "say why, in a sentence or two"))

  def create(in: CreateIn): Either[DomainError, CreateIssue] =
    for
      title <- argument("title")(Title.parse(in.title))
      project <- in.project.map(projectKey("project", _)).sequence
      labels <- each("labels", in.labels)(Label.parse)
      parent <- in.parent.map(text => argument("parent")(IssueId.parse(text))).sequence
      blockedBy <- each("blocked_by", in.blockedBy)(IssueId.parse)
      rank <- placement(in.rank, in.rankBefore, in.rankAfter)
    yield CreateIssue(project, title, in.body.map(Body.apply), labels, parent, blockedBy, rank, in.comment)

  def update(in: UpdateIn): Either[DomainError, UpdateIssue] =
    for
      version <- in.expectedVersion.map(value => argument("expected_version")(Version.parse(value))).sequence
      title <- in.title.map(text => argument("title")(Title.parse(text))).sequence
      body <- bodyChange(in.body, in.bodyEdits)
      add <- each("labels_add", in.labelsAdd)(Label.parse)
      remove <- each("labels_remove", in.labelsRemove)(Label.parse)
      parent <- parentChange(in.parent)
      blocked <- each("blocked_by_add", in.blockedByAdd)(IssueId.parse)
      freed <- each("blocked_by_remove", in.blockedByRemove)(IssueId.parse)
      rank <- placement(in.rank, in.rankBefore, in.rankAfter)
    yield UpdateIssue(version, title, body, add, remove, parent, blocked, freed, rank, in.comment)

  private def bodyChange(
      body: Option[String],
      edits: Option[List[BodyEditIn]]
  ): Either[DomainError, Option[BodyChange]] =
    (body, edits) match
      case (Some(_), Some(_)) =>
        Left(DomainError.InvalidArgument("body", "give body or body_edits, not both"))
      case (Some(text), None) => Right(Some(BodyChange.Replace(Body(text))))
      case (None, Some(Nil))  => Left(DomainError.InvalidArgument("body_edits", "give at least one edit"))
      case (None, Some(list)) => Right(Some(BodyChange.Edits(list.map(edit => BodyEdit(edit.old, edit.replacement)))))
      case (None, None)       => Right(None)

  private def parentChange(parent: Tri[String]): Either[DomainError, Option[ParentChange]] = parent match
    case Tri.Missing   => Right(None)
    case Tri.Cleared   => Right(Some(ParentChange.Clear))
    case Tri.Set(text) => argument("parent")(IssueId.parse(text)).map(id => Some(ParentChange.SetTo(id)))

  private def placement(
      rank: Option[Double],
      before: Option[String],
      after: Option[String]
  ): Either[DomainError, Option[RankPlacement]] =
    (rank, before, after) match
      case (None, None, None)        => Right(None)
      case (Some(value), None, None) =>
        argument("rank")(Rank.parse(value)).map(ranked => Some(RankPlacement.At(ranked)))
      case (None, Some(id), None) =>
        argument("rank_before")(IssueId.parse(id)).map(target => Some(RankPlacement.Before(target)))
      case (None, None, Some(id)) =>
        argument("rank_after")(IssueId.parse(id)).map(target => Some(RankPlacement.After(target)))
      case _ => Left(DomainError.InvalidArgument("rank", "give at most one of rank, rank_before and rank_after"))

  private def each[A](name: String, values: Option[List[String]])(
      read: String => Either[String, A]
  ): Either[DomainError, List[A]] =
    values.getOrElse(Nil).map(value => argument(name)(read(value))).sequence

  private def argument[A](name: String)(parsed: Either[String, A]): Either[DomainError, A] =
    parsed.left.map(reason => DomainError.InvalidArgument(name, reason))

  extension [A](values: List[Either[DomainError, A]])
    private def sequence: Either[DomainError, List[A]] =
      values.foldRight(Right(Nil): Either[DomainError, List[A]])((next, rest) =>
        next.flatMap(value => rest.map(value :: _))
      )

  extension [A](value: Option[Either[DomainError, A]])
    private def sequence: Either[DomainError, Option[A]] = value match
      case None           => Right(None)
      case Some(Right(a)) => Right(Some(a))
      case Some(Left(e))  => Left(e)
