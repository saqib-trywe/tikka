package tikka.ui

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import sttp.client4.fetch.FetchBackend
import sttp.model.Uri
import sttp.tapir.client.sttp4.SttpClientInterpreter
import tikka.shared.*
import scala.concurrent.ExecutionContext.Implicits.global

object Main:
  private val backend = FetchBackend()
  private val interp  = SttpClientInterpreter()
  private val origin  = Some(Uri.unsafeParse(dom.window.location.origin))

  private def fail(err: ErrorBody): String = s"${err.error}: ${err.message}"

  private def searchView(): HtmlElement =
    val query   = Var(Option(dom.URLSearchParams(dom.window.location.search).get("q")).getOrElse(""))
    val results = Var[Either[String, SearchResult]](Left("loading…"))
    def load(): Unit =
      val q = query.now()
      dom.window.history.replaceState(null, "", if q.isEmpty then "/" else s"/?q=${scala.scalajs.js.URIUtils.encodeURIComponent(q)}")
      interp.toRequestThrowDecodeFailures(Endpoints.search, origin)(Option(q).filter(_.nonEmpty)).send(backend)
        .foreach(resp => results.set(resp.body.left.map((_, e) => fail(e))))
    load()
    div(
      input(placeholder := "query (the skeleton accepts only empty)", controlled(value <-- query, onInput.mapToValue --> query)),
      button("search", onClick --> (_ => load())),
      child <-- results.signal.map {
        case Left(msg) => p(msg)
        case Right(r)  => div(p(s"effective query: ${r.effective_query}"),
          ul(r.issues.map(i => li(a(href := s"/i/${i.id.value}", code(i.id.value)), " ", i.title.value))))
      },
    )

  private def issueView(raw: String): HtmlElement =
    val state = Var[Either[String, Issue]](Left("loading…"))
    IssueId.parse(raw) match
      case Left(msg) => state.set(Left(msg))
      case Right(id) =>
        interp.toRequestThrowDecodeFailures(Endpoints.get, origin)(id).send(backend)
          .foreach(resp => state.set(resp.body.left.map((_, e) => fail(e))))
    div(a(href := "/", "← all issues"), child <-- state.signal.map {
      case Left(msg) => p(msg)
      case Right(i)  => div(h2(code(i.id.value), " ", i.title.value), p("open"))
    })

  def main(args: Array[String]): Unit =
    val path = dom.window.location.pathname
    val view = if path.startsWith("/i/") then issueView(path.stripPrefix("/i/")) else searchView()
    renderOnDomContentLoaded(dom.document.getElementById("app"), div(h1("tikka — PROTOTYPE walking skeleton"), view))
