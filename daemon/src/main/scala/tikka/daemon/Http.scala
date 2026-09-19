package tikka.daemon

import cats.data.EitherT
import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Signal
import io.circe.JsonObject
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.ServerSentEvent
import org.http4s.dsl.io.*
import org.http4s.headers.`Last-Event-Id`
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.server.model.ValuedEndpointOutput
import tikka.core.Core
import tikka.shared.*
import tikka.shared.Wire.*

import scala.concurrent.duration.*

/** The HTTP adapter: decode, call the core, render. The same wire types and request mapping as MCP. */
object Http:
  private type Result[A] = EitherT[IO, DomainError, A]

  /** Three statuses and no more; clients switch on the body's `error` code. */
  def status(code: ErrorCode): StatusCode = code match
    case ErrorCode.NotFound | ErrorCode.UnknownProject                               => StatusCode.NotFound
    case ErrorCode.InvalidQuery | ErrorCode.InvalidArgument | ErrorCode.EditMismatch => StatusCode.BadRequest
    case _                                                                           => StatusCode.Conflict

  def failure(error: DomainError): Endpoints.Failure = (status(error.code), Rejection.of(error))

  /** A request tapir cannot decode still answers in the contract's error body, never plain text. */
  private val options: Http4sServerOptions[IO] =
    Http4sServerOptions
      .customiseInterceptors[IO]
      .defaultHandlers(message =>
        ValuedEndpointOutput(jsonBody[ErrorOut], ErrorOut(ErrorCode.InvalidArgument.value, message, JsonObject.empty))
      )
      .options

  def routes(core: Core, changes: Signal[IO, Long]): HttpRoutes[IO] =
    stream(core, changes) <+> Http4sServerInterpreter[IO](options).toRoutes(endpoints(core))

  /** A browser always sends `Origin` on a write, so a write carrying one came from the web UI. */
  private val origin = header[Option[String]]("Origin")

  private def actor(origin: Option[String]): Actor =
    Actor(if origin.isDefined then Surface.Web else Surface.Cli, None)

  private def endpoints(core: Core): List[ServerEndpoint[Any, IO]] = List(
    Endpoints.search.serverLogic[IO]: (text, cursor, limit, project) =>
      run:
        for
          binding <- lift(project.traverse(Requests.projectKey("Tikka-Project", _)))
          page <- call(core.search(text.getOrElse(""), binding, cursor, limit.getOrElse(Core.defaultLimit)))
        yield SearchOut.from(page)
    ,
    Endpoints.get.serverLogic[IO]: (text, include) =>
      run:
        for
          id <- lift(Requests.issueId(text))
          events <- lift(includeEvents(include))
          view <- call(core.get(id, events))
        yield IssueOut.from(view)
    ,
    Endpoints.create
      .in(origin)
      .serverLogic[IO]: (project, body, from) =>
        run:
          for
            binding <- lift(project.traverse(Requests.projectKey("Tikka-Project", _)))
            command <- lift(Requests.create(body))
            written <- call(core.create(command, binding, actor(from)))
          yield WrittenOut.from(written)
    ,
    Endpoints.update
      .in(origin)
      .serverLogic[IO]: (text, body, from) =>
        run:
          for
            id <- lift(Requests.issueId(text))
            command <- lift(Requests.update(body))
            written <- call(core.update(id, command, actor(from)))
          yield WrittenOut.from(written)
    ,
    Endpoints.claim
      .in(origin)
      .serverLogic[IO]: (text, body, from) =>
        run:
          for
            id <- lift(Requests.issueId(text))
            who <- lift(Requests.assignee("assignee", body.assignee))
            claimed <- call(core.claim(id, who, body.comment, actor(from)))
          yield ClaimedOut.from(claimed)
    ,
    Endpoints.release
      .in(origin)
      .serverLogic[IO]: (text, body, from) =>
        run:
          for
            id <- lift(Requests.issueId(text))
            who <- lift(Requests.assignee("assignee", body.assignee))
            row <- call(core.release(id, who, body.comment, actor(from)))
          yield RowOnly(RowOut.from(row))
    ,
    Endpoints.reassign
      .in(origin)
      .serverLogic[IO]: (text, body, from) =>
        run:
          for
            id <- lift(Requests.issueId(text))
            previous <- lift(Requests.holder("from", body.from))
            next <- lift(Requests.holder("to", body.to))
            row <- call(core.reassign(id, previous, next, body.comment, actor(from)))
          yield RowOnly(RowOut.from(row))
    ,
    Endpoints.close
      .in(origin)
      .serverLogic[IO]: (text, body, from) =>
        run:
          for
            id <- lift(Requests.issueId(text))
            resolution <- lift(Requests.resolution(body.resolution))
            comment <- lift(Requests.requiredComment(body.comment))
            closed <- call(core.close(id, resolution, comment, actor(from)))
          yield ClosedOutWire.from(closed)
    ,
    Endpoints.reopen
      .in(origin)
      .serverLogic[IO]: (text, body, from) =>
        run:
          for
            id <- lift(Requests.issueId(text))
            comment <- lift(Requests.requiredComment(body.comment))
            written <- call(core.reopen(id, comment, actor(from)))
          yield WrittenOut.from(written)
    ,
    Endpoints.history.serverLogic[IO]: (text, cursor, limit) =>
      run:
        for
          id <- lift(Requests.issueId(text))
          after <- lift(sequenceCursor(cursor))
          slice <- call(core.history(id, after, limit.getOrElse(Core.defaultLimit)))
        yield HistoryOut(
          slice.events.map(EventOut.from),
          slice.events.lastOption.filter(_ => slice.hasMore).map(_.seq.value.toString),
          slice.hasMore
        )
    ,
    Endpoints.feed.serverLogic[IO]: (after, limit) =>
      run:
        val from = after.getOrElse(0L)
        call(core.feed(from, limit.getOrElse(Core.defaultLimit))).map: slice =>
          FeedOut(slice.events.map(EventOut.from), slice.events.lastOption.fold(from)(_.seq.value), slice.hasMore)
    ,
    Endpoints.meta.serverLogic[IO]: _ =>
      core.latestEvent.map(latest => Right(MetaOut(BuildVersion.current.value, Mcp.supportedVersions, latest))),
    Endpoints.projects.serverLogic[IO]: _ =>
      core.projectCounts.map(counts =>
        Right(counts.map((project, open, ready) => ProjectSummaryOut(project.key.value, project.name, open, ready)))
      ),
    Endpoints.createProject.serverLogic[IO]: body =>
      run:
        for
          key <- lift(Requests.projectKey("key", body.key))
          name <- lift(
            Either.cond(
              body.name.trim.nonEmpty,
              body.name.trim,
              DomainError.InvalidArgument("name", "a project needs a name")
            )
          )
          project <- call(core.createProject(key, name))
        yield ProjectOut.from(project)
  )

  private def includeEvents(include: Option[String]): Either[DomainError, Boolean] = include match
    case None           => Right(false)
    case Some("events") => Right(true)
    case Some(other)    =>
      Left(DomainError.InvalidArgument("include", s"'$other' is not something to include: expected events"))

  private def sequenceCursor(cursor: Option[String]): Either[DomainError, Long] =
    cursor.fold(Right(0L))(text =>
      text.toLongOption
        .filter(_ >= 0)
        .toRight(DomainError.InvalidArgument("cursor", "this cursor is not one tikka issued"))
    )

  // Live events

  private object After extends OptionalQueryParamDecoderMatcher[Long]("after")

  private val keepAlive: FiniteDuration = 15.seconds

  /** Server-sent events: stored events after the resume point first, then each new one as it is written. Each message's
    * `id:` is the event sequence, so a reconnecting `EventSource` resumes exactly where it left off.
    */
  private def stream(core: Core, changes: Signal[IO, Long]): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case request @ GET -> Root / "api" / "events" / "stream" :? After(after) =>
      val resumed = request.headers.get[`Last-Event-Id`].flatMap(_.id.value.toLongOption)
      Ok(live(core, changes, resumed.orElse(after).getOrElse(0L)))

  /** A signal rather than a topic, because a writer must never wait on a reader. A subscriber queue backpressures
    * whoever publishes to it, so a browser tab that has stopped draining its socket would eventually hold up the write
    * that woke it. Conflating is safe here: `drain` re-reads from the store by sequence, so the only thing a missed
    * wake-up costs is being folded into the next one, and `discrete` always ends on the latest value.
    */
  def live(core: Core, changes: Signal[IO, Long], from: Long): Stream[IO, ServerSentEvent] =
    // `discrete` opens with the signal's current value, so the first read happens without waiting for a write.
    val events = Stream
      .eval(Ref.of[IO, Long](from))
      .flatMap: last =>
        changes.discrete.evalMap(_ => drain(core, last)).flatMap(Stream.emits)
      .map: event =>
        ServerSentEvent(
          data = Some(EventOut.from(event).asJson.noSpaces),
          eventType = Some("event"),
          id = Some(org.http4s.ServerSentEvent.EventId(event.seq.value.toString))
        )
    events.merge(Stream.awakeEvery[IO](keepAlive).as(ServerSentEvent(comment = Some("keep-alive"))))

  private def drain(core: Core, last: Ref[IO, Long]): IO[List[Event]] =
    last.get.flatMap: from =>
      core
        .feed(from, Core.maxLimit)
        .flatMap:
          case Left(_)      => IO.pure(Nil)
          case Right(slice) =>
            val latest = slice.events.lastOption.fold(from)(_.seq.value)
            last.set(latest) *>
              (if slice.hasMore then drain(core, last).map(slice.events ++ _) else IO.pure(slice.events))

  private def run[A](program: Result[A]): IO[Either[Endpoints.Failure, A]] = program.value.map(_.left.map(failure))

  private def lift[A](value: Either[DomainError, A]): Result[A] = EitherT.fromEither(value)

  private def call[A](program: IO[Either[DomainError, A]]): Result[A] = EitherT(program)
