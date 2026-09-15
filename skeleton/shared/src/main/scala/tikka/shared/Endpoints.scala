package tikka.shared

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.circe.*

object Endpoints:
  type Failure = (StatusCode, ErrorBody)

  private val issues: PublicEndpoint[Unit, Failure, Unit, Any] =
    endpoint.in("api" / "issues").errorOut(statusCode.and(jsonBody[ErrorBody]))

  val search: PublicEndpoint[Option[String], Failure, SearchResult, Any] =
    issues.get.in(query[Option[String]]("q")).out(jsonBody[SearchResult])

  val get: PublicEndpoint[IssueId, Failure, Issue, Any] =
    issues.get.in(path[IssueId]("id")).out(jsonBody[Issue])

  val create: PublicEndpoint[CreateIssue, Failure, Issue, Any] =
    issues.post.in(jsonBody[CreateIssue]).out(jsonBody[Issue])
