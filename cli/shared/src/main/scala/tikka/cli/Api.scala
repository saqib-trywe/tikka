package tikka.cli

import cats.effect.ExitCode
import cats.effect.IO
import io.circe.Encoder
import io.circe.syntax.*
import sttp.client4.Backend
import sttp.client4.SttpClientException
import sttp.model.Uri
import sttp.tapir.PublicEndpoint
import sttp.tapir.client.sttp4.SttpClientInterpreter
import tikka.shared.Endpoints
import tikka.shared.Render
import tikka.shared.Wire.*

/** Why a command did not get its result. */
enum Failure:
  /** The daemon refused the request, with the contract's error body. */
  case Rejected(error: ErrorOut)

  /** Nothing answered at the daemon's address. */
  case Unreachable(address: String)

object Failure:
  /** 1 for a domain rejection, 3 for an unreachable daemon; 2 (usage) is decided before any request is sent. */
  def exitCode(failure: Failure): ExitCode = failure match
    case Rejected(_)    => ExitCode(1)
    case Unreachable(_) => ExitCode(3)

/** The daemon's HTTP API, through the tapir-derived client every surface shares. */
final class Api(backend: Backend[IO], settings: Settings):
  private val interpreter = SttpClientInterpreter()
  private val base = Some(Uri.unsafeParse(settings.baseUrl))
  private val bound = settings.project.map(_.value)

  def search(query: String, cursor: Option[String], limit: Option[Int]): IO[Either[Failure, SearchOut]] =
    call(Endpoints.search, (Some(query), cursor, limit, bound))

  def get(id: String, events: Boolean): IO[Either[Failure, IssueOut]] =
    call(Endpoints.get, (id, Option.when(events)("events")))

  def create(body: CreateIn): IO[Either[Failure, WrittenOut]] = call(Endpoints.create, (bound, body))

  def update(id: String, body: UpdateIn): IO[Either[Failure, WrittenOut]] = call(Endpoints.update, (id, body))

  def claim(id: String, body: ClaimIn): IO[Either[Failure, ClaimedOut]] = call(Endpoints.claim, (id, body))

  def release(id: String, body: ClaimIn): IO[Either[Failure, RowOnly]] = call(Endpoints.release, (id, body))

  def reassign(id: String, body: ReassignIn): IO[Either[Failure, RowOnly]] = call(Endpoints.reassign, (id, body))

  def close(id: String, body: CloseIn): IO[Either[Failure, ClosedOutWire]] = call(Endpoints.close, (id, body))

  def reopen(id: String, body: ReopenIn): IO[Either[Failure, WrittenOut]] = call(Endpoints.reopen, (id, body))

  def meta: IO[Either[Failure, MetaOut]] = call(Endpoints.meta, ())

  def projects: IO[Either[Failure, List[ProjectOut]]] = call(Endpoints.projects, ())

  def createProject(body: ProjectIn): IO[Either[Failure, ProjectOut]] = call(Endpoints.createProject, body)

  private def call[I, O](endpoint: PublicEndpoint[I, Endpoints.Failure, O, Any], input: I): IO[Either[Failure, O]] =
    interpreter
      .toClientThrowDecodeFailures(endpoint, base, backend)
      .apply(input)
      .attempt
      .flatMap:
        case Right(Right(value))                       => IO.pure(Right(value))
        case Right(Left((_, error)))                   => IO.pure(Left(Failure.Rejected(error)))
        case Left(problem) if Api.unreachable(problem) => IO.pure(Left(Failure.Unreachable(settings.baseUrl)))
        case Left(problem)                             => IO.raiseError(problem)

object Api:
  /** A refused connection is sttp's `ConnectException` on the JVM, but curl on Scala Native reports it as a plain
    * `RuntimeException` naming `COULDNT_CONNECT`. Both mean the daemon is not running.
    */
  def unreachable(problem: Throwable): Boolean = problem match
    case _: SttpClientException.ConnectException => true
    case other                                   =>
      Option(other.getMessage).exists(message =>
        message.contains("COULDNT_CONNECT") || message.contains("Connection refused")
      ) || Option(other.getCause).exists(cause => cause != other && unreachable(cause))

/** Printing results and failures the same way for every command. */
object Output:
  /** Text is the same compact rendering MCP returns, so terminal and agent output never drift; `--json` prints the
    * structured result instead, and never switches format when piped.
    */
  def result[A: Encoder](environment: Environment, json: Boolean, value: A)(text: A => String): IO[ExitCode] =
    environment
      .out(if json then value.asJson.deepDropNullValues.spaces2 else text(value))
      .as(ExitCode.Success)

  def failure(environment: Environment, json: Boolean, failure: Failure): IO[ExitCode] =
    val message = failure match
      case Failure.Rejected(error) =>
        if json then error.asJson.deepDropNullValues.spaces2 else Render.error(error)
      case Failure.Unreachable(address) =>
        s"tikka daemon is not reachable at $address. Start it with `tikka daemon start`, " +
          "or `tikka daemon install` if no service is installed."
    environment.err(message).as(Failure.exitCode(failure))

  def handle[A: Encoder](environment: Environment, json: Boolean)(outcome: Either[Failure, A])(
      text: A => String
  ): IO[ExitCode] =
    outcome.fold(failure(environment, json, _), result(environment, json, _)(text))

  def usage(environment: Environment, message: String): IO[ExitCode] =
    environment.err(message).as(ExitCode(2))
