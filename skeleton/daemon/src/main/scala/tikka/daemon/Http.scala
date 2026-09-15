package tikka.daemon

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import org.http4s.{HttpRoutes, Response, Status}
import org.http4s.headers.{Host, Origin}
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}
import sttp.tapir.server.model.ValuedEndpointOutput
import tikka.shared.*

object Http:
  private def status(err: ErrorBody): StatusCode = err.error match
    case ErrorCode.not_found                               => StatusCode.NotFound
    case ErrorCode.invalid_argument | ErrorCode.invalid_query => StatusCode.BadRequest

  private def failure(err: ErrorBody): Endpoints.Failure = (status(err), err)

  /** Decode failures must still speak the contract: an `invalid_argument` error body, not plain text. */
  private val options: Http4sServerOptions[IO] =
    Http4sServerOptions.customiseInterceptors[IO]
      .defaultHandlers(msg => ValuedEndpointOutput(jsonBody[ErrorBody], ErrorBody(ErrorCode.invalid_argument, msg)))
      .options

  def routes(core: Core): HttpRoutes[IO] =
    Http4sServerInterpreter[IO](options).toRoutes(List[ServerEndpoint[Fs2Streams[IO], IO]](
      Endpoints.search.serverLogic[IO](q => core.search(q).map(_.left.map(failure))),
      Endpoints.get.serverLogic[IO](id => core.get(id).map(_.left.map(failure))),
      Endpoints.create.serverLogic[IO](args => core.create(args).map(Right(_))),
    ))

  /** Localhost hardening: only our own Host, and no foreign Origin (DNS rebinding, cross-site pages). */
  def hardened(port: Int)(routes: HttpRoutes[IO]): HttpRoutes[IO] = Kleisli { req =>
    val hostOk = req.headers.get[Host].exists(h =>
      Set("127.0.0.1", "localhost").contains(h.host) && h.port.contains(port))
    val originOk = req.headers.get[Origin] match
      case None | Some(Origin.Null) => true
      case Some(Origin.HostList(hosts)) =>
        hosts.forall(o => o.scheme == org.http4s.Uri.Scheme.http &&
          Set("127.0.0.1", "localhost").contains(o.host.value) && o.port.contains(port))
    if hostOk && originOk then routes(req)
    else OptionT.some(Response[IO](Status.Forbidden).withEntity(s"refused: host or origin is not this tikka daemon"))
  }
