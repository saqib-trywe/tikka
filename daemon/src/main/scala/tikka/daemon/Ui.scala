package tikka.daemon

import cats.data.OptionT
import cats.effect.IO
import fs2.io.file.Files
import fs2.io.file.Path
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.StaticFile
import org.http4s.Status
import org.http4s.dsl.io.*

/** The web UI: its assets, and `index.html` for every other page, so a link like `/i/TIK-42` opens the app there.
  *
  * Built assets are embedded in the jar under `ui/`. With `TIKKA_UI_DIR` set, files there win, so a development loop of
  * `sbt ~ui/fastLinkJS` needs only a browser refresh.
  */
object Ui:
  def routes(developmentDirectory: Option[java.nio.file.Path]): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case request @ GET -> "assets" /: rest =>
      val name = rest.segments.map(_.decoded()).mkString("/")
      // An unknown asset is a 404, never the app: a missing script must fail loudly, not load HTML as JavaScript.
      if name.isEmpty || name.contains("..") then IO.pure(Response[IO](Status.NotFound))
      else file(developmentDirectory, name, request).getOrElse(Response[IO](Status.NotFound))
    case request @ GET -> path if !reserved(path.segments.headOption.map(_.decoded())) =>
      file(developmentDirectory, "index.html", request).getOrElse(Response[IO](Status.NotFound))

  /** The API and MCP answer for themselves, including their own 404s. */
  private def reserved(first: Option[String]): Boolean = first.exists(Set("api", "mcp").contains)

  private def file(
      developmentDirectory: Option[java.nio.file.Path],
      name: String,
      request: Request[IO]
  ): OptionT[IO, Response[IO]] =
    val fromDisk = developmentDirectory match
      case None            => OptionT.none[IO, Response[IO]]
      case Some(directory) =>
        val candidate = Path.fromNioPath(directory.resolve(name))
        OptionT
          .liftF(Files[IO].exists(candidate))
          .flatMap: present =>
            if present then StaticFile.fromPath(candidate, Some(request)) else OptionT.none
    fromDisk.orElse(StaticFile.fromResource(s"ui/$name", Some(request)))
