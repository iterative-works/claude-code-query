// PURPOSE: Main API for the Claude Code SDK enabling conversational AI interactions
// PURPOSE: Provides a ZIO streaming query interface and scoped sessions over the CLI

package works.iterative.claude.zio

import zio.*
import zio.stream.ZStream
import works.iterative.claude.core.cli.CLIArgumentBuilder
import works.iterative.claude.core.{CLIError, ConfigurationError}
import works.iterative.claude.core.log.ArchiveConfig
import works.iterative.claude.core.model.*
import works.iterative.claude.zio.internal.cli.{
  CLIDiscovery,
  ProcessManager,
  SessionArchiveHook,
  SessionProcess
}

/** Entry point for querying the Claude Code CLI with ZIO.
  *
  * `query` streams messages as the CLI produces them; `querySync` and
  * `queryResult` are convenience collectors. `session` opens a scoped
  * multi-turn conversation. All operations fail with a typed [[CLIError]].
  */
object ClaudeCode:

  // Public API - High-level "What" operations

  /** Ask Claude a question with sane defaults, returning the assistant's text.
    */
  def ask(prompt: String): IO[CLIError, String] =
    queryResult(QueryOptions.simple(prompt))

  /** Execute a query and stream messages from the Claude CLI as they arrive. */
  def query(options: QueryOptions): ZStream[Any, CLIError, Message] =
    ZStream.unwrap:
      for
        _ <- ZIO.logInfo(s"Initiating query with prompt: ${options.prompt}")
        executablePath <- resolveExecutable(options.pathToClaudeCodeExecutable)
        args = buildCLIArguments(options)
        _ <- validateConfiguration(options)
      yield ProcessManager
        .executeProcess(executablePath, args, options)
        .ensuring(ZIO.logInfo("Query completed"))

  /** Execute a query and collect all messages into a list. */
  def querySync(options: QueryOptions): IO[CLIError, List[Message]] =
    query(options).runCollect.map(_.toList)

  /** Execute a query and extract the assistant's text result. */
  def queryResult(options: QueryOptions): IO[CLIError, String] =
    querySync(options).map(extractTextFromMessages)

  /** Open a scoped session backed by a long-lived CLI process.
    *
    * When `archive` is set, the session mirrors the vendor transcript tree
    * after each real result and on close — best-effort, so a mirror failure is
    * logged and never breaks the session. `eventsBufferSize` sizes the lossy
    * `events` Hub (see [[Session.events]]); the default is conservative.
    */
  def session(
      options: SessionOptions,
      archive: Option[ArchiveConfig] = None,
      eventsBufferSize: Int = SessionProcess.DefaultEventsBufferSize
  ): ZIO[Scope, CLIError, Session] =
    for
      executablePath <- resolveExecutable(options.pathToClaudeCodeExecutable)
      hook <- archiveHook(archive)
      session <- SessionProcess.start(
        executablePath,
        options,
        hook,
        eventsBufferSize
      )
    yield session

  private def archiveHook(
      archive: Option[ArchiveConfig]
  ): UIO[SessionArchiveHook] =
    archive.fold(ZIO.succeed(SessionArchiveHook.none))(
      SessionArchiveHook.mirroring
    )

  // Mid-level operations - "How" we accomplish the high-level goals

  private[claude] def extractTextFromMessages(messages: List[Message]): String =
    messages
      .collectFirst { case assistant: AssistantMessage =>
        assistant.content
          .collectFirst { case TextBlock(text) => text }
          .getOrElse("")
      }
      .getOrElse("")

  private def resolveExecutable(path: Option[String]): IO[CLIError, String] =
    path match
      case Some(p) => ZIO.succeed(p)
      case None    => CLIDiscovery.findClaude

  private[claude] def buildCLIArguments(options: QueryOptions): List[String] =
    List("--print", "--verbose", "--output-format", "stream-json") ++
      CLIArgumentBuilder.buildArgs(options) ++
      List("--", options.prompt)

  // Low-level validation and utility operations

  private def validateConfiguration(
      options: QueryOptions
  ): IO[ConfigurationError, Unit] =
    options.cwd match
      case None             => ZIO.unit
      case Some(workingDir) =>
        ZIO
          .attemptBlocking:
            val path = java.nio.file.Paths.get(workingDir)
            (
              java.nio.file.Files.exists(path),
              java.nio.file.Files.isDirectory(path)
            )
          .mapError: cause =>
            ConfigurationError(
              "cwd",
              workingDir,
              s"Cannot access working directory: ${cause.getMessage}"
            )
          .flatMap: (exists, isDirectory) =>
            cwdError(workingDir, exists, isDirectory) match
              case Some(error) =>
                ZIO.logWarning(
                  s"Configuration validation failed: ${error.reason}"
                ) *> ZIO.fail(error)
              case None => ZIO.unit

  /** Classifies a working directory from its probed existence and
    * directory-ness, returning the configuration error (if any). Pure so the
    * decision can be tested without touching the filesystem.
    */
  private[claude] def cwdError(
      workingDir: String,
      exists: Boolean,
      isDirectory: Boolean
  ): Option[ConfigurationError] =
    if !exists then
      Some(
        ConfigurationError(
          "cwd",
          workingDir,
          "Working directory does not exist"
        )
      )
    else if !isDirectory then
      Some(
        ConfigurationError(
          "cwd",
          workingDir,
          "Path exists but is not a directory"
        )
      )
    else None
