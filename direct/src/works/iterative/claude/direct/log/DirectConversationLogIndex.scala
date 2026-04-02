// PURPOSE: Synchronous ConversationLogIndex that discovers and looks up .jsonl session files
// PURPOSE: Returns plain values; no effect type required by callers

package works.iterative.claude.direct.log

import works.iterative.claude.core.log.ConversationLogIndex
import works.iterative.claude.core.log.LogFileMetadataBuilder
import works.iterative.claude.core.log.model.LogFileMetadata

class DirectConversationLogIndex extends ConversationLogIndex[[A] =>> A]:

  def listSessions(projectPath: os.Path): Seq[LogFileMetadata] =
    if !os.exists(projectPath) then Seq.empty
    else
      os.list(projectPath)
        .filter(p => os.isFile(p) && p.last.endsWith(".jsonl"))
        .map(LogFileMetadataBuilder.fromStat(projectPath, _))

  def forSession(
      projectPath: os.Path,
      sessionId: String
  ): Option[LogFileMetadata] =
    val candidate = projectPath / s"$sessionId.jsonl"
    if os.exists(candidate) && os.isFile(candidate) then
      Some(LogFileMetadataBuilder.fromStat(projectPath, candidate))
    else None

object DirectConversationLogIndex:
  def apply(): DirectConversationLogIndex = new DirectConversationLogIndex()
