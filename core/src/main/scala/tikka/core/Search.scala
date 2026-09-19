package tikka.core

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import tikka.shared.*
// doobie also has a Query; the explicit import says which one this file means.
import tikka.shared.Query

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

import Codecs.given

/** Compiles a query into SQL.
  *
  * Derived state stays derived: `updated` is a correlated subquery over the event log rather than a stored column, so
  * it cannot drift from the timeline that defines it.
  */
private[core] object Search:
  /** Where a page left off. Ordering is total — sort key, then project key, then issue number — so the cursor names
    * exactly one row, and issues that did not change appear exactly once.
    */
  final case class Keyset(missing: Boolean, value: String, project: String, number: Int)

  private val fullTextFloor = 3

  def conditions(query: Query, now: Instant, zone: ZoneId): Either[DomainError, Fragment] =
    query.terms
      .traverse(term => filter(term.filter, now, zone).map(condition => negate(term.negated, condition)))
      .map:
        case Nil        => fr"1 = 1"
        case conditions => conditions.reduce((left, right) => left ++ fr"AND" ++ right)

  /** A negated filter must also match rows where the filter's value is absent: in SQL, `NOT NULL` is not true. */
  private def negate(negated: Boolean, condition: Fragment): Fragment =
    if negated then fr"NOT IFNULL(" ++ condition ++ fr", 0)" else fr"(" ++ condition ++ fr")"

  private def filter(value: Filter, now: Instant, zone: ZoneId): Either[DomainError, Fragment] = value match
    case Filter.Project(ProjectScope.Every)      => Right(fr"1 = 1")
    case Filter.Project(ProjectScope.Keys(keys)) => Right(anyOf(keys)(key => fr"issue.project_key = $key"))
    case Filter.Status(values)                   => Right(anyOf(values)(status => fr"issue.status = ${word(status)}"))
    case Filter.Resolution(values)               => Right(anyOf(values)(value => fr"issue.resolution = $value"))
    case Filter.Assignee(values)                 => Right(anyOf(values)(assignee))
    case Filter.Label(values)                    => Right(anyOf(values)(label))
    case Filter.Parent(values)                   =>
      Right(anyOf(values):
        case ParentMatch.Nobody => fr"issue.parent_id IS NULL"
        case ParentMatch.Of(id) => fr"issue.parent_id = (" ++ rowId(id) ++ fr")")
    case Filter.Under(ids)       => Right(anyOf(ids)(under))
    case Filter.Blocks(ids)      => Right(anyOf(ids)(blocks))
    case Filter.BlockedBy(ids)   => Right(anyOf(ids)(blockedBy))
    case Filter.Mentions(ids)    => Right(anyOf(ids)(mentions))
    case Filter.MentionedBy(ids) => Right(anyOf(ids)(mentionedBy))
    case Filter.Ids(ids)         =>
      Right(anyOf(ids)(id => fr"(issue.project_key = ${id.project} AND issue.number = ${id.number})"))
    case Filter.Blocked   => Right(blockedByAnythingOpen)
    case Filter.Unblocked => Right(fr"NOT" ++ blockedByAnythingOpen)
    // `ready` is exactly open, unblocked and unclaimed — the one place that expansion lives.
    case Filter.Ready =>
      Right(fr"(issue.status = 'open' AND issue.assignee IS NULL AND NOT" ++ blockedByAnythingOpen ++ fr")")
    case Filter.Time(field, direction, moment) =>
      resolve(moment, now, zone).map: at =>
        val column = timeColumn(field)
        val ordering = if direction == TimeDirection.Before then fr"<" else fr">"
        fr"(" ++ column ++ ordering ++ fr"$at)"
    case Filter.Text(text) => Right(fullText(text))

  private def word(status: StatusValue): String = status match
    case StatusValue.Open   => "open"
    case StatusValue.Closed => "closed"

  private def assignee(matcher: AssigneeMatch): Fragment = matcher match
    case AssigneeMatch.Nobody                        => fr"issue.assignee IS NULL"
    case AssigneeMatch.Anyone                        => fr"issue.assignee IS NOT NULL"
    case AssigneeMatch.Named(NameMatch.Exact(name))  => fr"issue.assignee = $name"
    case AssigneeMatch.Named(NameMatch.Prefix(name)) =>
      fr"issue.assignee LIKE ${startingWith(name)} ESCAPE '\'"

  private def label(matcher: NameMatch): Fragment =
    val matches = matcher match
      case NameMatch.Exact(name)  => fr"l.label = $name"
      case NameMatch.Prefix(name) => fr"l.label LIKE ${startingWith(name)} ESCAPE '\'"
    fr"EXISTS (SELECT 1 FROM issue_label l WHERE l.issue_id = issue.id AND" ++ matches ++ fr")"

  private def under(id: IssueId): Fragment =
    fr"""issue.id IN (
           WITH RECURSIVE descendant(id) AS (
             SELECT i.id FROM issue i WHERE i.parent_id = (""" ++ rowId(id) ++ fr""")
             UNION ALL
             SELECT i.id FROM issue i JOIN descendant d ON i.parent_id = d.id
           )
           SELECT id FROM descendant)"""

  private def blocks(id: IssueId): Fragment =
    fr"EXISTS (SELECT 1 FROM block_edge b WHERE b.blocker_id = issue.id AND b.blocked_id = (" ++ rowId(id) ++ fr"))"

  private def blockedBy(id: IssueId): Fragment =
    fr"EXISTS (SELECT 1 FROM block_edge b WHERE b.blocked_id = issue.id AND b.blocker_id = (" ++ rowId(id) ++ fr"))"

  private def mentions(id: IssueId): Fragment =
    fr"""EXISTS (SELECT 1 FROM mention m WHERE m.source_id = issue.id
                 AND m.target_key = ${id.project} AND m.target_number = ${id.number})"""

  private def mentionedBy(id: IssueId): Fragment =
    fr"EXISTS (SELECT 1 FROM mention m WHERE m.source_id = (" ++ rowId(id) ++
      fr" ) AND m.target_key = issue.project_key AND m.target_number = issue.number)"

  private val blockedByAnythingOpen: Fragment =
    fr"""EXISTS (SELECT 1 FROM block_edge b JOIN issue blocker ON blocker.id = b.blocker_id
                 WHERE b.blocked_id = issue.id AND blocker.status = 'open')"""

  /** The trigram index cannot answer a query shorter than three characters, so those scan instead. */
  private def fullText(text: String): Fragment =
    if text.length >= fullTextFloor then
      val phrase = "\"" + text.replace("\"", "\"\"") + "\""
      fr"issue.id IN (SELECT rowid FROM issue_fts WHERE issue_fts MATCH $phrase)"
    else
      val pattern = containing(text)
      fr"""(issue.title LIKE $pattern ESCAPE '\' OR issue.body LIKE $pattern ESCAPE '\'
            OR EXISTS (SELECT 1 FROM event e WHERE e.issue_id = issue.id AND e.comment LIKE $pattern ESCAPE '\'))"""

  private def rowId(id: IssueId): Fragment =
    fr"SELECT id FROM issue WHERE project_key = ${id.project} AND number = ${id.number}"

  private def anyOf[A](values: List[A])(condition: A => Fragment): Fragment =
    values.map(condition) match
      case Nil           => fr"1 = 0"
      case single :: Nil => fr"(" ++ single ++ fr")"
      case many          => fr"(" ++ many.reduce((left, right) => left ++ fr"OR" ++ right) ++ fr")"

  private def startingWith(value: String): String = escapeLike(value) + "%"

  private def containing(value: String): String = "%" + escapeLike(value) + "%"

  private def escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

  // Times

  private def timeColumn(field: TimeField): Fragment = field match
    case TimeField.Created => fr"issue.created"
    case TimeField.Closed  => fr"issue.closed_at"
    case TimeField.Claimed => fr"issue.claimed_at"
    case TimeField.Updated => updatedAt

  /** `updated` is the time of the latest event on the issue's timeline, related events included. */
  private val updatedAt: Fragment =
    fr"""COALESCE((SELECT MAX(e.at) FROM event e
                   WHERE e.issue_id = issue.id
                      OR e.seq IN (SELECT event_seq FROM event_related WHERE issue_id = issue.id)), issue.created)"""

  /** A relative duration counts back from the daemon's clock; a bare date starts at midnight in the daemon's zone. */
  private def resolve(moment: Moment, now: Instant, zone: ZoneId): Either[DomainError, Timestamp] = moment match
    case Moment.Ago(amount, unit) =>
      // An Instant knows nothing of weeks, and asking it for one throws, so a week is counted as the seven days it
      // is. That is exact here: these are absolute durations, with no calendar to skew them.
      val (multiplier, unitOf) = unit match
        case TimeUnit.Minutes => (1L, ChronoUnit.MINUTES)
        case TimeUnit.Hours   => (1L, ChronoUnit.HOURS)
        case TimeUnit.Days    => (1L, ChronoUnit.DAYS)
        case TimeUnit.Weeks   => (7L, ChronoUnit.DAYS)
      Right(Clock.render(now.minus(amount.toLong * multiplier, unitOf)))
    case Moment.OnDate(text) =>
      Either
        .catchNonFatal(LocalDate.parse(text).atStartOfDay(zone).toInstant)
        .leftMap(_ => DomainError.InvalidQuery(text, s"'$text' is not a date", Nil))
        .map(Clock.render)
    case Moment.AtDateTime(text) =>
      Either
        .catchNonFatal(OffsetDateTime.parse(text).toInstant)
        .orElse(Either.catchNonFatal(LocalDateTime.parse(text).atZone(zone).toInstant))
        .leftMap(_ => DomainError.InvalidQuery(text, s"'$text' is not a time", Nil))
        .map(Clock.render)

  // Ordering and paging

  def sortExpression(sort: Sort): Fragment = sort.field match
    case SortField.Rank    => fr"issue.rank"
    case SortField.Created => fr"issue.created"
    case SortField.Closed  => fr"issue.closed_at"
    case SortField.Updated => updatedAt

  /** Issues with no value for the sort key — an open issue under `sort:closed` — come last in both directions. */
  def order(sort: Sort): Fragment =
    val expression = sortExpression(sort)
    val present = fr"(" ++ expression ++ fr"IS NULL) ASC"
    sort.direction match
      case SortDirection.Ascending =>
        fr"ORDER BY" ++ present ++ fr"," ++ expression ++ fr"ASC, issue.project_key ASC, issue.number ASC"
      case SortDirection.Descending =>
        fr"ORDER BY" ++ present ++ fr"," ++ expression ++ fr"DESC, issue.project_key DESC, issue.number DESC"

  def after(sort: Sort, keyset: Keyset): Fragment =
    val expression = sortExpression(sort)
    val fallback = if sort.field == SortField.Rank then fr"COALESCE(" ++ expression ++ fr", 0)"
    else fr"COALESCE(" ++ expression ++ fr", '')"
    val missing = if keyset.missing then 1 else 0
    val ordering = if sort.direction == SortDirection.Ascending then fr">" else fr"<"
    val bound = sort.field match
      case SortField.Rank => fr"${keyset.value.toDoubleOption.getOrElse(0.0)}"
      case _              => fr"${keyset.value}"
    fr"((" ++ expression ++ fr"IS NULL) >" ++ fr"$missing OR ((" ++ expression ++ fr"IS NULL) = $missing AND (" ++
      fallback ++ ordering ++ bound ++ fr"OR (" ++ fallback ++ fr"=" ++ bound ++
      fr"AND (issue.project_key" ++ ordering ++ fr"${keyset.project} OR (issue.project_key = ${keyset.project} AND issue.number" ++
      ordering ++ fr"${keyset.number}))))))"
