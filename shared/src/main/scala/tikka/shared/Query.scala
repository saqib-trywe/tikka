package tikka.shared

/** The query, as one syntax on every surface: a positional CLI argument, `?q=` over HTTP, the UI's search box, and
  * MCP's `query` argument.
  *
  * Filters are ANDed, values within one filter are ORed, and a leading `-` negates a whole filter. There are no
  * parentheses and no cross-filter OR: that is where a query language begins, and tikka refused one.
  */

enum ProjectScope:
  case Every
  case Keys(keys: List[ProjectKey])

/** Exact, or a trailing `*` prefix match, as `label:wayfinder:*` and a per-session assignee prefix use. */
enum NameMatch:
  case Exact(value: String)
  case Prefix(value: String)

object NameMatch:
  extension (matcher: NameMatch)
    def render: String = matcher match
      case Exact(value)  => value
      case Prefix(value) => s"$value*"

enum AssigneeMatch:
  case Nobody
  case Anyone
  case Named(name: NameMatch)

object AssigneeMatch:
  extension (matcher: AssigneeMatch)
    def render: String = matcher match
      case Nobody      => "none"
      case Anyone      => "any"
      case Named(name) => name.render

/** Open or closed, as a query asks for it. */
enum StatusValue:
  case Open, Closed

object StatusValue:
  extension (status: StatusValue)
    def render: String = status match
      case Open   => "open"
      case Closed => "closed"

enum TimeField:
  case Created, Updated, Closed, Claimed

object TimeField:
  extension (field: TimeField)
    def render: String = field match
      case Created => "created"
      case Updated => "updated"
      case Closed  => "closed"
      case Claimed => "claimed"

enum TimeDirection:
  case Before, After

object TimeDirection:
  extension (direction: TimeDirection)
    def render: String = direction match
      case Before => "before"
      case After  => "after"

enum TimeUnit:
  case Minutes, Hours, Days, Weeks

object TimeUnit:
  extension (unit: TimeUnit)
    def render: String = unit match
      case Minutes => "m"
      case Hours   => "h"
      case Days    => "d"
      case Weeks   => "w"

/** When a time filter points at. Relative durations are resolved against the daemon's clock, never the caller's. */
enum Moment:
  case Ago(amount: Int, unit: TimeUnit)
  case OnDate(text: String)
  case AtDateTime(text: String)

object Moment:
  extension (moment: Moment)
    def render: String = moment match
      case Ago(amount, unit) => s"$amount${unit.render}"
      case OnDate(text)      => text
      case AtDateTime(text)  => text

enum SortField:
  case Rank, Created, Updated, Closed

object SortField:
  extension (field: SortField)
    def render: String = field match
      case Rank    => "rank"
      case Created => "created"
      case Updated => "updated"
      case Closed  => "closed"

enum SortDirection:
  case Ascending, Descending

/** Every sort breaks ties by project key and issue number, so the order is always total — which is what cursor paging
  * needs.
  */
final case class Sort(field: SortField, direction: SortDirection):
  def render: String =
    val suffix = direction match
      case SortDirection.Ascending  => "asc"
      case SortDirection.Descending => "desc"
    s"sort:${field.render}-$suffix"

object Sort:
  val default: Sort = Sort(SortField.Rank, SortDirection.Ascending)

  /** Rank reads forwards; times read newest first, which is what a reader means by "sort by updated". */
  def natural(field: SortField): Sort = field match
    case SortField.Rank => Sort(field, SortDirection.Ascending)
    case _              => Sort(field, SortDirection.Descending)

enum Filter:
  case Project(scope: ProjectScope)
  case Status(values: List[StatusValue])
  // Qualified: inside the enum, `Resolution` is the case being defined, not the domain type.
  case Resolution(values: List[tikka.shared.Resolution])
  case Assignee(values: List[AssigneeMatch])
  case Label(values: List[NameMatch])
  case Parent(ids: List[IssueId])
  case Under(ids: List[IssueId])
  case Blocks(ids: List[IssueId])
  case BlockedBy(ids: List[IssueId])
  case Mentions(ids: List[IssueId])
  case MentionedBy(ids: List[IssueId])
  case Ids(ids: List[IssueId])
  case Blocked
  case Unblocked

  /** Exactly `status:open unblocked assignee:none`, kept whole so it prints back as `ready`. */
  case Ready
  case Time(field: TimeField, direction: TimeDirection, moment: Moment)
  case Text(value: String)

object Filter:
  extension (filter: Filter)
    def render: String = filter match
      case Project(ProjectScope.Every)      => "project:*"
      case Project(ProjectScope.Keys(keys)) => s"project:${keys.map(_.value).mkString(",")}"
      case Status(values)                   => s"status:${values.map(_.render).mkString(",")}"
      case Resolution(values)               => s"resolution:${values.map(_.value).mkString(",")}"
      case Assignee(values)                 => s"assignee:${values.map(_.render).mkString(",")}"
      case Label(values)                    => s"label:${values.map(_.render).mkString(",")}"
      case Parent(ids)                      => s"parent:${renderIds(ids)}"
      case Under(ids)                       => s"under:${renderIds(ids)}"
      case Blocks(ids)                      => s"blocks:${renderIds(ids)}"
      case BlockedBy(ids)                   => s"blocked-by:${renderIds(ids)}"
      case Mentions(ids)                    => s"mentions:${renderIds(ids)}"
      case MentionedBy(ids)                 => s"mentioned-by:${renderIds(ids)}"
      case Ids(ids)                         => s"id:${renderIds(ids)}"
      case Blocked                          => "blocked"
      case Unblocked                        => "unblocked"
      case Ready                            => "ready"
      case Time(field, direction, moment)   => s"${field.render}-${direction.render}:${moment.render}"
      case Text(value)                      => s"text:${quoted(value)}"

  private def renderIds(ids: List[IssueId]): String = ids.map(_.render).mkString(",")

  private def quoted(value: String): String =
    if value.exists(_.isWhitespace) then s""""$value"""" else value

final case class Term(negated: Boolean, filter: Filter):
  def render: String = if negated then s"-${filter.render}" else filter.render

/** A whole query. An empty one is valid and matches everything in scope. */
final case class Query(terms: List[Term], sort: Sort):
  def render: String =
    val parts = terms.map(_.render) ++ Option.when(sort != Sort.default)(sort.render)
    parts.mkString(" ")

  def withoutProjectScope: Boolean = !terms.exists(term => term.filter.isInstanceOf[Filter.Project])

object Query:
  val everything: Query = Query(Nil, Sort.default)
