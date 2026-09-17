package tikka.shared

/** The scalar domain values. Every one is opaque, parsed at the edge, and carries its own rule.
  *
  * `parse` is for untrusted input and returns the reason it was refused. `trusted` is for values coming back out of the
  * store, which was validated when they went in.
  */

opaque type ProjectKey = String

object ProjectKey:
  /** 2-10 characters: an uppercase letter, then uppercase letters or digits. Permanent, so validation is strict. */
  private val allowed = "[A-Z][A-Z0-9]{1,9}".r

  def parse(value: String): Either[String, ProjectKey] =
    if allowed.matches(value) then Right(value)
    else Left(s"'$value' is not a project key: 2-10 characters, an uppercase letter then uppercase letters or digits")

  private[tikka] def trusted(value: String): ProjectKey = value

  given Ordering[ProjectKey] = Ordering.by(key => key: String)

  extension (key: ProjectKey) def value: String = key

opaque type IssueNumber = Int

object IssueNumber:
  def parse(value: Int): Either[String, IssueNumber] =
    if value >= 1 then Right(value) else Left(s"$value is not an issue number: issue numbers start at 1")

  private[tikka] def trusted(value: Int): IssueNumber = value

  given Ordering[IssueNumber] = Ordering.by(number => number: Int)

  extension (number: IssueNumber) def value: Int = number

/** A project key plus a number, `TIK-42`. Globally unique, assigned once, never reused. */
final case class IssueId(project: ProjectKey, number: IssueNumber):
  def render: String = s"${project.value}-${number.value}"

object IssueId:
  private val shape = "([A-Z][A-Z0-9]{1,9})-([1-9][0-9]*)".r

  def parse(value: String): Either[String, IssueId] = value match
    case shape(key, number) =>
      for
        project <- ProjectKey.parse(key)
        parsed <- number.toIntOption.toRight(s"'$value' is not an issue id: the number is too large")
        issue <- IssueNumber.parse(parsed)
      yield IssueId(project, issue)
    case _ => Left(s"'$value' is not an issue id: expected a form like TIK-42")

  given Ordering[IssueId] = Ordering.by(id => (id.project.value, id.number.value))

opaque type Title = String

object Title:
  def parse(value: String): Either[String, Title] =
    val trimmed = value.trim
    if trimmed.nonEmpty then Right(trimmed) else Left("a title must not be blank")

  private[tikka] def trusted(value: String): Title = value

  extension (title: Title) def value: String = title

/** Markdown, stored and returned exactly as written so `body_edits` round-trips. May be empty. */
opaque type Body = String

object Body:
  val empty: Body = ""

  def apply(value: String): Body = value

  extension (body: Body) def value: String = body

/** Free text naming whoever holds an issue — for parallel work a session, not a person. */
opaque type Assignee = String

object Assignee:
  def parse(value: String): Either[String, Assignee] =
    val trimmed = value.trim
    if trimmed.nonEmpty then Right(trimmed) else Left("an assignee must not be blank")

  private[tikka] def trusted(value: String): Assignee = value

  extension (assignee: Assignee) def value: String = assignee

/** An opaque tag. Lowercased on the way in, so no two labels differ only by case. */
opaque type Label = String

object Label:
  def parse(value: String): Either[String, Label] =
    val trimmed = value.trim.toLowerCase
    if trimmed.nonEmpty then Right(trimmed) else Left("a label must not be blank")

  private[tikka] def trusted(value: String): Label = value

  given Ordering[Label] = Ordering.by(label => label: String)

  extension (label: Label) def value: String = label

/** Position in a total order, any decimal, defaulting to the issue number. Not priority. */
opaque type Rank = Double

object Rank:
  def parse(value: Double): Either[String, Rank] =
    if value.isNaN || value.isInfinite then Left(s"$value is not a rank: a rank is a finite decimal number")
    else Right(value)

  private[tikka] def trusted(value: Double): Rank = value

  given Ordering[Rank] = Ordering.by(rank => rank: Double)

  extension (rank: Rank) def value: Double = rank

/** Moves only when title, body or labels change. */
opaque type Version = Int

object Version:
  val first: Version = 1

  def parse(value: Int): Either[String, Version] =
    if value >= 1 then Right(value) else Left(s"$value is not a version: versions start at 1")

  private[tikka] def trusted(value: Int): Version = value

  extension (version: Version)
    def value: Int = version
    def next: Version = version + 1

/** The global sequence that orders every timeline. Timestamps can repeat or step backwards; this cannot. */
opaque type EventSeq = Long

object EventSeq:
  private[tikka] def trusted(value: Long): EventSeq = value

  given Ordering[EventSeq] = Ordering.by(seq => seq: Long)

  extension (seq: EventSeq) def value: Long = seq

/** ISO-8601 UTC with milliseconds, which sorts correctly as text. The daemon's clock is the only source. */
opaque type Timestamp = String

object Timestamp:
  private val shape = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z".r

  def parse(value: String): Either[String, Timestamp] =
    if shape.matches(value) then Right(value)
    else Left(s"'$value' is not a timestamp: expected ISO-8601 UTC with milliseconds, like 2026-09-16T10:02:03.004Z")

  private[tikka] def trusted(value: String): Timestamp = value

  given Ordering[Timestamp] = Ordering.by(at => at: String)

  extension (at: Timestamp) def value: String = at
