// PURPOSE: Main API for Claude Code SDK enabling conversational AI interactions
// PURPOSE: Provides streaming query interface to Claude Code CLI functionality
package works.iterative.claude

import cats.effect.IO
import fs2.Stream
import fs2.io.process.{Process, ProcessBuilder}
import fs2.io.file.Path
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import works.iterative.claude.model.*
import works.iterative.claude.QueryOptions
import works.iterative.claude.internal.parsing.JsonParser
import works.iterative.claude.internal.cli.{
  CLIDiscovery,
  ProcessExecutionError,
  JsonParsingError,
  ProcessTimeoutError,
  ConfigurationError,
  ProcessManager
}
import works.iterative.claude.internal.cli.CLIArgumentBuilder

object ClaudeCode:
  // Public API - High-level "What" operations
  def queryResult(options: QueryOptions)(using logger: Logger[IO]): IO[String] =
    querySync(options)(using logger).map(extractTextFromMessages)

  def querySync(options: QueryOptions)(using
      logger: Logger[IO]
  ): IO[List[Message]] =
    query(options)(using logger).compile.toList

  def query(
      options: QueryOptions
  )(using logger: Logger[IO]): Stream[IO, Message] =
    for {
      _ <- logQueryStart(options.prompt)
      executablePath <- discoverExecutablePath(options)
      args <- Stream.eval(IO.pure(buildCLIArguments(options)))
      _ <- validateConfigurationOrFail(options)
      messages <- executeClaudeProcess(executablePath, args, options)
    } yield messages

  // Mid-level operations - "How" we accomplish the high-level goals
  private def extractTextFromMessages(messages: List[Message]): String =
    messages
      .collectFirst:
        case AssistantMessage(content) =>
          content
            .collectFirst:
              case TextBlock(text) => text
            .getOrElse("")
      .getOrElse("")

  private def logQueryStart(prompt: String)(using
      logger: Logger[IO]
  ): Stream[IO, Unit] =
    Stream.eval(logger.info(s"Initiating query with prompt: $prompt"))

  private def discoverExecutablePath(options: QueryOptions)(using
      logger: Logger[IO]
  ): Stream[IO, String] =
    options.pathToClaudeCodeExecutable match
      case Some(explicitPath) => Stream.eval(IO.pure(explicitPath))
      case None               =>
        Stream
          .eval(CLIDiscovery.findClaude(logger))
          .flatMap:
            case Right(path) => Stream.emit(path)
            case Left(error) => Stream.raiseError[IO](error)

  private def buildCLIArguments(options: QueryOptions): List[String] =
    List("--print", "--verbose", "--output-format", "stream-json") ++
      CLIArgumentBuilder.buildArgs(options) ++
      List(options.prompt)

  private def validateConfigurationOrFail(options: QueryOptions)(using
      logger: Logger[IO]
  ): Stream[IO, Unit] =
    validateConfiguration(options) match
      case Some(error) =>
        Stream
          .eval(
            logger.warn(s"Configuration validation failed: ${error.reason}")
          )
          .flatMap(_ => Stream.raiseError[IO](error))
      case None => Stream.emit(())

  private def executeClaudeProcess(
      executablePath: String,
      args: List[String],
      options: QueryOptions
  )(using logger: Logger[IO]): Stream[IO, Message] =
    val processBuilder = ProcessManager.default.configureProcessBuilder(
      executablePath,
      args,
      options
    )
    ProcessManager.default
      .executeProcess(processBuilder, options, executablePath, args)(using
        logger
      )
      .onFinalize(logger.info("Query completed"))

  // Low-level validation and utility operations
  private def validateConfiguration(
      options: QueryOptions
  ): Option[ConfigurationError] =
    validateWorkingDirectory(options.cwd)

  private def validateWorkingDirectory(
      cwd: Option[String]
  ): Option[ConfigurationError] =
    cwd.flatMap: workingDir =>
      val path = java.nio.file.Paths.get(workingDir)
      if !java.nio.file.Files.exists(path) then
        Some(
          ConfigurationError(
            "cwd",
            workingDir,
            "Working directory does not exist"
          )
        )
      else if !java.nio.file.Files.isDirectory(path) then
        Some(
          ConfigurationError(
            "cwd",
            workingDir,
            "Path exists but is not a directory"
          )
        )
      else None

  // Error context logging methods
  def logConfigurationError(
      logger: Logger[IO],
      options: QueryOptions
  ): IO[Unit] =
    validateConfiguration(options) match
      case Some(configError) =>
        logger.error(s"Configuration validation failed: ${configError.message}")
      case None => IO.unit

  def logProcessExecutionError(
      logger: Logger[IO],
      error: ProcessExecutionError
  ): IO[Unit] =
    logger.error(
      s"Process execution failed with exit code ${error.exitCode}: ${error.stderr}. Command: ${error.command.mkString(" ")}"
    )

  def logJsonParsingError(
      logger: Logger[IO],
      error: JsonParsingError
  ): IO[Unit] =
    logger.error(
      s"JSON parsing failed at line ${error.lineNumber}: ${error.cause.getMessage}. Content: ${error.line}"
    )
