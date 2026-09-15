package tikka.ui

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import sttp.client4.fetch.FetchBackend
import sttp.tapir.client.sttp4.SttpClientInterpreter
import tikka.shared.*
import scala.concurrent.ExecutionContext.Implicits.global

object Main:
  private val backend = FetchBackend()
  private val interp  = SttpClientInterpreter()

  def main(args: Array[String]): Unit =
    val query   = Var("")
    val results = Var[Either[String, SearchResult]](Left("loading…"))

    def load(q: String): Unit =
      interp.toRequestThrowDecodeFailures(Endpoints.search, None)(Option(q).filter(_.nonEmpty)).send(backend).foreach { resp =>
        results.set(resp.body.left.map((_, err) => s"${err.error}: ${err.message}"))
      }

    val app = div(
      h1("tikka — PROTOTYPE walking skeleton"),
      input(placeholder := "query", controlled(value <-- query, onInput.mapToValue --> query)),
      button("search", onClick --> (_ => load(query.now()))),
      child <-- results.signal.map {
        case Left(msg) => p(msg)
        case Right(r)  => ul(r.issues.map(i => li(code(i.id.value), " ", i.title.value)))
      },
    )
    renderOnDomContentLoaded(dom.document.getElementById("app"), app)
    load("")
