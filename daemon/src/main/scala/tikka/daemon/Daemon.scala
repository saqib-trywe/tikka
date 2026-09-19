package tikka.daemon

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.concurrent.SignallingRef
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import tikka.core.Clock
import tikka.core.Core
import tikka.core.Store

import scala.concurrent.duration.*

/** The running daemon: the store, the core over it, and HTTP and MCP on one port bound to 127.0.0.1 only. Tests start
  * exactly this, so what they exercise is what users run.
  */
final case class Daemon(core: Core, server: Server)

object Daemon:
  def resource(
      home: Home,
      clock: Clock = Clock.system,
      uiDirectory: Option[java.nio.file.Path] = sys.env.get("TIKKA_UI_DIR").map(java.nio.file.Path.of(_))
  ): Resource[IO, Daemon] =
    val port = home.config.port.value
    for
      store <- Store.open(home.store)
      changes <- Resource.eval(SignallingRef[IO, Long](0L))
      sessions <- Resource.eval(Mcp.Sessions.make)
      core = Core(store, clock, changes.update(_ + 1))
      routes = Hardening(port)(Http.routes(core, changes) <+> Mcp.routes(core, sessions) <+> Ui.routes(uiDirectory))
      server <- EmberServerBuilder
        .default[IO]
        .withHost(ipv4"127.0.0.1")
        .withPort(Port.fromInt(port).getOrElse(port"7017"))
        .withHttpApp(routes.orNotFound)
        // Let in-flight writes finish before the store closes.
        .withShutdownTimeout(5.seconds)
        .build
    yield Daemon(core, server)
