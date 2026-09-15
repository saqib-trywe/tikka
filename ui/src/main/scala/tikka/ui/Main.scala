package tikka.ui

import com.raquo.laminar.api.L.*
import org.scalajs.dom

/** Placeholder entry point: the scaffold proves Laminar links. */
object Main:
  def main(args: Array[String]): Unit =
    renderOnDomContentLoaded(dom.document.getElementById("app"), div("tikka")): Unit
