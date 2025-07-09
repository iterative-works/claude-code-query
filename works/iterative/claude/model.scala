package works.iterative.claude

// Content block types - the building blocks of messages
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

// Inner message types - the actual message payloads
case class UserMessageContent(content: String)
case class AssistantMessageContent(content: List[ContentBlock])

// SDK Message wrapper types - following TypeScript SDKMessage structure
sealed trait SDKMessage:
  def sessionId: String

case class UserSDKMessage(
  message: UserMessageContent,
  sessionId: String
) extends SDKMessage

case class AssistantSDKMessage(
  message: AssistantMessageContent,
  sessionId: String
) extends SDKMessage

case class ResultSDKMessage(
  subtype: ResultSubtype,
  durationMs: Int,
  durationApiMs: Int,
  isError: Boolean,
  numTurns: Int,
  result: Option[String],
  sessionId: String,
  totalCostUsd: Double
) extends SDKMessage

case class SystemSDKMessage(
  subtype: SystemSubtype,
  apiKeySource: String,
  cwd: String,
  sessionId: String,
  tools: List[String],
  mcpServers: List[McpServerStatus],
  model: String,
  permissionMode: PermissionMode
) extends SDKMessage

enum ResultSubtype:
  case Success
  case ErrorMaxTurns
  case ErrorDuringExecution

enum SystemSubtype:
  case Init

enum PermissionMode:
  case Default
  case AcceptEdits
  case BypassPermissions
  case Plan

case class McpServerStatus(
  name: String,
  status: String
)