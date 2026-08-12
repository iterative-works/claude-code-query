// PURPOSE: Spike: cc-chat custom element rendering with raw DOM calls, sharing the SDK
// PURPOSE: model and the ChatView fold; measures the +model/circe bundle cost over bare.
package works.iterative.claude.uispike.rawdom

import org.scalajs.dom
import scala.scalajs.js
import works.iterative.claude.core.log.parsing.ConversationLogParser
import works.iterative.claude.core.parsing.JsonParser
import works.iterative.claude.uispike.view.*

/** Chat element: loads history from `history-url` (JSONL), then follows `events-url` (SSE
  * of Message JSON lines). All rendering is raw DOM; entry semantics come from ChatView.
  */
class CcChatElement extends dom.HTMLElement:
  private var wired = false
  private var fold = ChatView.initial
  private val list = dom.document.createElement("div").asInstanceOf[dom.html.Div]
  private val status = dom.document.createElement("div").asInstanceOf[dom.html.Div]

  def connectedCallback(): Unit =
    // connectedCallback re-fires on DOM moves; the element wires itself only once
    if !wired then
      wired = true
      status.setAttribute("style", "color:#666;font-style:italic;min-height:1.2em;")
      list.setAttribute("style", "display:flex;flex-direction:column;gap:4px;")
      appendChild(list)
      appendChild(status)
      Option(getAttribute("history-url")).foreach(loadHistory)
      Option(getAttribute("events-url")).foreach(followEvents)

  private def loadHistory(url: String): Unit =
    dom
      .fetch(url)
      .`then`[String](_.text())
      .`then`[Unit] { (text: String) =>
        text.linesIterator
          .flatMap(ConversationLogParser.parseLogLine)
          .flatMap(ChatView.fromLogEntry)
          .foreach(appendEntry)
      }

  private def followEvents(url: String): Unit =
    val source = new dom.EventSource(url)
    source.onmessage = event =>
      JsonParser.parseJsonLine(event.data.toString).foreach { message =>
        val (next, events) = ChatView.step(fold, message)
        fold = next
        events.foreach(applyEvent)
      }

  private def applyEvent(event: ChatEvent): Unit = event match
    case ChatEvent.Append(entry) => appendEntry(entry)
    case ChatEvent.Status(text)  => status.textContent = text

  private def appendEntry(entry: EntryView): Unit =
    val row = dom.document.createElement("div").asInstanceOf[dom.html.Div]
    val bubble = dom.document.createElement("div").asInstanceOf[dom.html.Div]
    bubble.textContent = entry.text
    val (rowStyle, bubbleStyle) = entry.role match
      case Role.User =>
        ("display:flex;justify-content:flex-end;", "background:#eff6ff;color:#1e40af;")
      case Role.Assistant if entry.subagent =>
        ("display:flex;margin-left:24px;", "background:#f9fafb;color:#6b7280;font-size:12px;")
      case Role.Assistant =>
        ("display:flex;", "background:#f9fafb;color:#1f2937;")
      case Role.Error =>
        ("display:flex;", "background:#fef2f2;color:#b91c1c;")
    row.setAttribute("style", rowStyle)
    bubble.setAttribute(
      "style",
      bubbleStyle + "padding:6px 10px;border-radius:8px;max-width:65ch;white-space:pre-wrap;"
    )
    row.appendChild(bubble)
    list.appendChild(row)
    list.scrollTop = list.scrollHeight

object CcChatRaw:
  def main(args: Array[String]): Unit =
    val registry = dom.window.customElements
    if js.isUndefined(registry.asInstanceOf[js.Dynamic].get("cc-chat")) then
      registry.define("cc-chat", js.constructorOf[CcChatElement])
