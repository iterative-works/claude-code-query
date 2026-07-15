// PURPOSE: Package object for the ZIO API with convenient imports and type aliases
// PURPOSE: Enables single-import usage with all necessary model and error classes available

package works.iterative.claude

package object zio:
  // Type aliases for convenient usage
  type QueryOptions = works.iterative.claude.core.model.QueryOptions
  val QueryOptions = works.iterative.claude.core.model.QueryOptions
  type SessionOptions = works.iterative.claude.core.model.SessionOptions
  val SessionOptions = works.iterative.claude.core.model.SessionOptions

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
  type UnknownMessage = works.iterative.claude.core.model.UnknownMessage
  val UnknownMessage = works.iterative.claude.core.model.UnknownMessage
  type ControlResponse = works.iterative.claude.core.model.ControlResponse
  val ControlResponse = works.iterative.claude.core.model.ControlResponse
  type ControlRequest = works.iterative.claude.core.model.ControlRequest
  val ControlRequest = works.iterative.claude.core.model.ControlRequest
  type ControlRequestBody =
    works.iterative.claude.core.model.ControlRequestBody
  val ControlRequestBody = works.iterative.claude.core.model.ControlRequestBody
  type MessageId = works.iterative.claude.core.model.MessageId
  val MessageId = works.iterative.claude.core.model.MessageId
  type SessionId = works.iterative.claude.core.model.SessionId
  val SessionId = works.iterative.claude.core.model.SessionId
  type RequestId = works.iterative.claude.core.model.RequestId
  val RequestId = works.iterative.claude.core.model.RequestId
  type ResultOrigin = works.iterative.claude.core.model.ResultOrigin
  val ResultOrigin = works.iterative.claude.core.model.ResultOrigin

  // Session surface types
  type SessionState = works.iterative.claude.core.model.SessionState
  val SessionState = works.iterative.claude.core.model.SessionState
  type SessionEnd = works.iterative.claude.core.model.SessionEnd
  val SessionEnd = works.iterative.claude.core.model.SessionEnd
  type SessionInfo = works.iterative.claude.core.model.SessionInfo
  val SessionInfo = works.iterative.claude.core.model.SessionInfo
  type InterruptOutcome = works.iterative.claude.core.model.InterruptOutcome
  val InterruptOutcome = works.iterative.claude.core.model.InterruptOutcome

  // Structured user input
  type UserInput = works.iterative.claude.core.model.UserInput
  val UserInput = works.iterative.claude.core.model.UserInput
  type ContextItem = works.iterative.claude.core.model.ContextItem
  val ContextItem = works.iterative.claude.core.model.ContextItem
  type Channel = works.iterative.claude.core.model.Channel
  val Channel = works.iterative.claude.core.model.Channel

  // Archive custody configuration
  type ArchiveConfig = works.iterative.claude.core.log.ArchiveConfig
  val ArchiveConfig = works.iterative.claude.core.log.ArchiveConfig
  type PermissionDenial = works.iterative.claude.core.model.PermissionDenial
  val PermissionDenial = works.iterative.claude.core.model.PermissionDenial
  type ResultTimings = works.iterative.claude.core.model.ResultTimings
  val ResultTimings = works.iterative.claude.core.model.ResultTimings
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

  // Error types - the typed error channel of the ZIO API
  type CLIError = works.iterative.claude.core.CLIError
  type CLINotFoundError = works.iterative.claude.core.CLINotFoundError
  val CLINotFoundError = works.iterative.claude.core.CLINotFoundError
  type NodeJSNotFoundError = works.iterative.claude.core.NodeJSNotFoundError
  val NodeJSNotFoundError = works.iterative.claude.core.NodeJSNotFoundError
  type ProcessExecutionError = works.iterative.claude.core.ProcessExecutionError
  val ProcessExecutionError = works.iterative.claude.core.ProcessExecutionError
  type ProcessTimeoutError = works.iterative.claude.core.ProcessTimeoutError
  val ProcessTimeoutError = works.iterative.claude.core.ProcessTimeoutError
  type ConfigurationError = works.iterative.claude.core.ConfigurationError
  val ConfigurationError = works.iterative.claude.core.ConfigurationError
  type JsonParsingError = works.iterative.claude.core.JsonParsingError
  val JsonParsingError = works.iterative.claude.core.JsonParsingError
  type EnvironmentValidationError =
    works.iterative.claude.core.EnvironmentValidationError
  val EnvironmentValidationError =
    works.iterative.claude.core.EnvironmentValidationError
  type SessionProcessDied = works.iterative.claude.core.SessionProcessDied
  val SessionProcessDied = works.iterative.claude.core.SessionProcessDied
  type SessionClosedError = works.iterative.claude.core.SessionClosedError
  val SessionClosedError = works.iterative.claude.core.SessionClosedError

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
  type TokenUsage = works.iterative.claude.core.model.TokenUsage
  val TokenUsage = works.iterative.claude.core.model.TokenUsage
  type LogFileMetadata = works.iterative.claude.core.log.model.LogFileMetadata
  val LogFileMetadata = works.iterative.claude.core.log.model.LogFileMetadata
  type SubAgentMetadata = works.iterative.claude.core.log.model.SubAgentMetadata
  val SubAgentMetadata = works.iterative.claude.core.log.model.SubAgentMetadata

  // Log service traits
  type ConversationLogIndex[F[_]] =
    works.iterative.claude.core.log.ConversationLogIndex[F]
  type ConversationLogReader[F[_]] =
    works.iterative.claude.core.log.ConversationLogReader[F]

  // ZIO log implementations
  type ZioConversationLogIndex =
    works.iterative.claude.zio.log.ZioConversationLogIndex
  val ZioConversationLogIndex =
    works.iterative.claude.zio.log.ZioConversationLogIndex
  type ZioConversationLogReader =
    works.iterative.claude.zio.log.ZioConversationLogReader
  val ZioConversationLogReader =
    works.iterative.claude.zio.log.ZioConversationLogReader

  // Utility
  type ProjectPathDecoder =
    works.iterative.claude.core.log.ProjectPathDecoder.type
  val ProjectPathDecoder = works.iterative.claude.core.log.ProjectPathDecoder
