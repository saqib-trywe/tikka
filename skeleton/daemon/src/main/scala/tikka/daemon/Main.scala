package tikka.daemon

import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

object Main extends IOApp:
  given LoggerFactory[IO] = NoOpFactory[IO]

  def run(args: List[String]): IO[ExitCode] = args match
    case "run" :: Nil =>
      val daemon = for
        home   <- Resource.eval(Home.load)
        _      <- Home.lock(home)
        report <- Resource.eval(Store.migrate(home))
        store  <- Store.resource(home)
        core    = Core(store)
        sessions <- Resource.eval(Ref.of[IO, Map[String, String]](Map.empty))
        app     = Http.hardened(home.port)(Http.routes(core) <+> Mcp.routes(core, sessions) <+> Ui.routes(home)).orNotFound
        _      <- EmberServerBuilder.default[IO]
                    .withHost(ipv4"127.0.0.1")
                    .withPort(Port.fromInt(home.port).get)
                    .withHttpApp(app)
                    .build
        _      <- Resource.eval(IO.println(s"tikka daemon on http://127.0.0.1:${home.port} (home ${home.dir}; $report; ui ${home.uiDir.fold("embedded")(d => s"dev from $d")})"))
      yield ()
      daemon.useForever.as(ExitCode.Success)
    case _ => IO.println("usage: daemon run").as(ExitCode(2))
