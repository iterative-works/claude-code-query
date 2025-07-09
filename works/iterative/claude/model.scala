package works.iterative.claude

// Content block types - simplified like Python SDK
sealed trait ContentBlock

case class TextBlock(text: String) extends ContentBlock

case class ToolUseBlock(
  id: String,
  name: String,
  input: Map[String, Any]
) extends ContentBlock

case class ToolResultBlock(
  toolUseId: String,
  content: Option[String] = None,
  isError: Option[Boolean] = None
) extends ContentBlock

// Permission modes
enum PermissionMode:
  case Default
  case AcceptEdits  
  case BypassPermissions

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