package works.iterative.claude.core.log.model

// PURPOSE: Envelope type for a single entry in a Claude Code conversation log file
// PURPOSE: Carries common metadata shared across all log entry types

import java.time.Instant

case class ConversationLogEntry(
    uuid: String,
    parentUuid: Option[String],
    timestamp: Option[Instant],
    sessionId: String,
    isSidechain: Boolean,
    cwd: Option[String],
    version: Option[String],
    payload: LogEntryPayload
)
