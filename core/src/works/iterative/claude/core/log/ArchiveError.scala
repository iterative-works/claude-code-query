// PURPOSE: Typed error channel for conversation archive custody operations
// PURPOSE: Distinguishes missing sessions, sub-agents, and rejected ids from underlying IO failures

package works.iterative.claude.core.log

/** Errors surfaced by [[ConversationArchive]] operations. Extends `Throwable`
  * so an implementation may also let it propagate through effect types whose
  * error channel is `Throwable`.
  */
enum ArchiveError extends Throwable:

  /** No main transcript exists for the requested session under the configured
    * vendor projects directory and cwd encoding.
    */
  case SessionNotFound(sessionId: String)

  /** No sub-agent transcript records the given parent `tool_use` id in the
    * session's `subagents` directory.
    */
  case SubAgentNotFound(sessionId: String, parentToolUseId: String)

  /** A filesystem operation underlying an archive read or mirror failed. */
  case ArchiveIOError(detail: String, cause: Throwable)

  /** A session or sub-agent id did not match the accepted id shape, so no path
    * was ever derived from it.
    */
  case InvalidSessionId(value: String)

  /** A page cursor was minted against a transcript that no longer is the
    * session's resolved source (e.g. the vendor tree was pruned between pages,
    * so the mirror now wins). The offset would index a different file, so
    * paging stops rather than reading it; restart from the latest page.
    */
  case PageSourceMoved(sessionId: String)

  def message: String = this match
    case SessionNotFound(sessionId) =>
      s"No archived session found for id '$sessionId'"
    case SubAgentNotFound(sessionId, parentToolUseId) =>
      s"No sub-agent joined by tool_use id '$parentToolUseId' in session '$sessionId'"
    case ArchiveIOError(detail, _)  => detail
    case PageSourceMoved(sessionId) =>
      s"Transcript source for session '$sessionId' changed between pages; restart from the latest page"
    case InvalidSessionId(value) =>
      // The rejected value is attacker-shaped by definition: strip control
      // characters and bound the length before it reaches any log line.
      val printable = value.filter(c => c >= ' ' && c != '\u007f').take(40)
      s"Rejected id '$printable': not an accepted session id shape"

  override def getMessage: String = message
