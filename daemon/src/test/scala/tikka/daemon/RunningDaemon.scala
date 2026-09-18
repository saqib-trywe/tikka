package tikka.daemon

import cats.effect.IO
import cats.effect.Resource
import io.circe.Json
import io.circe.parser.parse
import org.http4s.Header
import org.http4s.Headers
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString
import tikka.core.Migrations
import tikka.shared.DaemonConfig
import tikka.shared.Port

import java.net.ServerSocket
import scala.util.Using

/** A real daemon on a free port with a fresh home, and a raw HTTP client pointed at it.
  *
  * Raw rather than typed where it matters: hardening lives in middleware, and the MCP adapter is judged on exactly what
  * it puts on the wire.
  */
final case class Live(home: Home, daemon: Daemon, client: Client[IO]):
  val port: Int = home.config.port.value

  val base: Uri = Uri.unsafeFromString(s"http://127.0.0.1:$port")

  def send(
      method: Method,
      path: String,
      body: Option[Json] = None,
      headers: List[(String, String)] = Nil
  ): IO[(Int, Json)] =
    val request = Request[IO](method, Uri.unsafeFromString(s"$base$path"))
      .withHeaders(Headers(headers.map((name, value) => Header.Raw(CIString(name), value))))
    val withBody = body.fold(request)(json => request.withEntity(json))
    client
      .run(withBody)
      .use: response =>
        response.bodyText.compile.string.map: text =>
          (response.status.code, parse(text).getOrElse(Json.fromString(text)))

  /** The headers of an answer, for the checks that are about them rather than the body. */
  def headersOf(method: Method, path: String): IO[Headers] =
    client.run(Request[IO](method, Uri.unsafeFromString(s"$base$path"))).use(response => IO.pure(response.headers))

trait RunningDaemon extends DaemonFixtures:
  def live[A](value: Home, uiDirectory: Option[java.nio.file.Path] = None)(use: Live => IO[A]): IO[A] =
    val home = value.copy(config = DaemonConfig(Port.parse(freePort().toLong).fold(sys.error, identity)))
    val running =
      for
        _ <- Resource.eval(Migrations.run(home.store, Some(home.backups)))
        daemon <- Daemon.resource(home, uiDirectory = uiDirectory)
        client <- EmberClientBuilder.default[IO].build
      yield Live(home, daemon, client)
    running.use(use)

  /** Hardening checks the port in `Host`, so it has to be known before the server binds. */
  private def freePort(): Int = Using.resource(ServerSocket(0))(_.getLocalPort)
