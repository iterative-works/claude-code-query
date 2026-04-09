// PURPOSE: IO-based ConversationLogIndex that discovers and looks up .jsonl session files
// PURPOSE: All operations are deferred and composable within IO programs

package works.iterative.claude.effectful.log

import cats.effect.IO
import fs2.io.file.{Files, Path => Fs2Path}
import works.iterative.claude.core.log.ConversationLogIndex
import works.iterative.claude.core.log.ClaudeProjects
import works.iterative.claude.core.log.LogFileMetadataBuilder
import works.iterative.claude.core.log.model.LogFileMetadata
import works.iterative.claude.core.log.model.SubAgentMetadata
import works.iterative.claude.core.log.parsing.SubAgentMetadataParser

class EffectfulConversationLogIndex private (
    configDirOverride: Option[os.Path],
    home: os.Path
) extends ConversationLogIndex[IO]:

  def listSessions(projectPath: os.Path): IO[Seq[LogFileMetadata]] =
    IO(os.exists(projectPath)).flatMap:
      case false => IO.pure(Seq.empty)
      case true  =>
        val dir = Fs2Path.fromNioPath(projectPath.toNIO)
        Files[IO]
          .list(dir)
          .filter(p => p.fileName.toString.endsWith(".jsonl"))
          .evalMap(p =>
            IO(
              LogFileMetadataBuilder.fromStat(projectPath, os.Path(p.toNioPath))
            )
          )
          .compile
          .toList
          .map(_.toSeq)

  def forSession(
      projectPath: os.Path,
      sessionId: String
  ): IO[Option[LogFileMetadata]] =
    val candidate = projectPath / s"$sessionId.jsonl"
    IO(os.exists(candidate) && os.isFile(candidate)).flatMap:
      case true =>
        IO(LogFileMetadataBuilder.fromStat(projectPath, candidate)).map(Some(_))
      case false => IO.pure(None)

  def listSubAgents(
      projectPath: os.Path,
      sessionId: String
  ): IO[Seq[SubAgentMetadata]] =
    val subagentsDir = projectPath / sessionId / "subagents"
    IO(os.exists(subagentsDir) && os.isDir(subagentsDir)).flatMap:
      case false => IO.pure(Seq.empty)
      case true  =>
        val dir = Fs2Path.fromNioPath(subagentsDir.toNIO)
        Files[IO]
          .list(dir)
          .filter(p =>
            val name = p.fileName.toString
            name.startsWith("agent-") && name.endsWith(".jsonl")
          )
          .evalMap: p =>
            val jsonlPath = os.Path(p.toNioPath)
            val metaPath =
              jsonlPath / os.up / s"${jsonlPath.last.stripSuffix(".jsonl")}.meta.json"
            IO(os.exists(metaPath)).flatMap:
              case false => IO.pure(None)
              case true  =>
                IO(os.read(metaPath))
                  .map(content =>
                    io.circe.parser
                      .parse(content)
                      .toOption
                      .flatMap(SubAgentMetadataParser.parse(_, jsonlPath))
                  )
          .compile
          .toList
          .map(_.flatten.toSeq)

  /** Lists all sub-agents for a session in the given working directory.
    *
    * Resolves the project directory via `CLAUDE_CONFIG_DIR` semantics (same as
    * `listSessionsFor`) and delegates to `listSubAgents`.
    *
    * @param cwd
    *   the working directory
    * @param sessionId
    *   the session identifier
    */
  def listSubAgentsFor(
      cwd: os.Path,
      sessionId: String
  ): IO[Seq[SubAgentMetadata]] =
    listSubAgents(
      ClaudeProjects.projectDirFor(cwd, configDirOverride, home),
      sessionId
    )

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
  def listSessionsFor(cwd: os.Path): IO[Seq[LogFileMetadata]] =
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
  def forSessionAt(
      cwd: os.Path,
      sessionId: String
  ): IO[Option[LogFileMetadata]] =
    forSession(
      ClaudeProjects.projectDirFor(cwd, configDirOverride, home),
      sessionId
    )

object EffectfulConversationLogIndex:

  /** Creates an `EffectfulConversationLogIndex` using the current environment,
    * deferred in IO.
    *
    * Reads `CLAUDE_CONFIG_DIR` from the environment inside `IO` at construction
    * time. An empty string is treated as unset. No shell `~` expansion is
    * performed on the value. Falls back to `os.home / ".claude"` when the
    * variable is absent or empty. The environment is captured once per
    * instance, not re-read on each method call.
    */
  def apply(): IO[EffectfulConversationLogIndex] =
    IO:
      new EffectfulConversationLogIndex(
        ClaudeProjects.resolveConfigDir(sys.env.get),
        os.home
      )

  /** Test seam: creates an `EffectfulConversationLogIndex` with injected config
    * synchronously.
    *
    * Intended for use in tests where direct construction without `IO` is
    * required.
    *
    * @param configDirOverride
    *   pre-resolved `CLAUDE_CONFIG_DIR` value, `None` if unset
    * @param home
    *   the home directory to use when `configDirOverride` is absent
    */
  def make(
      configDirOverride: Option[os.Path],
      home: os.Path
  ): EffectfulConversationLogIndex =
    new EffectfulConversationLogIndex(configDirOverride, home)
