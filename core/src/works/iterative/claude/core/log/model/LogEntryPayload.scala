package works.iterative.claude.core.log.model

// PURPOSE: Sealed type hierarchy for conversation log entry payloads
// PURPOSE: Represents all known entry types in Claude Code conversation log files

import works.iterative.claude.core.model.ContentBlock
import io.circe.Json

sealed trait LogEntryPayload

case class UserLogEntry(
    content: List[ContentBlock]
) extends LogEntryPayload

case class AssistantLogEntry(
    content: List[ContentBlock],
    model: Option[String],
    usage: Option[TokenUsage],
    requestId: Option[String]
) extends LogEntryPayload

case class SystemLogEntry(
    subtype: String,
    data: Map[String, Any]
) extends LogEntryPayload

case class ProgressLogEntry(
    data: Map[String, Any],
    parentToolUseId: Option[String]
) extends LogEntryPayload

case class QueueOperationLogEntry(
    operation: String,
    content: Option[String]
) extends LogEntryPayload

case class FileHistorySnapshotLogEntry(
    data: Map[String, Any]
) extends LogEntryPayload

case class LastPromptLogEntry(
    data: Map[String, Any]
) extends LogEntryPayload

case class RawLogEntry(
    entryType: String,
    json: Json
) extends LogEntryPayload
