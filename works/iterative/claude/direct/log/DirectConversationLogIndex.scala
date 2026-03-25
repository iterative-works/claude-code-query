// PURPOSE: Direct-style implementation of ConversationLogIndex using os-lib for file discovery
// PURPOSE: Lists and looks up .jsonl session files under a project directory without effect wrappers

package works.iterative.claude.direct.log

import java.time.Instant
import works.iterative.claude.core.log.ConversationLogIndex
import works.iterative.claude.core.log.ProjectPathDecoder
import works.iterative.claude.core.log.model.LogFileMetadata

class DirectConversationLogIndex extends ConversationLogIndex[[A] =>> A]:

  def listSessions(projectPath: os.Path): Seq[LogFileMetadata] =
    if !os.exists(projectPath) then Seq.empty
    else
      os.list(projectPath)
        .filter(p => os.isFile(p) && p.last.endsWith(".jsonl"))
        .map(metadataFor(projectPath, _))

  def forSession(
      projectPath: os.Path,
      sessionId: String
  ): Option[LogFileMetadata] =
    val candidate = projectPath / s"$sessionId.jsonl"
    if os.exists(candidate) && os.isFile(candidate) then
      Some(metadataFor(projectPath, candidate))
    else None

  private def metadataFor(
      projectPath: os.Path,
      path: os.Path
  ): LogFileMetadata =
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

object DirectConversationLogIndex:
  def apply(): DirectConversationLogIndex = new DirectConversationLogIndex()
