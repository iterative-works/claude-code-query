// PURPOSE: Spike: cc-chat custom element rendered with Laminar, sharing the SDK model and
// PURPOSE: the ChatView fold; measures the Laminar bundle delta over the rawdom variant.
package works.iterative.claude.uispike.laminarel

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js
import works.iterative.claude.core.log.parsing.ConversationLogParser
import works.iterative.claude.core.parsing.JsonParser
import works.iterative.claude.uispike.view.*

/** Chat element: same contract as the rawdom variant (`history-url` JSONL, `events-url`
  * SSE), with all rendering expressed as Laminar signals.
  */
class CcChatElement extends dom.HTMLElement:
  private var wired = false
  private var fold = ChatView.initial
  private val entries = Var(Vector.empty[EntryView])
  private val status = Var("")

  def connectedCallback(): Unit =
    // connectedCallback re-fires on DOM moves; the element wires itself only once
    if !wired then
      wired = true
      render(
        this,
        div(
          div(
            styleAttr := "display:flex;flex-direction:column;gap:4px;",
            // `children` unqualified resolves to HTMLElement's own DOM property here
            L.children <-- entries.signal.map(_.map(renderEntry))
          ),
          div(
            styleAttr := "color:#666;font-style:italic;min-height:1.2em;",
            child.text <-- status.signal
          )
        )
      )
      Option(getAttribute("history-url")).foreach(loadHistory)
      Option(getAttribute("events-url")).foreach(followEvents)

  private def loadHistory(url: String): Unit =
    dom
      .fetch(url)
      .`then`[String](_.text())
      .`then`[Unit] { (text: String) =>
        val loaded = text.linesIterator
          .flatMap(ConversationLogParser.parseLogLine)
          .flatMap(ChatView.fromLogEntry)
          .toVector
        entries.update(_ ++ loaded)
      }

  private def followEvents(url: String): Unit =
    val source = new dom.EventSource(url)
    source.onmessage = event =>
      JsonParser.parseJsonLine(event.data.toString).foreach { message =>
        val (next, chatEvents) = ChatView.step(fold, message)
        fold = next
        chatEvents.foreach {
          case ChatEvent.Append(entry) => entries.update(_ :+ entry)
          case ChatEvent.Status(text)  => status.set(text)
        }
      }

  private def renderEntry(entry: EntryView): HtmlElement =
    val (rowStyle, bubbleStyle) = entry.role match
      case Role.User =>
        ("display:flex;justify-content:flex-end;", "background:#eff6ff;color:#1e40af;")
      case Role.Assistant if entry.subagent =>
        ("display:flex;margin-left:24px;", "background:#f9fafb;color:#6b7280;font-size:12px;")
      case Role.Assistant =>
        ("display:flex;", "background:#f9fafb;color:#1f2937;")
      case Role.Error =>
        ("display:flex;", "background:#fef2f2;color:#b91c1c;")
    div(
      styleAttr := rowStyle,
      div(
        styleAttr := bubbleStyle + "padding:6px 10px;border-radius:8px;max-width:65ch;white-space:pre-wrap;",
        entry.text
      )
    )

object CcChatLaminar:
  def main(args: Array[String]): Unit =
    val registry = dom.window.customElements
    if js.isUndefined(registry.asInstanceOf[js.Dynamic].get("cc-chat")) then
      registry.define("cc-chat", js.constructorOf[CcChatElement])
