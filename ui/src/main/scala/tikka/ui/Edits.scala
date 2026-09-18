package tikka.ui

import com.raquo.laminar.api.L.*
import tikka.shared.Wire.*

/** The edits the UI allows: a comment, labels, closing and reopening, and reassigning.
  *
  * Deliberately not here: creating issues, editing a title or body (`tikka edit` has the version-guarded `$EDITOR`
  * flow), editing edges, and claiming — the UI has no identity to claim as.
  */
object Edits:
  def panel(issue: IssueOut, refresh: () => Unit): HtmlElement =
    div(
      cls := "edits",
      comment(issue, refresh),
      labels(issue, refresh),
      assignee(issue, refresh),
      status(issue, refresh)
    )

  private def comment(issue: IssueOut, refresh: () => Unit): HtmlElement =
    val text = Var("")
    val failed = Var(Option.empty[String])
    div(
      cls := "edit comment",
      textArea(placeholder := "comment", controlled(value <-- text, onInput.mapToValue --> text)),
      button(
        "comment",
        disabled <-- text.signal.map(_.trim.isEmpty),
        onClick --> { _ =>
          send(
            Client.update(issue.id, UpdateIn(comment = Some(text.now()))),
            failed,
            () =>
              text.set(""); refresh()
          )
        }
      ),
      problem(failed)
    )

  private def labels(issue: IssueOut, refresh: () => Unit): HtmlElement =
    val adding = Var("")
    val failed = Var(Option.empty[String])
    div(
      cls := "edit labels",
      issue.labels.map(label =>
        span(
          cls := "label",
          label,
          button(
            cls := "remove",
            "×",
            onClick --> { _ =>
              send(Client.update(issue.id, UpdateIn(labelsRemove = Some(List(label)))), failed, refresh)
            }
          )
        )
      ),
      input(placeholder := "label", controlled(value <-- adding, onInput.mapToValue --> adding)),
      button(
        "add",
        disabled <-- adding.signal.map(_.trim.isEmpty),
        onClick --> { _ =>
          send(
            Client.update(issue.id, UpdateIn(labelsAdd = Some(List(adding.now().trim)))),
            failed,
            () =>
              adding.set(""); refresh()
          )
        }
      ),
      problem(failed)
    )

  /** Reassigning, including to nobody, which is how a stale claim left by a dead session is cleared. */
  private def assignee(issue: IssueOut, refresh: () => Unit): HtmlElement =
    val next = Var("")
    val failed = Var(Option.empty[String])
    div(
      cls := "edit assignee",
      input(placeholder := "assignee, or none", controlled(value <-- next, onInput.mapToValue --> next)),
      button(
        "reassign",
        disabled <-- next.signal.map(_.trim.isEmpty),
        onClick --> { _ =>
          send(
            Client.reassign(issue.id, issue.assignee.getOrElse("none"), next.now().trim),
            failed,
            () =>
              next.set(""); refresh()
          )
        }
      ),
      problem(failed)
    )

  /** Closing and reopening always carry a comment saying why. */
  private def status(issue: IssueOut, refresh: () => Unit): HtmlElement =
    val why = Var("")
    val failed = Var(Option.empty[String])
    val closed = issue.status == "closed"
    div(
      cls := "edit status",
      input(placeholder := "why", controlled(value <-- why, onInput.mapToValue --> why)),
      if closed then
        button(
          "reopen",
          disabled <-- why.signal.map(_.trim.isEmpty),
          onClick --> { _ =>
            send(
              Client.reopen(issue.id, why.now()),
              failed,
              () =>
                why.set(""); refresh()
            )
          }
        )
      else
        span(
          button(
            "close done",
            disabled <-- why.signal.map(_.trim.isEmpty),
            onClick --> { _ =>
              send(
                Client.close(issue.id, "done", why.now()),
                failed,
                () =>
                  why.set(""); refresh()
              )
            }
          ),
          button(
            "close dropped",
            disabled <-- why.signal.map(_.trim.isEmpty),
            onClick --> { _ =>
              send(
                Client.close(issue.id, "dropped", why.now()),
                failed,
                () =>
                  why.set(""); refresh()
              )
            }
          )
        )
      ,
      problem(failed)
    )

  private def problem(failed: Var[Option[String]]): HtmlElement =
    div(child <-- failed.signal.map(_.fold(div(cls := "empty"))(message => p(cls := "failure", message))))

  /** A rejection is shown where the edit was made, in the daemon's own words. */
  private def send[A](
      request: EventStream[Either[String, A]],
      failed: Var[Option[String]],
      done: () => Unit
  ): Unit =
    request.foreach {
      case Left(message) => failed.set(Some(message))
      case Right(_)      =>
        failed.set(None)
        done()
    }(using unsafeWindowOwner): Unit
