package works.iterative.claude.core.log.model

// PURPOSE: Envelope type for a single entry in a Claude Code conversation log file
// PURPOSE: Carries common metadata shared across all log entry types

import works.iterative.core.Moment

case class ConversationLogEntry(
    uuid: Option[String],
    parentUuid: Option[String],
    timestamp: Option[Moment],
    sessionId: String,
    isSidechain: Boolean,
    cwd: Option[String],
    version: Option[String],
    payload: LogEntryPayload,
    agentId: Option[String] = None
)
