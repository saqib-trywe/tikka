package tikka.shared

/** Why a query was refused: the token at fault, where it sits, and what to write instead.
  *
  * A silently ignored typo returns the wrong issues with no signal, which is the worst failure mode for an agent, so
  * every unknown name, bad value and malformed token rejects the whole query.
  */
final case class QueryError(token: String, position: Int, reason: String, suggestions: List[String])

object QueryParser:
  private val bareNames = List("ready", "blocked", "unblocked")

  private val valuedNames = List(
    "project",
    "status",
    "resolution",
    "assignee",
    "label",
    "parent",
    "under",
    "blocks",
    "blocked-by",
    "mentions",
    "mentioned-by",
    "id",
    "text",
    "sort"
  )

  private val timeNames =
    for
      field <- List("created", "updated", "closed", "claimed")
      direction <- List("before", "after")
    yield s"$field-$direction"

  private val knownNames = bareNames ++ valuedNames ++ timeNames

  def parse(text: String): Either[QueryError, Query] =
    tokenise(text)
      .foldLeft(Right(Parsed(Query.everything, sorted = false)): Either[QueryError, Parsed]): (parsed, token) =>
        parsed.flatMap(state => addTo(state, token))
      .map(_.query)

  /** `sorted` is tracked rather than compared against the default, because `sort:rank` *is* the default. */
  private final case class Parsed(query: Query, sorted: Boolean)

  private final case class Token(text: String, position: Int)

  /** Splits on whitespace, except inside double quotes, so `text:"two words"` stays one token. */
  private def tokenise(text: String): List[Token] =
    val tokens = List.newBuilder[Token]
    val current = StringBuilder()
    var start = 0
    var quoted = false
    text.zipWithIndex.foreach: (char, index) =>
      if char == '"' then
        quoted = !quoted
        current.append(char): Unit
      else if char.isWhitespace && !quoted then
        if current.nonEmpty then tokens += Token(current.result(), start)
        current.clear()
        start = index + 1
      else
        if current.isEmpty then start = index
        current.append(char): Unit
    if current.nonEmpty then tokens += Token(current.result(), start)
    tokens.result()

  private def addTo(state: Parsed, token: Token): Either[QueryError, Parsed] =
    val negated = token.text.startsWith("-")
    val body = if negated then token.text.drop(1) else token.text
    def adding(filter: Filter): Parsed =
      state.copy(query = state.query.copy(terms = state.query.terms :+ Term(negated, filter)))
    body.indexOf(':') match
      case -1 => bare(body, token).map(adding)
      case at =>
        val name = body.take(at)
        val value = body.drop(at + 1)
        if name == "sort" then
          if negated then Left(QueryError(token.text, token.position, "sort cannot be negated", Nil))
          else if state.sorted then Left(QueryError(token.text, token.position, "a query has only one sort", Nil))
          else sort(value, token).map(chosen => Parsed(state.query.copy(sort = chosen), sorted = true))
        else valued(name, value, token).map(adding)

  private def bare(name: String, token: Token): Either[QueryError, Filter] = name match
    case "ready"                                                           => Right(Filter.Ready)
    case "blocked"                                                         => Right(Filter.Blocked)
    case "unblocked"                                                       => Right(Filter.Unblocked)
    case other if valuedNames.contains(other) || timeNames.contains(other) =>
      Left(QueryError(token.text, token.position, s"'$other' needs a value, as in '$other:…'", Nil))
    case other =>
      Left(QueryError(token.text, token.position, s"'$other' is not a filter", suggestionsFor(other)))

  private def valued(name: String, value: String, token: Token): Either[QueryError, Filter] =
    if value.isEmpty then Left(QueryError(token.text, token.position, s"'$name:' needs a value", Nil))
    else
      name match
        case "project" =>
          if value == "*" then Right(Filter.Project(ProjectScope.Every))
          else each(value, token, ProjectKey.parse).map(keys => Filter.Project(ProjectScope.Keys(keys)))
        case "status"                           => each(value, token, status).map(Filter.Status.apply)
        case "resolution"                       => each(value, token, Resolution.parse).map(Filter.Resolution.apply)
        case "assignee"                         => each(value, token, assignee).map(Filter.Assignee.apply)
        case "label"                            => each(value, token, label).map(Filter.Label.apply)
        case "parent"                           => ids(value, token).map(Filter.Parent.apply)
        case "under"                            => ids(value, token).map(Filter.Under.apply)
        case "blocks"                           => ids(value, token).map(Filter.Blocks.apply)
        case "blocked-by"                       => ids(value, token).map(Filter.BlockedBy.apply)
        case "mentions"                         => ids(value, token).map(Filter.Mentions.apply)
        case "mentioned-by"                     => ids(value, token).map(Filter.MentionedBy.apply)
        case "id"                               => ids(value, token).map(Filter.Ids.apply)
        case "text"                             => Right(Filter.Text(unquote(value)))
        case other if timeNames.contains(other) => time(other, value, token)
        case other if bareNames.contains(other) =>
          Left(QueryError(token.text, token.position, s"'$other' takes no value", Nil))
        case other => Left(QueryError(token.text, token.position, s"'$other' is not a filter", suggestionsFor(other)))

  private def status(value: String): Either[String, StatusValue] = value match
    case "open"   => Right(StatusValue.Open)
    case "closed" => Right(StatusValue.Closed)
    case other    => Left(s"'$other' is not a status: expected open or closed")

  private def assignee(value: String): Either[String, AssigneeMatch] = value match
    case "none" => Right(AssigneeMatch.Nobody)
    case "any"  => Right(AssigneeMatch.Anyone)
    case other  => label(other).map(AssigneeMatch.Named.apply)

  private def label(value: String): Either[String, NameMatch] =
    val text = unquote(value)
    if text.isEmpty then Left("a value must not be blank")
    else if text.endsWith("*") then Right(NameMatch.Prefix(text.dropRight(1)))
    else Right(NameMatch.Exact(text))

  private def time(name: String, value: String, token: Token): Either[QueryError, Filter] =
    val (fieldName, directionName) = name.span(_ != '-')
    val field = fieldName match
      case "created" => TimeField.Created
      case "updated" => TimeField.Updated
      case "closed"  => TimeField.Closed
      case _         => TimeField.Claimed
    val direction = if directionName == "-before" then TimeDirection.Before else TimeDirection.After
    moment(value).left
      .map(reason => QueryError(token.text, token.position, reason, Nil))
      .map(Filter.Time(field, direction, _))

  private val relative = "([0-9]+)([mhdw])".r
  private val date = "\\d{4}-\\d{2}-\\d{2}".r
  // Non-capturing: a group would make the pattern extract, and `case dateTime()` would then never match.
  private val dateTime = "\\d{4}-\\d{2}-\\d{2}T[0-9:.]+(?:Z|[+-][0-9:]+)?".r

  private def moment(value: String): Either[String, Moment] = value match
    case relative(amount, unit) =>
      val chosen = unit match
        case "m" => TimeUnit.Minutes
        case "h" => TimeUnit.Hours
        case "d" => TimeUnit.Days
        case _   => TimeUnit.Weeks
      amount.toIntOption.toRight(s"'$amount' is too large").map(Moment.Ago(_, chosen))
    case date()     => Right(Moment.OnDate(value))
    case dateTime() => Right(Moment.AtDateTime(value))
    case other      =>
      Left(s"'$other' is not a time: expected a duration like 30m, 2h, 3d or 1w, or a date like 2026-09-16")

  private def sort(value: String, token: Token): Either[QueryError, Sort] =
    val (fieldName, suffix) = value.span(_ != '-')
    val field = fieldName match
      case "rank"    => Right(SortField.Rank)
      case "created" => Right(SortField.Created)
      case "updated" => Right(SortField.Updated)
      case "closed"  => Right(SortField.Closed)
      case other     =>
        Left(s"'$other' is not a sort: expected rank, created, updated or closed")
    field
      .flatMap { chosen =>
        suffix match
          case ""      => Right(Sort.natural(chosen))
          case "-asc"  => Right(Sort(chosen, SortDirection.Ascending))
          case "-desc" => Right(Sort(chosen, SortDirection.Descending))
          case other   => Left(s"'${other.drop(1)}' is not a direction: expected asc or desc")
      }
      .left
      .map(reason => QueryError(token.text, token.position, reason, Nil))

  private def ids(value: String, token: Token): Either[QueryError, List[IssueId]] =
    each(value, token, IssueId.parse)

  /** Values within one filter are ORed, so they are comma-separated and all must parse. */
  private def each[A](value: String, token: Token, read: String => Either[String, A]): Either[QueryError, List[A]] =
    val parts = value.split(",").toList.map(_.trim).filter(_.nonEmpty)
    if parts.isEmpty then Left(QueryError(token.text, token.position, "a filter needs at least one value", Nil))
    else
      parts
        .foldLeft(Right(List.empty[A]): Either[String, List[A]]): (parsed, part) =>
          parsed.flatMap(values => read(part).map(values :+ _))
        .left
        .map(reason => QueryError(token.text, token.position, reason, Nil))

  private def unquote(value: String): String =
    if value.length >= 2 && value.startsWith("\"") && value.endsWith("\"") then value.drop(1).dropRight(1) else value

  /** The names closest to what was written, so a typo is one edit away from working. */
  private def suggestionsFor(name: String): List[String] =
    knownNames
      .map(known => known -> distance(name, known))
      .filter((known, gap) => gap <= 3 || known.startsWith(name))
      .sortBy((known, gap) => (gap, known))
      .map((known, _) => known)
      .take(3)

  private def distance(left: String, right: String): Int =
    val previous = Array.tabulate(right.length + 1)(identity)
    val current = Array.fill(right.length + 1)(0)
    left.indices.foreach: row =>
      current(0) = row + 1
      right.indices.foreach: column =>
        val substitution = previous(column) + (if left(row) == right(column) then 0 else 1)
        current(column + 1) = math.min(math.min(current(column) + 1, previous(column + 1) + 1), substitution)
      current.copyToArray(previous)
    previous(right.length)
