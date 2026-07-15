package works.iterative.claude.core.model

// PURPOSE: Identifier of a live session — the vendor-assigned `session_id`
// PURPOSE: Keeps a session id distinct from other strings and from a RequestId

opaque type SessionId = String

object SessionId:
  def apply(value: String): SessionId = value

  extension (id: SessionId) def value: String = id
