// PURPOSE: Main API for the Claude Code SDK enabling conversational AI interactions
// PURPOSE: Provides a ZIO streaming query interface and scoped sessions over the CLI

package works.iterative.claude.zio

import zio.*
import zio.stream.ZStream
import works.iterative.claude.core.cli.CLIArgumentBuilder
import works.iterative.claude.core.{CLIError, ConfigurationError}
import works.iterative.claude.core.model.*
import works.iterative.claude.zio.internal.cli.{
  CLIDiscovery,
  ProcessManager,
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
        _ <- validateConfiguration(options)
        executablePath <- resolveExecutable(options.pathToClaudeCodeExecutable)
        args = buildCLIArguments(options)
      yield ProcessManager.executeProcess(executablePath, args, options)

  /** Execute a query and collect all messages into a list. */
  def querySync(options: QueryOptions): IO[CLIError, List[Message]] =
    query(options).runCollect.map(_.toList)

  /** Execute a query and extract the assistant's text result. */
  def queryResult(options: QueryOptions): IO[CLIError, String] =
    querySync(options).map(extractTextFromMessages)

  /** Open a scoped multi-turn session backed by a long-lived CLI process. */
  def session(options: SessionOptions): ZIO[Scope, CLIError, Session] =
    resolveExecutable(options.pathToClaudeCodeExecutable)
      .flatMap(SessionProcess.start(_, options))

  // Mid-level operations - "How" we accomplish the high-level goals

  private[claude] def extractTextFromMessages(messages: List[Message]): String =
    messages
      .collectFirst { case AssistantMessage(content) =>
        content
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
          .orElseSucceed((false, false))
          .flatMap: (exists, isDirectory) =>
            if !exists then
              ZIO.fail(
                ConfigurationError(
                  "cwd",
                  workingDir,
                  "Working directory does not exist"
                )
              )
            else if !isDirectory then
              ZIO.fail(
                ConfigurationError(
                  "cwd",
                  workingDir,
                  "Path exists but is not a directory"
                )
              )
            else ZIO.unit
