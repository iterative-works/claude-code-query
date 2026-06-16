// PURPOSE: Task-based ConversationLogIndex that discovers and looks up .jsonl session files
// PURPOSE: All filesystem access is deferred and composable within ZIO programs

package works.iterative.claude.zio.log

import zio.*
import works.iterative.claude.core.log.ConversationLogIndex
import works.iterative.claude.core.log.ClaudeProjects
import works.iterative.claude.core.log.LogFileMetadataBuilder
import works.iterative.claude.core.log.model.LogFileMetadata
import works.iterative.claude.core.log.model.SubAgentMetadata
import works.iterative.claude.core.log.parsing.SubAgentMetadataParser

class ZioConversationLogIndex private (
    configDirOverride: Option[os.Path],
    home: os.Path
) extends ConversationLogIndex[Task]:

  def listSessions(projectPath: os.Path): Task[Seq[LogFileMetadata]] =
    ZIO.attemptBlocking:
      if !os.exists(projectPath) then Seq.empty
      else
        os.list(projectPath)
          .filter(_.last.endsWith(".jsonl"))
          .map(path => LogFileMetadataBuilder.fromStat(projectPath, path))
          .toSeq

  def forSession(
      projectPath: os.Path,
      sessionId: String
  ): Task[Option[LogFileMetadata]] =
    ZIO.attemptBlocking:
      val candidate = projectPath / s"$sessionId.jsonl"
      if os.exists(candidate) && os.isFile(candidate) then
        Some(LogFileMetadataBuilder.fromStat(projectPath, candidate))
      else None

  def listSubAgents(
      projectPath: os.Path,
      sessionId: String
  ): Task[Seq[SubAgentMetadata]] =
    ZIO.attemptBlocking:
      val subagentsDir = projectPath / sessionId / "subagents"
      if !(os.exists(subagentsDir) && os.isDir(subagentsDir)) then Seq.empty
      else
        os.list(subagentsDir)
          .filter: path =>
            val name = path.last
            name.startsWith("agent-") && name.endsWith(".jsonl")
          .flatMap: jsonlPath =>
            val metaPath =
              jsonlPath / os.up / s"${jsonlPath.last.stripSuffix(".jsonl")}.meta.json"
            if !os.exists(metaPath) then None
            else
              io.circe.parser
                .parse(os.read(metaPath))
                .toOption
                .flatMap(SubAgentMetadataParser.parse(_, jsonlPath))
          .toSeq

  /** Lists all sub-agents for a session in the given working directory. */
  def listSubAgentsFor(
      cwd: os.Path,
      sessionId: String
  ): Task[Seq[SubAgentMetadata]] =
    listSubAgents(
      ClaudeProjects.projectDirFor(cwd, configDirOverride, home),
      sessionId
    )

  /** Lists all sessions for the given working directory, resolving the project
    * directory via `CLAUDE_CONFIG_DIR` semantics.
    */
  def listSessionsFor(cwd: os.Path): Task[Seq[LogFileMetadata]] =
    listSessions(ClaudeProjects.projectDirFor(cwd, configDirOverride, home))

  /** Looks up a specific session for the given working directory. */
  def forSessionAt(
      cwd: os.Path,
      sessionId: String
  ): Task[Option[LogFileMetadata]] =
    forSession(
      ClaudeProjects.projectDirFor(cwd, configDirOverride, home),
      sessionId
    )

object ZioConversationLogIndex:

  /** Creates a `ZioConversationLogIndex` from the current environment, deferred
    * in a Task. `CLAUDE_CONFIG_DIR` is read once at construction (empty string
    * treated as unset); otherwise `os.home / ".claude"` is used.
    */
  def apply(): Task[ZioConversationLogIndex] =
    ZIO.attempt:
      new ZioConversationLogIndex(
        ClaudeProjects.resolveConfigDir(sys.env.get),
        os.home
      )

  /** Test seam: creates an index with injected config synchronously. */
  def make(
      configDirOverride: Option[os.Path],
      home: os.Path
  ): ZioConversationLogIndex =
    new ZioConversationLogIndex(configDirOverride, home)
