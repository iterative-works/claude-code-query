// PURPOSE: Package object for effectful API with convenient imports and type aliases
// PURPOSE: Enables single-import usage with all necessary model classes available

package works.iterative.claude

package object effectful:
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
  type ThinkingBlock = works.iterative.claude.core.model.ThinkingBlock
  val ThinkingBlock = works.iterative.claude.core.model.ThinkingBlock
  type RedactedThinkingBlock =
    works.iterative.claude.core.model.RedactedThinkingBlock
  val RedactedThinkingBlock =
    works.iterative.claude.core.model.RedactedThinkingBlock

  // Log model types
  type ConversationLogEntry =
    works.iterative.claude.core.log.model.ConversationLogEntry
  val ConversationLogEntry =
    works.iterative.claude.core.log.model.ConversationLogEntry
  type LogEntryPayload = works.iterative.claude.core.log.model.LogEntryPayload
  type UserLogEntry = works.iterative.claude.core.log.model.UserLogEntry
  val UserLogEntry = works.iterative.claude.core.log.model.UserLogEntry
  type AssistantLogEntry =
    works.iterative.claude.core.log.model.AssistantLogEntry
  val AssistantLogEntry =
    works.iterative.claude.core.log.model.AssistantLogEntry
  type SystemLogEntry = works.iterative.claude.core.log.model.SystemLogEntry
  val SystemLogEntry = works.iterative.claude.core.log.model.SystemLogEntry
  type ProgressLogEntry = works.iterative.claude.core.log.model.ProgressLogEntry
  val ProgressLogEntry = works.iterative.claude.core.log.model.ProgressLogEntry
  type QueueOperationLogEntry =
    works.iterative.claude.core.log.model.QueueOperationLogEntry
  val QueueOperationLogEntry =
    works.iterative.claude.core.log.model.QueueOperationLogEntry
  type FileHistorySnapshotLogEntry =
    works.iterative.claude.core.log.model.FileHistorySnapshotLogEntry
  val FileHistorySnapshotLogEntry =
    works.iterative.claude.core.log.model.FileHistorySnapshotLogEntry
  type LastPromptLogEntry =
    works.iterative.claude.core.log.model.LastPromptLogEntry
  val LastPromptLogEntry =
    works.iterative.claude.core.log.model.LastPromptLogEntry
  type RawLogEntry = works.iterative.claude.core.log.model.RawLogEntry
  val RawLogEntry = works.iterative.claude.core.log.model.RawLogEntry
  type TokenUsage = works.iterative.claude.core.log.model.TokenUsage
  val TokenUsage = works.iterative.claude.core.log.model.TokenUsage
  type LogFileMetadata = works.iterative.claude.core.log.model.LogFileMetadata
  val LogFileMetadata = works.iterative.claude.core.log.model.LogFileMetadata

  // Log service traits
  type ConversationLogIndex[F[_]] =
    works.iterative.claude.core.log.ConversationLogIndex[F]
  type ConversationLogReader[F[_]] =
    works.iterative.claude.core.log.ConversationLogReader[F]

  // Effectful log implementations
  type EffectfulConversationLogIndex =
    works.iterative.claude.effectful.log.EffectfulConversationLogIndex
  val EffectfulConversationLogIndex =
    works.iterative.claude.effectful.log.EffectfulConversationLogIndex
  type EffectfulConversationLogReader =
    works.iterative.claude.effectful.log.EffectfulConversationLogReader
  val EffectfulConversationLogReader =
    works.iterative.claude.effectful.log.EffectfulConversationLogReader

  // Utility
  type ProjectPathDecoder =
    works.iterative.claude.core.log.ProjectPathDecoder.type
  val ProjectPathDecoder = works.iterative.claude.core.log.ProjectPathDecoder
