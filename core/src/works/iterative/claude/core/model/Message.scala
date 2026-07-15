package works.iterative.claude.core.model

// PURPOSE: Message type hierarchy for Claude Code SDK communication
// PURPOSE: Represents different types of messages exchanged during conversation

import io.circe.Json

// Message types - following Python SDK structure exactly
sealed trait Message

case class UserMessage(content: String) extends Message

case class AssistantMessage(content: List[ContentBlock]) extends Message

case class SystemMessage(
    subtype: String,
    data: Map[String, Any]
) extends Message

case class ResultMessage(
    subtype: String,
    durationMs: Int,
    durationApiMs: Int,
    isError: Boolean,
    numTurns: Int,
    sessionId: String,
    totalCostUsd: Option[Double] = None,
    usage: Option[Map[String, Any]] = None,
    result: Option[String] = None
) extends Message

case object KeepAliveMessage extends Message

case class StreamEventMessage(data: Map[String, Any]) extends Message

/** A wire message the parser has no specific type for — the raw JSON survives
  * verbatim. Covers unknown `type` values and known types whose required fields
  * are missing or malformed. `messageType` is the wire `type` value, or empty
  * when the message carries no string `type` key.
  */
case class UnknownMessage(messageType: String, json: Json) extends Message
