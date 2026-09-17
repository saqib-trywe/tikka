package tikka.shared

import tikka.shared.Wire.*

/** A line-by-line diff, hand-written because no Scala diff library builds for the JVM, the browser and Scala Native.
  * MCP renders body changes with it now; the web UI's timeline will use the same one.
  */
object LineDiff:
  enum Line:
    case Same(text: String)
    case Removed(text: String)
    case Added(text: String)

  /** The longest common subsequence of lines, walked back into a diff. Bodies are short, so quadratic is fine. */
  def lines(before: String, after: String): List[Line] =
    val left = before.split("\n", -1).toVector
    val right = after.split("\n", -1).toVector
    val common = Array.ofDim[Int](left.length + 1, right.length + 1)
    for
      row <- left.indices.reverse
      column <- right.indices.reverse
    do
      common(row)(column) =
        if left(row) == right(column) then common(row + 1)(column + 1) + 1
        else math.max(common(row + 1)(column), common(row)(column + 1))
    val out = List.newBuilder[Line]
    var row = 0
    var column = 0
    while row < left.length || column < right.length do
      if row < left.length && column < right.length && left(row) == right(column) then
        out += Line.Same(left(row))
        row += 1
        column += 1
      // On a tie, removals come first, so a replaced line reads as `-` then `+` like any unified diff.
      else if row < left.length && (column == right.length || common(row + 1)(column) >= common(row)(column + 1)) then
        out += Line.Removed(left(row))
        row += 1
      else
        out += Line.Added(right(column))
        column += 1
    out.result()

  /** Unified-style text, keeping `context` unchanged lines around each change and eliding the rest. */
  def unified(before: String, after: String, context: Int = 2): String =
    val all = lines(before, after).toVector
    val near = all.indices.filter(index =>
      all(index) match
        case Line.Same(_) => false
        case _            => true
    )
    def kept(index: Int): Boolean = near.exists(changed => math.abs(changed - index) <= context)
    val rendered = List.newBuilder[String]
    var elided = false
    all.indices.foreach: index =>
      if kept(index) then
        elided = false
        rendered += (all(index) match
          case Line.Same(text)    => s"  $text"
          case Line.Removed(text) => s"- $text"
          case Line.Added(text)   => s"+ $text")
      else if !elided then
        elided = true
        rendered += "  …"
    rendered.result().mkString("\n")

/** The compact text every surface prints: MCP's text block now, the CLI's output later. It renders from the wire
  * shapes, so a client holding decoded JSON prints exactly what the daemon would.
  */
object Render:
  /** One line per issue, like
    * `TIK-42  open · claimed by scala-survey · blocked  Choose the persistence engine  [grilling]`.
    */
  def row(row: RowOut): String =
    val status = row.resolution.fold(row.status)(value => s"${row.status} $value")
    val claim = row.assignee.map(name => s"claimed by $name")
    val blocked = Option.when(row.blocked)("blocked")
    val state = (status :: claim.toList ++ blocked.toList).mkString(" · ")
    val labels = if row.labels.isEmpty then "" else row.labels.mkString("  [", ", ", "]")
    s"${row.id}  $state  ${row.title}$labels"

  def search(page: SearchOut): String =
    val count = page.issues.size
    val noun = if count == 1 then "issue" else "issues"
    val more = if page.hasMore then ", more available" else ""
    val header = s"${page.effectiveQuery.ifBlank("(everything)")}: $count $noun$more"
    val cursor = page.nextCursor.map(value => s"next cursor: $value")
    (header :: page.issues.map(row) ++ cursor.toList).mkString("\n")

  def written(out: WrittenOut): String = s"${row(out.row)}\nversion ${out.version}"

  def claimed(out: ClaimedOut): String = s"${row(out.row)}\nclaimed at ${out.claimedAt}"

  def closed(out: ClosedOutWire): String =
    val freed = if out.newlyUnblocked.isEmpty then Nil else "now unblocked:" :: out.newlyUnblocked.map(row)
    (row(out.row) :: freed).mkString("\n")

  def issue(out: IssueOut): String =
    val summary = row(
      RowOut(
        out.id,
        out.title,
        out.status,
        out.resolution,
        out.assignee,
        out.labels,
        out.parent,
        out.blocked,
        out.rank,
        out.updated
      )
    )
    val sections = List(
      Some(s"version ${out.version} · created ${out.created} · updated ${out.updated}"),
      out.parent.map(parent => s"parent: $parent"),
      edges("children", out.children),
      edges("blocked by", out.blockers),
      edges("blocks", out.blocking),
      edges("mentions", out.mentions),
      edges("mentioned by", out.backlinks),
      Option.when(out.body.nonEmpty)(s"\n${out.body}"),
      Option.when(out.comments.nonEmpty)(
        ("\ncomments:" :: out.comments.map(comment =>
          s"#${comment.seq} ${comment.at} ${comment.actor}\n${comment.text}"
        ))
          .mkString("\n")
      ),
      out.events.map(events)
    )
    (summary :: sections.flatten).mkString("\n")

  def events(page: EventsOut): String =
    val header = if page.truncated then s"\nevents (latest ${page.events.size} of ${page.total}):" else "\nevents:"
    (header :: page.events.map(event)).mkString("\n")

  /** A header line, then each change: bodies as a diff, titles before and after, everything else in one line. */
  def event(out: EventOut): String =
    val header = s"#${out.seq} ${out.at} ${out.actor}${if out.issue.nonEmpty then s" on ${out.issue}" else ""}"
    val changes = out.changes.map(change)
    val comment = out.comment.map(text => s"  “$text”")
    (header :: changes ++ comment.toList).mkString("\n")

  def error(out: ErrorOut): String = s"${out.error}: ${out.message}"

  private def change(out: ChangeOut): String = (out.field, out.op) match
    case ("body", _) =>
      LineDiff.unified(out.before.getOrElse(""), out.after.getOrElse("")).linesIterator.map("  " + _).mkString("\n")
    case ("title", _) =>
      s"  title: ${out.before.fold("(none)")(quoted)}\n      → ${out.after.fold("(none)")(quoted)}"
    case ("label", "add")         => s"  +${out.after.getOrElse("")}"
    case ("label", "remove")      => s"  -${out.before.getOrElse("")}"
    case ("blocked_by", "add")    => s"  blocked by +${out.after.getOrElse("")}"
    case ("blocked_by", "remove") => s"  blocked by -${out.before.getOrElse("")}"
    case (field, _)               => s"  $field: ${out.before.getOrElse("(none)")} → ${out.after.getOrElse("(none)")}"

  private def edges(name: String, refs: List[RefOut]): Option[String] =
    Option.when(refs.nonEmpty)(s"$name: ${refs.map(ref => s"${ref.id} (${ref.status}) ${ref.title}").mkString("; ")}")

  private def quoted(text: String): String = s"“$text”"

  extension (text: String) private def ifBlank(fallback: String): String = if text.trim.isEmpty then fallback else text
