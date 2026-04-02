// PURPOSE: Abstract contract for discovering and looking up Claude Code conversation log files
// PURPOSE: Parameterised by effect type F so both direct and effectful implementations can satisfy it

package works.iterative.claude.core.log

import works.iterative.claude.core.log.model.LogFileMetadata

trait ConversationLogIndex[F[_]]:
  def listSessions(projectPath: os.Path): F[Seq[LogFileMetadata]]
  def forSession(
      projectPath: os.Path,
      sessionId: String
  ): F[Option[LogFileMetadata]]
