// PURPOSE: Identifying information about a live session (the vendor-assigned session id)
// PURPOSE: Carries the session id the vendor assigned, known once the CLI names the session

package works.iterative.claude.core.model

/** Identifying information about a live session.
  *
  * `sessionId` is the id the vendor assigned — always a real value, never a
  * sentinel: `Session.info` blocks until the CLI names the session (via its
  * init message or the first result) rather than returning a placeholder.
  */
case class SessionInfo(sessionId: SessionId)
