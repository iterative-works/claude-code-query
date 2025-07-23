package works.iterative.claude.model

// PURPOSE: Message type hierarchy for Claude Code SDK communication
// PURPOSE: Represents different types of messages exchanged during conversation

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
