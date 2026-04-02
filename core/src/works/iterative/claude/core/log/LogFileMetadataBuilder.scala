// PURPOSE: Builds LogFileMetadata from filesystem stat information and path decoding
// PURPOSE: Shared pure logic used by both direct and effectful ConversationLogIndex implementations

package works.iterative.claude.core.log

import java.time.Instant
import works.iterative.claude.core.log.model.LogFileMetadata

object LogFileMetadataBuilder:

  def fromStat(projectPath: os.Path, path: os.Path): LogFileMetadata =
    val stat = os.stat(path)
    val sessionId = path.last.stripSuffix(".jsonl")
    val cwd = ProjectPathDecoder.decode(projectPath.last)
    LogFileMetadata(
      path = path,
      sessionId = sessionId,
      summary = None,
      lastModified = Instant.ofEpochMilli(stat.mtime.toMillis),
      fileSize = stat.size,
      cwd = if cwd.isEmpty then None else Some(cwd),
      gitBranch = None,
      createdAt = None
    )
