package tikka.daemon

import cats.effect.IO
import fs2.io.file.Path as FPath
import org.http4s.{HttpRoutes, MediaType, Response, StaticFile, Status}
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

/** Serves the Laminar SPA: fastLinkJS output from disk in dev mode, fullLinkJS from the jar otherwise. */
object Ui:
  private val index =
    """<!doctype html>
      |<html><head><meta charset="utf-8"><title>tikka (PROTOTYPE)</title>
      |<style>body{font-family:system-ui;margin:2rem;max-width:48rem} code{background:#eee;padding:0 .3rem}</style>
      |</head><body><div id="app"></div><script src="/assets/main.js"></script></body></html>""".stripMargin

  def routes(home: Home): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "assets" / file if file == "main.js" || file == "main.js.map" =>
      val served = home.uiDir match
        case Some(dir) => StaticFile.fromPath(FPath.fromNioPath(dir.resolve(file)), Some(req))
        case None      => StaticFile.fromResource(s"/ui/$file", Some(req))
      served.getOrElseF(NotFound(s"ui asset $file not built"))
    case GET -> path if !Seq("api", "mcp", "assets").exists(p => path.startsWith(Root / p)) =>
      IO.pure(Response[IO](Status.Ok).withEntity(index).withContentType(`Content-Type`(MediaType.text.html)))
  }
