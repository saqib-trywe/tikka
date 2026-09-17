package tikka.shared

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.circe.*
import tikka.shared.Wire.*

/** The HTTP API, defined once. The daemon interprets these as http4s routes; the web UI and the CLI interpret the same
  * definitions as typed clients, so a change to a route is a compile error everywhere it is used.
  *
  * Every error is the contract's body with one of three statuses — 404, 400 or 409 — and clients switch on the body's
  * `error` code, never on the status.
  */
object Endpoints:
  type Failure = (StatusCode, ErrorOut)

  private val api = endpoint.in("api").errorOut(statusCode.and(jsonBody[ErrorOut]))

  private val issues = api.in("issues")

  private val issueId = path[String]("id").description("An issue id, such as TIK-42.")

  private val boundProject = header[Option[String]]("Tikka-Project")
    .description("The project an unscoped search or a create means. An explicit project always wins.")

  private val limit = query[Option[Int]]("limit").description(s"Page size, at most 200. Defaults to 50.")

  val search: PublicEndpoint[(Option[String], Option[String], Option[Int], Option[String]), Failure, SearchOut, Any] =
    issues.get
      .in(query[Option[String]]("q").description("A query in tikka's grammar, such as `ready label:bug`."))
      .in(query[Option[String]]("cursor"))
      .in(limit)
      .in(boundProject)
      .out(jsonBody[SearchOut])

  val get: PublicEndpoint[(String, Option[String]), Failure, IssueOut, Any] =
    issues.get
      .in(issueId)
      .in(query[Option[String]]("include").description("`events` adds the latest 50 events."))
      .out(jsonBody[IssueOut])

  val create: PublicEndpoint[(Option[String], CreateIn), Failure, WrittenOut, Any] =
    issues.post.in(boundProject).in(jsonBody[CreateIn]).out(statusCode(StatusCode.Created)).out(jsonBody[WrittenOut])

  val update: PublicEndpoint[(String, UpdateIn), Failure, WrittenOut, Any] =
    issues.patch.in(issueId).in(jsonBody[UpdateIn]).out(jsonBody[WrittenOut])

  val claim: PublicEndpoint[(String, ClaimIn), Failure, ClaimedOut, Any] =
    issues.post.in(issueId / "claim").in(jsonBody[ClaimIn]).out(jsonBody[ClaimedOut])

  val release: PublicEndpoint[(String, ClaimIn), Failure, RowOnly, Any] =
    issues.post.in(issueId / "release").in(jsonBody[ClaimIn]).out(jsonBody[RowOnly])

  val reassign: PublicEndpoint[(String, ReassignIn), Failure, RowOnly, Any] =
    issues.post.in(issueId / "reassign").in(jsonBody[ReassignIn]).out(jsonBody[RowOnly])

  val close: PublicEndpoint[(String, CloseIn), Failure, ClosedOutWire, Any] =
    issues.post.in(issueId / "close").in(jsonBody[CloseIn]).out(jsonBody[ClosedOutWire])

  val reopen: PublicEndpoint[(String, ReopenIn), Failure, WrittenOut, Any] =
    issues.post.in(issueId / "reopen").in(jsonBody[ReopenIn]).out(jsonBody[WrittenOut])

  /** Full history with full before-and-after text, oldest first. The cursor is opaque to callers. */
  val history: PublicEndpoint[(String, Option[String], Option[Int]), Failure, HistoryOut, Any] =
    issues.get.in(issueId / "events").in(query[Option[String]]("cursor")).in(limit).out(jsonBody[HistoryOut])

  /** Every event after a sequence number, across all issues: what live views resume from. */
  val feed: PublicEndpoint[(Option[Long], Option[Int]), Failure, FeedOut, Any] =
    api.get.in("events").in(query[Option[Long]]("after")).in(limit).out(jsonBody[FeedOut])

  val meta: PublicEndpoint[Unit, Failure, MetaOut, Any] =
    api.get.in("meta").out(jsonBody[MetaOut])

  val projects: PublicEndpoint[Unit, Failure, List[ProjectOut], Any] =
    api.get.in("projects").out(jsonBody[List[ProjectOut]])

  /** Creating a project is a human act: the CLI calls this, and MCP has no project tools at all. */
  val createProject: PublicEndpoint[ProjectIn, Failure, ProjectOut, Any] =
    api.post.in("projects").in(jsonBody[ProjectIn]).out(statusCode(StatusCode.Created)).out(jsonBody[ProjectOut])

  val all: List[AnyEndpoint] =
    List(
      search,
      get,
      create,
      update,
      claim,
      release,
      reassign,
      close,
      reopen,
      history,
      feed,
      meta,
      projects,
      createProject
    )
