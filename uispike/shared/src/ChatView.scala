// PURPOSE: Spike: the shared chat view model and the live-stream fold, written once against
// PURPOSE: the SDK model and used by both spike renderers (rawdom and laminar).
package works.iterative.claude.uispike.view

import works.iterative.claude.core.log.model.*
import works.iterative.claude.core.model.*

/** What a rendered transcript row is, independent of any rendering technology. */
enum Role:
  case User, Assistant, Error

final case class EntryView(role: Role, subagent: Boolean, text: String)

/** What the renderer must do in response to one live message. */
enum ChatEvent:
  case Append(entry: EntryView)
  case Status(text: String)

/** Maps SDK model values to view entries and folds the live stream.
  *
  * The fold ports the session-contract knowledge from ADR 0001: a turn's text
  * normally arrives as an AssistantMessage; the terminal ResultMessage carries it
  * only as a fallback (never render both), origin-present results are background
  * notifications (never clear the status for them), and a subagent's reply is
  * demoted rather than passed off as the main agent's.
  */
object ChatView:

  final case class FoldState(textShownThisTurn: Boolean)
  val initial: FoldState = FoldState(textShownThisTurn = false)

  def step(state: FoldState, message: Message): (FoldState, List[ChatEvent]) =
    message match
      case AssistantMessage(content, _, parentToolUseId, _) =>
        val text = content.collect { case TextBlock(t) => t }.mkString.trim
        val toolStatuses =
          content.collect { case ToolUseBlock(_, name, _) => ChatEvent.Status(s"Working… $name") }
        if text.nonEmpty then
          val entry = EntryView(Role.Assistant, subagent = parentToolUseId.isDefined, text = text)
          (FoldState(textShownThisTurn = true), toolStatuses :+ ChatEvent.Append(entry))
        else (state, toolStatuses)

      case result: ResultMessage if result.origin.isEmpty =>
        val fallback =
          if state.textShownThisTurn then Nil
          else
            result.result
              .map(_.trim)
              .filter(_.nonEmpty)
              .map(t => ChatEvent.Append(EntryView(Role.Assistant, subagent = false, text = t)))
              .toList
        val events =
          if result.isError then
            List(ChatEvent.Append(EntryView(Role.Error, subagent = false, text = "Agent error.")), ChatEvent.Status(""))
          else fallback :+ ChatEvent.Status("")
        (FoldState(textShownThisTurn = false), events)

      case _ =>
        (state, Nil)

  /** Renders an archive entry to a view row; entries with nothing to show yield None. */
  def fromLogEntry(entry: ConversationLogEntry): Option[EntryView] =
    entry.payload match
      case UserLogEntry(content) =>
        textOf(content).map(t => EntryView(Role.User, subagent = entry.isSidechain, text = t))
      case a: AssistantLogEntry =>
        textOf(a.content).map(t => EntryView(Role.Assistant, subagent = entry.isSidechain, text = t))
      case _ => None

  private def textOf(content: List[ContentBlock]): Option[String] =
    val text = content.collect { case TextBlock(t) => t }.mkString.trim
    Option.when(text.nonEmpty)(text)
