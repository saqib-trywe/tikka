package tikka.daemon

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import java.time.Instant
import tikka.shared.*

/** The one core. HTTP and MCP are both thin adapters over this. */
trait Core:
  def create(args: CreateIssue): IO[Issue]
  def get(id: IssueId): IO[Either[ErrorBody, Issue]]
  def search(query: Option[String]): IO[Either[ErrorBody, SearchResult]]

object Core:
  private val project = "SKL"

  private def toIssue(key: String, number: Long, title: String): Issue =
    Issue(IssueId.of(key, number), Title.parse(title).fold(e => throw IllegalStateException(e), identity))

  def apply(store: Store): Core = new Core:
    def create(args: CreateIssue): IO[Issue] =
      val program = for
        number <- sql"UPDATE project SET next_number = next_number + 1 WHERE key = $project RETURNING next_number - 1"
                    .query[Long].unique
        _      <- sql"INSERT INTO issue (project, number, title, created) VALUES ($project, $number, ${args.title.value}, ${Instant.now().toString})"
                    .update.run
      yield toIssue(project, number, args.title.value)
      store.writing(program)

    def get(id: IssueId): IO[Either[ErrorBody, Issue]] =
      val (key, number) = id.value.splitAt(id.value.lastIndexOf('-'))
      store.reading(
        sql"SELECT project, number, title FROM issue WHERE project = $key AND number = ${number.drop(1).toLong}"
          .query[(String, Long, String)].option
      ).map(_.map(toIssue.tupled).toRight(ErrorBody(ErrorCode.not_found, s"${id.value} does not exist")))

    def search(query: Option[String]): IO[Either[ErrorBody, SearchResult]] =
      query.map(_.trim).filter(_.nonEmpty) match
        case Some(q) =>
          IO.pure(Left(ErrorBody(ErrorCode.invalid_query, s"the skeleton only supports the empty query, not `$q`")))
        case None =>
          store.reading(
            sql"SELECT project, number, title FROM issue ORDER BY project, number LIMIT 50"
              .query[(String, Long, String)].to[List]
          ).map(rows => Right(SearchResult(s"project:$project", rows.map(toIssue.tupled), None, has_more = false)))
