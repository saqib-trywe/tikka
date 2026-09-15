package tikka.cli

import cats.effect.{ExitCode, IO, IOApp}
import sttp.client4.curl.cats.CurlCatsBackend
import sttp.model.Uri
import sttp.tapir.client.sttp4.SttpClientInterpreter
import tikka.shared.*

object Main extends IOApp:
  private val base: Uri = Uri.unsafeParse(sys.env.getOrElse("TIKKA_URL", "http://127.0.0.1:7474"))

  def run(args: List[String]): IO[ExitCode] =
    val backend = CurlCatsBackend[IO]()
    val interp  = SttpClientInterpreter()
    val program = args match
      case "search" :: rest =>
        interp.toRequestThrowDecodeFailures(Endpoints.search, Some(base))(rest.headOption).send(backend).flatMap { resp =>
          resp.body match
            case Right(result)  => IO.println(Render.rows(result)).as(ExitCode.Success)
            case Left((_, err)) => IO.consoleForIO.errorln(s"${err.error}: ${err.message}").as(ExitCode(1))
        }
      case "new" :: title :: Nil =>
        Title.parse(title) match
          case Left(msg) => IO.consoleForIO.errorln(s"invalid_argument: $msg").as(ExitCode(2))
          case Right(t) =>
            interp.toRequestThrowDecodeFailures(Endpoints.create, Some(base))(CreateIssue(t)).send(backend).flatMap { resp =>
              resp.body match
                case Right(issue)   => IO.println(Render.row(issue)).as(ExitCode.Success)
                case Left((_, err)) => IO.consoleForIO.errorln(s"${err.error}: ${err.message}").as(ExitCode(1))
            }
      case _ => IO.consoleForIO.errorln("usage: tikka search [query] | tikka new <title>").as(ExitCode(2))
    program.handleErrorWith(e => IO.consoleForIO.errorln(s"daemon unreachable: ${e.getMessage}").as(ExitCode(3)))
