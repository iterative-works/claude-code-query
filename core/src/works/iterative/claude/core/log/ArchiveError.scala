// PURPOSE: Typed error channel for conversation archive custody operations
// PURPOSE: Distinguishes missing sessions and sub-agents from underlying IO failures

package works.iterative.claude.core.log

/** Errors surfaced by [[ConversationArchive]] operations. Extends `Throwable`
  * so an implementation may also let it propagate through effect types whose
  * error channel is `Throwable`.
  */
sealed trait ArchiveError extends Throwable:
  def message: String
  override def getMessage: String = message

/** No main transcript exists for the requested session under the configured
  * vendor projects directory and cwd encoding.
  */
case class SessionNotFound(sessionId: String) extends ArchiveError:
  val message = s"No archived session found for id '$sessionId'"

/** No sub-agent transcript records the given parent `tool_use` id in the
  * session's `subagents` directory.
  */
case class SubAgentNotFound(sessionId: String, parentToolUseId: String)
    extends ArchiveError:
  val message =
    s"No sub-agent joined by tool_use id '$parentToolUseId' in session '$sessionId'"

/** A filesystem operation underlying an archive read or mirror failed. */
case class ArchiveIOError(message: String, cause: Throwable)
    extends ArchiveError
