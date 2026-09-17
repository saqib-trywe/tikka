package tikka.ui

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.SplitRender
import org.scalajs.dom

/** tikka's reading and reviewing surface. */
object Main:
  private def shell: HtmlElement =
    val pages = SplitRender[Page, HtmlElement](Pages.router.currentPageSignal)
      .collectSignal[Page.Search](Views.search)
      .collectSignal[Page.Issue](Views.issue)
      .collectSignal[Page.Tree](Views.tree)
      .collectStatic(Page.Projects)(Views.projects)
    div(
      cls := "app",
      Views.loadProjects(),
      // The stream starts at the newest event, so a fresh page does not replay the whole log.
      Client.meta --> Observer[Either[String, tikka.shared.Wire.MetaOut]]:
        case Right(meta) => Live.start(meta.latestEvent)
        case Left(_)     => ()
      ,
      headerTag(
        cls := "bar",
        Pages.link(Page.Search(None), cls := "brand", "tikka"),
        Pages.link(Page.Projects, "projects")
      ),
      mainTag(child <-- pages.signal)
    )

  def main(args: Array[String]): Unit =
    renderOnDomContentLoaded(dom.document.getElementById("app"), shell)
