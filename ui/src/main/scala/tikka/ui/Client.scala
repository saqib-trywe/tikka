package tikka.ui

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import sttp.client4.fetch.FetchBackend
import sttp.model.Uri
import sttp.tapir.PublicEndpoint
import sttp.tapir.client.sttp4.SttpClientInterpreter
import tikka.shared.Endpoints
import tikka.shared.Render
import tikka.shared.Wire.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

/** The daemon's API through the same tapir endpoints the daemon serves, so a change to a route is a compile error here
  * too. The UI is served by the daemon, so its own origin is the address.
  */
object Client:
  private val backend = FetchBackend()
  private val interpreter = SttpClientInterpreter()
  private val origin = Some(Uri.unsafeParse(dom.window.location.origin))

  def search(query: String, cursor: Option[String]): EventStream[Either[String, SearchOut]] =
    call(Endpoints.search, (Some(query), cursor, None, None))

  def issue(id: String): EventStream[Either[String, IssueOut]] = call(Endpoints.get, (id, Some("events")))

  def projects: EventStream[Either[String, List[ProjectSummaryOut]]] = call(Endpoints.projects, ())

  def meta: EventStream[Either[String, MetaOut]] = call(Endpoints.meta, ())

  def update(id: String, change: UpdateIn): EventStream[Either[String, WrittenOut]] =
    call(Endpoints.update, (id, change))

  def close(id: String, resolution: String, comment: String): EventStream[Either[String, ClosedOutWire]] =
    call(Endpoints.close, (id, CloseIn(resolution, comment)))

  def reopen(id: String, comment: String): EventStream[Either[String, WrittenOut]] =
    call(Endpoints.reopen, (id, ReopenIn(comment)))

  def reassign(id: String, from: String, to: String): EventStream[Either[String, RowOnly]] =
    call(Endpoints.reassign, (id, ReassignIn(from, to, None)))

  private def call[I, O](
      endpoint: PublicEndpoint[I, Endpoints.Failure, O, Any],
      input: I
  ): EventStream[Either[String, O]] =
    val answered: Future[Either[String, O]] = interpreter
      .toClientThrowDecodeFailures(endpoint, origin, backend)
      .apply(input)
      .transform:
        case Success(Right(value))     => Success(Right(value))
        case Success(Left((_, error))) => Success(Left(Render.error(error)))
        case Failure(_)                => Success(Left("the tikka daemon is not answering; is it still running?"))
    EventStream.fromFuture(answered)
