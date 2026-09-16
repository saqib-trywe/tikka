package tikka.shared

/** Where an issue goes in the order. Relative placements resolve to a midpoint inside the write transaction, because
  * reading the neighbours first would race.
  */
enum RankPlacement:
  case At(rank: Rank)
  case Before(id: IssueId)
  case After(id: IssueId)

/** Replace the whole body, or apply exact replacements to it. */
enum BodyChange:
  case Replace(body: Body)
  case Edits(edits: List[BodyEdit])

/** One exact, unique-match replacement. Needs no version: an edit whose `old` text is still present is safe. */
final case class BodyEdit(old: String, replacement: String)

/** Set a parent or clear it. `Option[ParentChange]` then means "leave it alone" versus "change it". */
enum ParentChange:
  case SetTo(id: IssueId)
  case Clear

final case class CreateIssue(
    project: Option[ProjectKey],
    title: Title,
    body: Option[Body],
    labels: List[Label],
    parent: Option[IssueId],
    blockedBy: List[IssueId],
    rank: Option[RankPlacement],
    comment: Option[String]
)

/** Everything a generic update may touch. The assignee is deliberately absent: it moves only through claim, release and
  * reassign, each conditional on who holds the issue now.
  */
final case class UpdateIssue(
    expectedVersion: Option[Version],
    title: Option[Title],
    body: Option[BodyChange],
    labelsAdd: List[Label],
    labelsRemove: List[Label],
    parent: Option[ParentChange],
    blockedByAdd: List[IssueId],
    blockedByRemove: List[IssueId],
    rank: Option[RankPlacement],
    comment: Option[String]
)

object UpdateIssue:
  /** Nothing touched: the starting point callers copy from, and a comment-only write on its own. */
  val nothing: UpdateIssue = UpdateIssue(None, None, None, Nil, Nil, None, Nil, Nil, None, None)
