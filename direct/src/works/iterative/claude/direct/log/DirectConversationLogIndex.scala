// PURPOSE: Synchronous ConversationLogIndex that discovers and looks up .jsonl session files
// PURPOSE: Returns plain values; no effect type required by callers

package works.iterative.claude.direct.log

import works.iterative.claude.core.log.ConversationLogIndex
import works.iterative.claude.core.log.LogFileMetadataBuilder
import works.iterative.claude.core.log.ClaudeProjects
import works.iterative.claude.core.log.model.LogFileMetadata

class DirectConversationLogIndex private (
    configDirOverride: Option[os.Path],
    home: os.Path
) extends ConversationLogIndex[[A] =>> A]:

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

  /** Lists all sessions for the given working directory.
    *
    * Resolves the project directory via `CLAUDE_CONFIG_DIR` semantics: if the
    * override is set (non-empty), it replaces `~/.claude`; otherwise
    * `home / ".claude"` is used. The `cwd` path is encoded with
    * [[works.iterative.claude.core.log.ProjectPathEncoder]] to form the project
    * subdirectory name.
    *
    * @param cwd
    *   the working directory whose sessions to list
    */
  def listSessionsFor(cwd: os.Path): Seq[LogFileMetadata] =
    listSessions(ClaudeProjects.projectDirFor(cwd, configDirOverride, home))

  /** Looks up a specific session for the given working directory.
    *
    * Resolves the project directory via `CLAUDE_CONFIG_DIR` semantics: if the
    * override is set (non-empty), it replaces `~/.claude`; otherwise
    * `home / ".claude"` is used.
    *
    * @param cwd
    *   the working directory whose sessions to search
    * @param sessionId
    *   the session identifier (filename without the `.jsonl` extension)
    */
  def forSessionAt(cwd: os.Path, sessionId: String): Option[LogFileMetadata] =
    forSession(
      ClaudeProjects.projectDirFor(cwd, configDirOverride, home),
      sessionId
    )

object DirectConversationLogIndex:

  /** Creates a `DirectConversationLogIndex` using the current environment.
    *
    * Reads `CLAUDE_CONFIG_DIR` from the environment at construction time
    * (eagerly, not deferred). An empty string is treated as unset. No shell `~`
    * expansion is performed on the value. Falls back to `os.home / ".claude"`
    * when the variable is absent or empty.
    *
    * Tests that need to mutate the environment after construction should use
    * the two-arg `apply(configDirOverride, home)` seam instead.
    */
  def apply(): DirectConversationLogIndex =
    new DirectConversationLogIndex(
      ClaudeProjects.resolveConfigDir(sys.env.get),
      os.home
    )

  /** Test seam: creates a `DirectConversationLogIndex` with injected config.
    *
    * @param configDirOverride
    *   pre-resolved `CLAUDE_CONFIG_DIR` value, `None` if unset
    * @param home
    *   the home directory to use when `configDirOverride` is absent
    */
  def apply(
      configDirOverride: Option[os.Path],
      home: os.Path
  ): DirectConversationLogIndex =
    new DirectConversationLogIndex(configDirOverride, home)
