package tikka.ui

import com.raquo.laminar.api.L.*
import io.circe.parser.decode
import org.scalajs.dom
import tikka.shared.Wire.EventOut

/** The daemon's event stream, as it happens.
  *
  * The browser's `EventSource` reconnects on its own and resends the last event id it saw, and the daemon replays from
  * there, so a dropped connection loses nothing. It starts from the latest event at load, rather than replaying the
  * whole log.
  */
object Live:
  private val bus: EventBus[EventOut] = EventBus[EventOut]()

  /** Every event written since this page loaded. */
  val events: EventStream[EventOut] = bus.events

  def start(after: Long): Unit =
    val source = dom.EventSource(s"/api/events/stream?after=$after")
    source.addEventListener[dom.MessageEvent](
      "event",
      (message: dom.MessageEvent) => decode[EventOut](message.data.toString).foreach(event => bus.writer.onNext(event))
    )
