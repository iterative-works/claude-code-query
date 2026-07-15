package works.iterative.claude.core.model

// PURPOSE: Identifier of a control request — correlates a ControlRequest with its ControlResponse
// PURPOSE: Keeps a request id distinct from other strings and from a SessionId

opaque type RequestId = String

object RequestId:
  def apply(value: String): RequestId = value

  extension (id: RequestId) def value: String = id
