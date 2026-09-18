package tikka.ui

import com.raquo.laminar.api.L.*
import tikka.prose.MentionTarget
import tikka.prose.Prose
import tikka.shared.LineDiff
import tikka.shared.ProjectKey
import tikka.shared.Wire.*

/** The views. Reading and reviewing is the job: lists, an issue, a tree, and light edits in place. */
object Views:
  /** The projects that exist, so prose can tell a mention from an ordinary word. Loaded once. */
  private val knownProjects: Var[Set[ProjectKey]] = Var(Set.empty)

  def loadProjects(): Binder[HtmlElement] =
    Client.projects --> Observer[Either[String, List[ProjectSummaryOut]]]:
      case Right(projects) => knownProjects.set(projects.flatMap(p => ProjectKey.parse(p.key).toOption).toSet)
      case Left(_)         => ()

  // Pieces

  private def failure(message: String): HtmlElement = p(cls := "failure", message)

  private def loading: HtmlElement = p(cls := "loading", "loading…")

  private def labels(row: RowOut): HtmlElement =
    span(cls := "labels", row.labels.map(label => span(cls := "label", label)))

  /** One issue, as a line: its id, its state, its title and its labels. */
  private def row(value: RowOut, trailing: Modifier[HtmlElement]*): HtmlElement =
    div(
      cls := Logic.rowClasses(value).mkString(" "),
      Pages.link(Page.Issue(value.id), cls := "id", value.id),
      span(cls := "state", Logic.state(value)),
      Pages.link(Page.Issue(value.id), cls := "title", value.title),
      labels(value),
      trailing
    )

  /** Markdown, rendered with mentions linked exactly where the daemon counted them. */
  private def prose(markdown: String, self: String, mentions: List[RefOut]): HtmlElement =
    val targets = mentions.map(ref => ref.id -> MentionTarget(ref.title, ref.status == "closed")).toMap
    val body = Prose.html(
      markdown,
      knownProjects.now(),
      tikka.shared.IssueId.parse(self).toOption,
      id => targets.get(id.render)
    )
    // Laika escapes raw HTML, so what goes in here is tikka's own markup around the author's text.
    div(cls := "prose", onMountCallback(_.thisNode.ref.innerHTML = body))

  private def when(condition: Boolean)(content: => HtmlElement): HtmlElement =
    if condition then content else div(cls := "empty")

  // Search

  def search(page: Signal[Page.Search]): HtmlElement =
    val typed = Var("")
    val results = Var(Option.empty[Either[String, SearchOut]])
    val pending = Var(0)
    val again = EventBus[Unit]()
    val asked: Signal[String] = page.map(current => current.query.getOrElse(Logic.lastQuery()))
    div(
      cls := "search",
      asked --> Observer[String](query => typed.set(query)),
      Logic.loads(asked, again.events).flatMapSwitch(query => Client.search(query, None)) --> Observer[
        Either[String, SearchOut]
      ]: answer =>
        results.set(Some(answer))
        pending.set(0)
        answer.foreach(found => Logic.rememberQuery(found.effectiveQuery))
      ,
      // A list never reshuffles under the reader; it offers to refresh instead.
      Live.events --> Observer[EventOut](_ => pending.update(_ + 1)),
      form(
        cls := "query",
        onSubmit.preventDefault --> (_ => Pages.router.pushState(Page.Search(Some(typed.now())))),
        input(
          cls := "queryBox",
          placeholder := "ready label:bug -assignee:none",
          controlled(value <-- typed, onInput.mapToValue --> typed)
        ),
        button(typ := "submit", "search")
      ),
      child <-- pending.signal.map: count =>
        Logic
          .updateBar(count)
          .fold(div(cls := "empty"))(text => div(cls := "updates", button(text, onClick --> (_ => again.emit(()))))),
      child <-- results.signal.map:
        case None                => loading
        case Some(Left(message)) => failure(message)
        case Some(Right(found))  =>
          found.issues match
            case Nil  => p(cls := "empty", s"${found.effectiveQuery}: nothing matches")
            case rows =>
              div(
                cls := "results",
                p(
                  cls := "effective",
                  s"${found.effectiveQuery}: ${rows.size} of ${if found.hasMore then "more" else "all"}"
                ),
                rows.map(value => row(value, reorder(rows, value, () => again.emit(()))))
              )
    )

  /** Moving an issue up or down places it beside its neighbour, which is what rank_before and rank_after mean. */
  private def reorder(rows: List[RowOut], value: RowOut, refresh: () => Unit): HtmlElement =
    def arrow(move: Move, symbol: String): HtmlElement =
      val placement = Logic.placement(rows, value.id, move)
      button(
        cls := "reorder",
        disabled := placement.isEmpty,
        symbol,
        onClick --> { _ =>
          placement.foreach: place =>
            val change =
              if place.before then UpdateIn(rankBefore = Some(place.target))
              else UpdateIn(rankAfter = Some(place.target))
            Client.update(value.id, change).foreach(_ => refresh())(using unsafeWindowOwner): Unit
        }
      )
    span(cls := "reorderers", arrow(Move.Up, "↑"), arrow(Move.Down, "↓"))

  // One issue

  def issue(page: Signal[Page.Issue]): HtmlElement =
    val state = Var(Option.empty[Either[String, IssueOut]])
    val again = EventBus[Unit]()
    // The detail view does refresh in place, because the reader is looking at exactly this issue.
    val touched: EventStream[Unit] = Live.events
      .withCurrentValueOf(page)
      .collect:
        case (event, current) if Logic.concerns(event, current.id) => ()
    div(
      cls := "issue",
      // A different issue shows its own loading, rather than the one before it until the answer lands.
      page --> Observer[Page.Issue](_ => state.set(None)),
      Logic.loads(page.map(_.id), again.events.mergeWith(touched)).flatMapSwitch(Client.issue) --> Observer[
        Either[String, IssueOut]
      ](answer => state.set(Some(answer))),
      child <-- state.signal.map:
        case None                => loading
        case Some(Left(message)) => failure(message)
        case Some(Right(found))  => details(found, () => again.emit(()))
    )

  private def details(found: IssueOut, refresh: () => Unit): HtmlElement =
    div(
      h2(span(cls := "id", found.id), " ", found.title),
      p(
        cls := "state",
        Logic.state(
          RowOut(
            found.id,
            found.title,
            found.status,
            found.resolution,
            found.assignee,
            found.labels,
            found.parent,
            found.blocked,
            found.rank,
            found.updated
          )
        )
      ),
      p(cls := "meta", s"version ${found.version} · created ${found.created} · updated ${found.updated}"),
      found.parent.fold(div(cls := "empty"))(parent =>
        p(cls := "parent", "parent: ", Pages.link(Page.Issue(parent), parent))
      ),
      edges("children", found.children),
      blockers(found.blockers),
      edges("blocks", found.blocking),
      edges("mentions", found.mentions),
      edges("mentioned by", found.backlinks),
      when(found.body.nonEmpty)(prose(found.body, found.id, found.mentions)),
      Edits.panel(found, refresh),
      comments(found.comments),
      found.events.fold(div(cls := "empty"))(timeline)
    )

  private def edges(name: String, refs: List[RefOut]): HtmlElement =
    when(refs.nonEmpty)(
      div(
        cls := "edges",
        span(cls := "edgeName", s"$name: "),
        refs.map(ref => Pages.link(Page.Issue(ref.id), cls := s"ref ${ref.status}", s"${ref.id} ${ref.title}"))
      )
    )

  /** Each blocker expands in place to show its own blockers, so a chain can be walked without leaving the page. */
  private def blockers(refs: List[RefOut]): HtmlElement =
    when(refs.nonEmpty)(div(cls := "edges blockers", span(cls := "edgeName", "blocked by: "), refs.map(blocker)))

  private def blocker(ref: RefOut): HtmlElement =
    val expanded = Var(false)
    val deeper = Var(Option.empty[Either[String, IssueOut]])
    span(
      cls := "blockerEntry",
      Pages.link(Page.Issue(ref.id), cls := s"ref ${ref.status}", s"${ref.id} ${ref.title}"),
      button(
        cls := "expand",
        child.text <-- expanded.signal.map(open => if open then "−" else "+"),
        onClick --> { _ =>
          expanded.update(!_)
          if deeper.now().isEmpty then
            Client.issue(ref.id).foreach(answer => deeper.set(Some(answer)))(using unsafeWindowOwner): Unit
        }
      ),
      child <-- expanded.signal
        .combineWith(deeper.signal)
        .map: (open, loaded) =>
          if !open then div(cls := "empty")
          else
            loaded match
              case None                => loading
              case Some(Left(message)) => failure(message)
              case Some(Right(issue))  =>
                if issue.blockers.isEmpty then span(cls := "clear", " (nothing blocks it)")
                else span(cls := "nested", issue.blockers.map(blocker))
    )

  private def comments(values: List[CommentOut]): HtmlElement =
    when(values.nonEmpty)(
      div(
        cls := "comments",
        h3("comments"),
        values.map(comment =>
          div(cls := "comment", p(cls := "byline", s"#${comment.seq} ${comment.at} ${comment.actor}"), p(comment.text))
        )
      )
    )

  private def timeline(page: EventsOut): HtmlElement =
    div(
      cls := "timeline",
      h3(if page.truncated then s"timeline (latest ${page.events.size} of ${page.total})" else "timeline"),
      page.events.map(entry =>
        div(
          cls := "event",
          p(cls := "byline", s"#${entry.seq} ${entry.at} ${entry.actor}", when(false)(div())),
          entry.changes.map(change),
          entry.comment.fold(div(cls := "empty"))(text => p(cls := "eventComment", text))
        )
      )
    )

  private def change(value: ChangeOut): HtmlElement = value.field match
    case "body" =>
      div(
        cls := "diff",
        LineDiff
          .lines(value.before.getOrElse(""), value.after.getOrElse(""))
          .map:
            case LineDiff.Line.Same(text)    => div(cls := "same", text)
            case LineDiff.Line.Removed(text) => div(cls := "removed", s"- $text")
            case LineDiff.Line.Added(text)   => div(cls := "added", s"+ $text")
      )
    case "title" =>
      div(cls := "change", s"title: ${value.before.getOrElse("(none)")} → ${value.after.getOrElse("(none)")}")
    case "label" if value.op == "add"    => div(cls := "change", s"+${value.after.getOrElse("")}")
    case "label" if value.op == "remove" => div(cls := "change", s"-${value.before.getOrElse("")}")
    case field                           =>
      div(cls := "change", s"$field: ${value.before.getOrElse("(none)")} → ${value.after.getOrElse("(none)")}")

  // Tree

  def tree(page: Signal[Page.Tree]): HtmlElement =
    div(
      cls := "tree",
      child <-- page.map: current =>
        tikka.shared.IssueId.parse(current.target) match
          case Right(id) => node(id.render)
          case Left(_)   => roots(current.target)
    )

  /** A project's tree starts at the issues with no parent. */
  private def roots(project: String): HtmlElement =
    val state = Var(Option.empty[Either[String, SearchOut]])
    div(
      h2(s"$project"),
      Client.search(s"project:$project parent:none", None) --> Observer[Either[String, SearchOut]](answer =>
        state.set(Some(answer))
      ),
      child <-- state.signal.map:
        case None                => loading
        case Some(Left(message)) => failure(message)
        case Some(Right(found))  => div(cls := "branch", found.issues.map(value => branch(value)))
    )

  private def node(id: String): HtmlElement =
    val state = Var(Option.empty[Either[String, IssueOut]])
    div(
      Client.issue(id) --> Observer[Either[String, IssueOut]](answer => state.set(Some(answer))),
      child <-- state.signal.map:
        case None                => loading
        case Some(Left(message)) => failure(message)
        case Some(Right(found))  =>
          div(
            h2(span(cls := "id", found.id), " ", found.title),
            div(
              cls := "branch",
              found.children.map(ref =>
                branch(RowOut(ref.id, ref.title, ref.status, None, None, Nil, Some(found.id), blocked = false, 0, ""))
              )
            )
          )
    )

  /** One row of the tree, with its children fetched when it is opened. */
  private def branch(value: RowOut): HtmlElement =
    val expanded = Var(false)
    val children = Var(Option.empty[Either[String, SearchOut]])
    div(
      cls := "treeRow",
      button(
        cls := "expand",
        child.text <-- expanded.signal.map(open => if open then "−" else "+"),
        onClick --> { _ =>
          expanded.update(!_)
          if children.now().isEmpty then
            Client
              .search(s"parent:${value.id}", None)
              .foreach(answer => children.set(Some(answer)))(using unsafeWindowOwner): Unit
        }
      ),
      row(value),
      child <-- expanded.signal
        .combineWith(children.signal)
        .map: (open, loaded) =>
          if !open then div(cls := "empty")
          else
            loaded match
              case None                => loading
              case Some(Left(message)) => failure(message)
              case Some(Right(found))  =>
                if found.issues.isEmpty then div(cls := "empty leaf")
                else div(cls := "branch", found.issues.map(branch))
    )

  // Projects

  def projects: HtmlElement =
    val state = Var(Option.empty[Either[String, List[ProjectSummaryOut]]])
    div(
      cls := "projects",
      h2("projects"),
      Client.projects --> Observer[Either[String, List[ProjectSummaryOut]]](answer => state.set(Some(answer))),
      child <-- state.signal.map:
        case None                => loading
        case Some(Left(message)) => failure(message)
        case Some(Right(Nil))    => p(cls := "empty", "no projects yet; create one with `tikka project new`")
        case Some(Right(found))  =>
          div(
            found.map(project =>
              div(
                cls := "project",
                span(cls := "id", project.key),
                span(cls := "name", project.name),
                Pages.link(Page.Search(Some(s"project:${project.key} ready")), s"${project.ready} ready"),
                Pages.link(Page.Search(Some(s"project:${project.key} status:open")), s"${project.open} open"),
                Pages.link(Page.Tree(project.key), "tree")
              )
            )
          )
    )
