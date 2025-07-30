// PURPOSE: Package object for direct API with convenient imports and type aliases
// PURPOSE: Enables single-import usage with all necessary model classes available

package works.iterative.claude

package object direct:
  // Type aliases for convenient usage
  type QueryOptions = works.iterative.claude.core.model.QueryOptions
  val QueryOptions = works.iterative.claude.core.model.QueryOptions

  // Re-export all model classes that users need
  type Message = works.iterative.claude.core.model.Message
  type UserMessage = works.iterative.claude.core.model.UserMessage
  val UserMessage = works.iterative.claude.core.model.UserMessage
  type AssistantMessage = works.iterative.claude.core.model.AssistantMessage
  val AssistantMessage = works.iterative.claude.core.model.AssistantMessage
  type SystemMessage = works.iterative.claude.core.model.SystemMessage
  val SystemMessage = works.iterative.claude.core.model.SystemMessage
  type ResultMessage = works.iterative.claude.core.model.ResultMessage
  val ResultMessage = works.iterative.claude.core.model.ResultMessage
  type ContentBlock = works.iterative.claude.core.model.ContentBlock
  type TextBlock = works.iterative.claude.core.model.TextBlock
  val TextBlock = works.iterative.claude.core.model.TextBlock
  type ToolUseBlock = works.iterative.claude.core.model.ToolUseBlock
  val ToolUseBlock = works.iterative.claude.core.model.ToolUseBlock
  type ToolResultBlock = works.iterative.claude.core.model.ToolResultBlock
  val ToolResultBlock = works.iterative.claude.core.model.ToolResultBlock
  type PermissionMode = works.iterative.claude.core.model.PermissionMode
  val PermissionMode = works.iterative.claude.core.model.PermissionMode
