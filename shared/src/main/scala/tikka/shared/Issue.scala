package tikka.shared

/** Why an issue was closed: the work was carried out, or it was abandoned. */
enum Resolution:
  case Done, Dropped

object Resolution:
  def parse(value: String): Either[String, Resolution] = value match
    case "done"    => Right(Done)
    case "dropped" => Right(Dropped)
    case other     => Left(s"'$other' is not a resolution: expected done or dropped")

  extension (resolution: Resolution)
    def value: String = resolution match
      case Done    => "done"
      case Dropped => "dropped"

/** Open or closed, and nothing else. A closed issue always has a resolution, so the two travel together. */
enum IssueState:
  case Open
  case Closed(resolution: Resolution, at: Timestamp)

object IssueState:
  extension (state: IssueState)
    def isOpen: Boolean = state match
      case Open         => true
      case Closed(_, _) => false

    def resolution: Option[Resolution] = state match
      case Open             => None
      case Closed(value, _) => Some(value)

/** The compact shape every write returns and every search row uses. Never carries a body. */
final case class Row(
    id: IssueId,
    title: Title,
    state: IssueState,
    assignee: Option[Assignee],
    labels: List[Label],
    parent: Option[IssueId],
    blocked: Boolean,
    rank: Rank,
    updated: Timestamp
)

/** An issue named from somewhere else: an edge, a backlink, or the obstacle in an error. */
final case class IssueRef(id: IssueId, title: Title, state: IssueState)

/** A remark, appended to an issue and never edited. Comments are the events that carry one. */
final case class Comment(seq: EventSeq, at: Timestamp, actor: Actor, text: String)

/** The field an event changed. Stored as these words, so `sqlite3` reads without a decoder. */
enum ChangeField:
  case Title, Body, Status, Resolution, Assignee, Label, Parent, BlockedBy, Rank

object ChangeField:
  def parse(value: String): Either[String, ChangeField] = value match
    case "title"      => Right(Title)
    case "body"       => Right(Body)
    case "status"     => Right(Status)
    case "resolution" => Right(Resolution)
    case "assignee"   => Right(Assignee)
    case "label"      => Right(Label)
    case "parent"     => Right(Parent)
    case "blocked_by" => Right(BlockedBy)
    case "rank"       => Right(Rank)
    case other        => Left(s"'$other' is not a change field")

  extension (field: ChangeField)
    def value: String = field match
      case Title      => "title"
      case Body       => "body"
      case Status     => "status"
      case Resolution => "resolution"
      case Assignee   => "assignee"
      case Label      => "label"
      case Parent     => "parent"
      case BlockedBy  => "blocked_by"
      case Rank       => "rank"

enum ChangeOp:
  case Set, Add, Remove

object ChangeOp:
  def parse(value: String): Either[String, ChangeOp] = value match
    case "set"    => Right(Set)
    case "add"    => Right(Add)
    case "remove" => Right(Remove)
    case other    => Left(s"'$other' is not a change operation")

  extension (op: ChangeOp)
    def value: String = op match
      case Set    => "set"
      case Add    => "add"
      case Remove => "remove"

/** One field's movement within an event: `old` is absent when nothing was there, `updated` when it was cleared. */
final case class Change(field: ChangeField, op: ChangeOp, old: Option[String], updated: Option[String])

/** One write, however many fields it touched, plus the comment that explains it. */
final case class Event(
    seq: EventSeq,
    at: Timestamp,
    actor: Actor,
    subject: IssueId,
    changes: List[Change],
    comment: Option[String]
)

/** Everything `get_issue` returns. Events come only when asked for. */
final case class IssueDetail(
    row: Row,
    body: Body,
    version: Version,
    created: Timestamp,
    claimedAt: Option[Timestamp],
    children: List[IssueRef],
    blockers: List[IssueRef],
    blocking: List[IssueRef],
    mentions: List[IssueRef],
    backlinks: List[IssueRef],
    comments: List[Comment]
)

/** A project: a namespace for issues and the sequence that numbers them. */
final case class Project(key: ProjectKey, name: String, created: Timestamp)

/** What a write returns: the row, plus the version a careful caller guards its next write with. */
final case class Written(row: Row, version: Version)

final case class Claimed(row: Row, claimedAt: Timestamp)

/** Closing a blocker frees its dependents, and the closer is told which, so the consequence is never invisible. */
final case class ClosedOut(row: Row, newlyUnblocked: List[Row])

/** The latest slice of a timeline. Full history lives over HTTP. */
final case class EventPage(events: List[Event], total: Int, truncated: Boolean)

final case class IssueView(detail: IssueDetail, events: Option[EventPage])

/** A page of search results. `effectiveQuery` shows the scoping that was actually applied, so a bound connection's
  * results are never a mystery.
  */
final case class SearchPage(effectiveQuery: String, issues: List[Row], nextCursor: Option[String], hasMore: Boolean)

/** A slice of the event log, oldest first, and whether more follows it. */
final case class EventSlice(events: List[Event], hasMore: Boolean)
