package tikka.daemon

import cats.effect.IO
import fs2.text
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.Header
import org.http4s.Headers
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.typelevel.ci.CIString
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import sttp.model.Uri as SttpUri
import sttp.tapir.client.sttp4.SttpClientInterpreter
import tikka.shared.*
import tikka.shared.Wire.*

import scala.concurrent.duration.*

/** The HTTP API against a real daemon: the typed client the UI and CLI will use, and the raw wire where it matters. */
class HttpTest extends RunningDaemon:
  private def typed[A](running: Live)(use: TypedApi => IO[A]): IO[A] =
    HttpClientCatsBackend.resource[IO]().use(backend => use(TypedApi(running, backend)))

  final case class TypedApi(running: Live, backend: sttp.client4.Backend[IO]):
    private val interpreter = SttpClientInterpreter()
    private val base = Some(SttpUri.unsafeParse(running.base.renderString))

    def createProject(key: String): IO[Either[Endpoints.Failure, ProjectOut]] =
      interpreter.toClientThrowDecodeFailures(Endpoints.createProject, base, backend)(ProjectIn(key, key))

    def create(project: Option[String], body: CreateIn): IO[Either[Endpoints.Failure, WrittenOut]] =
      interpreter.toClientThrowDecodeFailures(Endpoints.create, base, backend)((project, body))

    def search(query: String, project: Option[String]): IO[Either[Endpoints.Failure, SearchOut]] =
      interpreter.toClientThrowDecodeFailures(Endpoints.search, base, backend)((Some(query), None, None, project))

    def get(id: String): IO[Either[Endpoints.Failure, IssueOut]] =
      interpreter.toClientThrowDecodeFailures(Endpoints.get, base, backend)((id, Some("events")))

    def claim(id: String, who: String): IO[Either[Endpoints.Failure, ClaimedOut]] =
      interpreter.toClientThrowDecodeFailures(Endpoints.claim, base, backend)((id, ClaimIn(who)))

  home.test("the typed client creates, finds and reads an issue, with the header binding applied"): value =>
    live(value): running =>
      typed(running): api =>
        for
          project <- api.createProject("TIK")
          created <- api.create(Some("TIK"), CreateIn("First issue", labels = Some(List("Bug"))))
          found <- api.search("ready", Some("TIK"))
          read <- api.get("TIK-1")
        yield
          assertEquals(project.map(_.key), Right("TIK"))
          assertEquals(created.map(_.row.id), Right("TIK-1"))
          assertEquals(found.map(_.effectiveQuery), Right("project:TIK ready"))
          assertEquals(found.map(_.issues.map(_.id)), Right(List("TIK-1")))
          assertEquals(read.map(_.labels), Right(List("bug")))
          assertEquals(read.map(_.events.map(_.total)), Right(Some(1)))

  home.test("rejections carry the contract's body, with 404, 400 or 409 and nothing else"): value =>
    live(value): running =>
      typed(running): api =>
        for
          _ <- api.createProject("TIK")
          _ <- api.create(Some("TIK"), CreateIn("Contended"))
          _ <- api.claim("TIK-1", "first")
          missing <- api.get("TIK-99")
          badQuery <- api.search("statu:open", None)
          conflict <- api.claim("TIK-1", "second")
        yield
          assertEquals(missing.left.map((status, body) => (status.code, body.error)), Left((404, "not_found")))
          assertEquals(badQuery.left.map((status, body) => (status.code, body.error)), Left((400, "invalid_query")))
          assertEquals(conflict.left.map((status, body) => (status.code, body.error)), Left((409, "claim_conflict")))
          assertEquals(conflict.left.toOption.flatMap(_._2.facts("holder")).flatMap(_.asString), Some("first"))

  home.test("a request tikka cannot decode is still answered in the contract's error body"): value =>
    live(value): running =>
      for (status, body) <- running.send(Method.POST, "/api/issues", Some(Json.obj("nope" -> 1.asJson)))
      yield
        assertEquals(status, 400)
        assertEquals(body.hcursor.get[String]("error"), Right("invalid_argument"))

  home.test("a write from a browser is recorded as web, and one from anything else as cli"): value =>
    live(value): running =>
      val origin = List("Origin" -> s"http://127.0.0.1:${running.port}")
      for
        _ <- running.send(Method.POST, "/api/projects", Some(Json.obj("key" -> "TIK".asJson, "name" -> "Tikka".asJson)))
        _ <- running.send(
          Method.POST,
          "/api/issues",
          Some(Json.obj("title" -> "From the CLI".asJson, "project" -> "TIK".asJson))
        )
        _ <- running.send(
          Method.POST,
          "/api/issues/TIK-1/claim",
          Some(Json.obj("assignee" -> "someone".asJson)),
          origin
        )
        history <- running.send(Method.GET, "/api/issues/TIK-1/events")
      yield
        val actors = history._2.hcursor
          .downField("events")
          .as[List[Json]]
          .getOrElse(Nil)
          .flatMap(_.hcursor.get[String]("actor").toOption)
        assertEquals(actors, List("cli", "web"))

  home.test("a foreign origin or a rebinding host is refused on every route"): value =>
    live(value): running =>
      for
        foreign <- running.send(Method.GET, "/api/meta", headers = List("Origin" -> "http://evil.example"))
        rebinding <- running.send(Method.GET, "/api/meta", headers = List("Host" -> s"evil.example:${running.port}"))
        mcp <- running.send(Method.POST, "/mcp", Some(Json.obj()), List("Origin" -> "http://evil.example"))
        own <- running.send(Method.GET, "/api/meta", headers = List("Origin" -> s"http://localhost:${running.port}"))
      yield
        assertEquals(foreign._1, 403)
        assertEquals(rebinding._1, 403)
        assertEquals(mcp._1, 403)
        assertEquals(own._1, 200)

  home.test("the event feed and an issue's history page by sequence"): value =>
    live(value): running =>
      val project = Json.obj("key" -> "TIK".asJson, "name" -> "Tikka".asJson)
      val issue = Json.obj("title" -> "An issue".asJson, "project" -> "TIK".asJson)
      for
        _ <- running.send(Method.POST, "/api/projects", Some(project))
        _ <- running.send(Method.POST, "/api/issues", Some(issue))
        _ <- running.send(Method.POST, "/api/issues", Some(issue))
        _ <- running.send(Method.POST, "/api/issues", Some(issue))
        first <- running.send(Method.GET, "/api/events?limit=2")
        rest <- running.send(Method.GET, s"/api/events?after=${first._2.hcursor.get[Long]("next_after").getOrElse(0L)}")
        meta <- running.send(Method.GET, "/api/meta")
      yield
        assertEquals(first._2.hcursor.downField("events").as[List[Json]].map(_.size), Right(2))
        assertEquals(first._2.hcursor.get[Boolean]("has_more"), Right(true))
        assertEquals(rest._2.hcursor.downField("events").as[List[Json]].map(_.size), Right(1))
        assertEquals(meta._2.hcursor.get[List[String]]("protocols"), Right(List("2026-07-28", "2025-11-25")))

  home.test("the live stream replays from a resume point, then delivers new events as they are written"): value =>
    live(value): running =>
      val request = Request[IO](Method.GET, Uri.unsafeFromString(s"${running.base}/api/events/stream"))
        .withHeaders(Headers(Header.Raw(CIString("Last-Event-ID"), "1")))
      val firstTwoIds = running.client
        .stream(request)
        .flatMap(_.body)
        .through(text.utf8.decode)
        .through(text.lines)
        .filter(_.startsWith("data:"))
        .map(line => parse(line.drop(5).trim).flatMap(_.hcursor.get[Long]("seq")).getOrElse(-1L))
        .take(2)
        .compile
        .toList
        .timeout(15.seconds)
      val create =
        running.send(Method.POST, "/api/issues", Some(Json.obj("title" -> "Live".asJson, "project" -> "TIK".asJson)))
      for
        _ <- running.send(Method.POST, "/api/projects", Some(Json.obj("key" -> "TIK".asJson, "name" -> "Tikka".asJson)))
        _ <- create
        _ <- create
        reader <- firstTwoIds.start
        _ <- IO.sleep(500.millis)
        _ <- create
        received <- reader.joinWithNever
      yield
        // Resuming after event 1 replays event 2, then the stream delivers event 3 when it is written.
        assertEquals(received, List(2L, 3L))
