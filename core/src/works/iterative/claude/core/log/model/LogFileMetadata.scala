package works.iterative.claude.core.log.model

// PURPOSE: Metadata about a Claude Code conversation log file for index and discovery
// PURPOSE: Provides session identification and file statistics without loading the full log

import java.time.Instant

case class LogFileMetadata(
    path: os.Path,
    sessionId: String,
    summary: Option[String],
    lastModified: Instant,
    fileSize: Long,
    cwd: Option[String],
    gitBranch: Option[String],
    createdAt: Option[Instant]
)
