package works.iterative.claude.core.model

// PURPOSE: Message type hierarchy for Claude Code SDK communication
// PURPOSE: Represents different types of messages exchanged during conversation

import io.circe.Json

// Message types - following Python SDK structure exactly
sealed trait Message

case class UserMessage(content: String) extends Message

/** An assistant reply on the live stream. `id` is the vendor `uuid` when the
  * wire message carries one (synthetic messages may not); `parentToolUseId`
  * joins subagent output to its sidechain transcript; `model` is the model that
  * produced the reply.
  */
case class AssistantMessage(
    content: List[ContentBlock],
    id: Option[MessageId] = None,
    parentToolUseId: Option[String] = None,
    model: Option[String] = None
) extends Message

case class SystemMessage(
    subtype: String,
    data: Map[String, Any]
) extends Message

/** The end-of-turn envelope on the live stream. `origin` is `None` when the
  * result ends an interactive turn — the wire key is absent there — and present
  * when a background task finished; `id` is the vendor `uuid`; `usage` carries
  * real token counts.
  */
case class ResultMessage(
    subtype: String,
    durationMs: Int,
    durationApiMs: Int,
    isError: Boolean,
    numTurns: Int,
    sessionId: SessionId,
    totalCostUsd: Option[Double] = None,
    usage: Option[TokenUsage] = None,
    result: Option[String] = None,
    id: Option[MessageId] = None,
    origin: Option[ResultOrigin] = None,
    stopReason: Option[String] = None,
    terminalReason: Option[String] = None,
    permissionDenials: List[PermissionDenial] = Nil,
    apiErrorStatus: Option[String] = None,
    timings: ResultTimings = ResultTimings()
) extends Message

case object KeepAliveMessage extends Message

case class StreamEventMessage(data: Map[String, Any]) extends Message

/** A wire message the parser has no specific type for — the raw JSON survives
  * verbatim. Covers unknown `type` values and known types whose required fields
  * are missing or malformed. `messageType` is the wire `type` value, or empty
  * when the message carries no string `type` key.
  */
case class UnknownMessage(messageType: String, json: Json) extends Message

/** Reply to a [[ControlRequest]], arriving interleaved on stdout and correlated
  * to its request by the vendor-supplied `requestId`. `payload` is the inner
  * response object, e.g. `{"still_queued": []}` for an interrupt.
  */
case class ControlResponse(
    requestId: RequestId,
    subtype: String,
    payload: Json
) extends Message
