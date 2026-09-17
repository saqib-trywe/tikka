package tikka.daemon

import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.Response
import org.http4s.Status
import org.http4s.Uri
import org.http4s.circe.*
import org.http4s.headers.Host
import org.http4s.headers.Origin
import tikka.shared.Wire.ErrorOut

/** What makes "no auth" true: only this machine's own pages and tools can reach the daemon.
  *
  * The daemon binds 127.0.0.1, but a web page in the user's browser can still send requests there, and a DNS rebinding
  * attack can make one look same-origin. So every route checks that `Host` names this daemon and that any `Origin` is
  * this daemon's own.
  */
object Hardening:
  private val loopback = Set("127.0.0.1", "localhost")

  def apply(port: Int)(routes: HttpRoutes[IO]): HttpRoutes[IO] = Kleisli: request =>
    val hostAllowed = request.headers
      .get[Host]
      .exists(host => loopback.contains(host.host) && host.port.forall(_ == port))
    val originAllowed = request.headers.get[Origin] match
      case None | Some(Origin.Null)     => true
      case Some(Origin.HostList(hosts)) =>
        hosts.forall(origin =>
          origin.scheme == Uri.Scheme.http && loopback.contains(origin.host.value) && origin.port.contains(port)
        )
    if hostAllowed && originAllowed then routes(request)
    else
      val body =
        ErrorOut("forbidden", "Refused: this request did not come from this machine's tikka daemon.", JsonObject.empty)
      OptionT.some(Response[IO](Status.Forbidden).withEntity(body.asJson))
