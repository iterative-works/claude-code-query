package works.iterative.claude.core.model

// PURPOSE: Control-channel request written to the CLI's stdin, answered by a ControlResponse
// PURPOSE: Correlated by the caller-chosen requestId; the request body vocabulary stays open

import io.circe.Json

case class ControlRequest(requestId: String, request: ControlRequestBody)

enum ControlRequestBody:
  /** Stop the running turn; the session survives. */
  case Interrupt

  /** Any other control request subtype. */
  case Other(subtype: String, payload: Json)
