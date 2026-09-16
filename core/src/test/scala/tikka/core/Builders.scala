package tikka.core

import cats.effect.IO
import tikka.shared.*

/** Terse constructors for tests, so each test reads as the rule it checks. */
trait Builders:
  val actor: Actor = Actor(Surface.Cli, None)

  def key(value: String): ProjectKey = ProjectKey.parse(value).fold(sys.error, identity)

  def id(value: String): IssueId = IssueId.parse(value).fold(sys.error, identity)

  def title(value: String): Title = Title.parse(value).fold(sys.error, identity)

  def label(value: String): Label = Label.parse(value).fold(sys.error, identity)

  def holder(value: String): Assignee = Assignee.parse(value).fold(sys.error, identity)

  def creating(
      titleText: String,
      project: Option[ProjectKey] = None,
      body: String = "",
      labels: List[Label] = Nil,
      parent: Option[IssueId] = None,
      blockedBy: List[IssueId] = Nil,
      rank: Option[RankPlacement] = None,
      comment: Option[String] = None
  ): CreateIssue =
    CreateIssue(project, title(titleText), Some(Body(body)), labels, parent, blockedBy, rank, comment)

  extension (core: Core)
    /** Creates a project, failing the test if the store refuses. */
    def project(value: String): IO[ProjectKey] =
      val projectKey = key(value)
      core.createProject(projectKey, value).map(_.fold(error => sys.error(error.toString), _ => projectKey))

    def add(command: CreateIssue): IO[IssueId] =
      core.create(command, None, actor).map(_.fold(error => sys.error(error.toString), _.row.id))

    def addTo(project: ProjectKey, titleText: String): IO[IssueId] =
      core.add(creating(titleText, project = Some(project)))

    def detail(issue: IssueId): IO[IssueDetail] =
      core.get(issue, includeEvents = false).map(_.fold(error => sys.error(error.toString), _.detail))

    def events(issue: IssueId): IO[List[Event]] =
      core
        .get(issue, includeEvents = true)
        .map(_.fold(error => sys.error(error.toString), _.events.fold(Nil)(_.events)))

    /** Runs a query and fails the test if the grammar refused it. */
    def find(query: String): IO[List[IssueId]] =
      core.page(query).map(_.issues.map(_.id))

    def page(query: String, cursor: Option[String] = None, limit: Int = 50): IO[SearchPage] =
      core.search(query, None, cursor, limit).map(_.fold(error => sys.error(error.toString), identity))

    def refuse(query: String): IO[DomainError] =
      core.search(query, None, None, 50).map(_.fold(identity, page => sys.error(s"expected a rejection: $page")))

    def blockOn(issue: IssueId, blocker: IssueId): IO[Either[DomainError, Written]] =
      core.update(issue, UpdateIssue.nothing.copy(blockedByAdd = List(blocker)), actor)

    def reparent(issue: IssueId, parent: IssueId): IO[Either[DomainError, Written]] =
      core.update(issue, UpdateIssue.nothing.copy(parent = Some(ParentChange.SetTo(parent))), actor)
