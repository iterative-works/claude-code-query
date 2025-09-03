// PURPOSE: Manages subprocess execution and configuration for Claude Code CLI
// PURPOSE: Separates process management logic from ClaudeCode for better testability

package works.iterative.claude.effectful.internal.cli

import cats.effect.IO
import fs2.Stream
import fs2.io.process.ProcessBuilder
import fs2.io.file.Path
import works.iterative.claude.core.model.QueryOptions
import works.iterative.claude.core.model.{
  Message,
  AssistantMessage,
  ResultMessage
}
import works.iterative.claude.effectful.internal.parsing.JsonParser
import works.iterative.claude.core.{
  ProcessExecutionError,
  ProcessTimeoutError,
  EnvironmentValidationError
}
import org.typelevel.log4cats.Logger

/** Manages process configuration and execution for Claude Code CLI.
  *
  * Provides clean separation between process configuration and execution,
  * enabling better testing by allowing ProcessBuilder configuration to be
  * tested independently from actual process execution.
  */
trait ProcessManager:
  /** Configure ProcessBuilder from QueryOptions.
    *
    * Maps QueryOptions fields to ProcessBuilder configuration including working
    * directory, environment variables, and other process settings.
    *
    * @param executablePath
    *   Path to the Claude Code CLI executable
    * @param args
    *   Command line arguments for the executable
    * @param options
    *   Query configuration options
    * @return
    *   Configured ProcessBuilder ready for execution
    */
  def configureProcessBuilder(
      executablePath: String,
      args: List[String],
      options: QueryOptions
  ): ProcessBuilder

  /** Execute configured process and return message stream.
    *
    * Takes a configured ProcessBuilder and executes it, parsing the output into
    * a stream of Message objects.
    *
    * @param processBuilder
    *   Configured ProcessBuilder to execute
    * @param options
    *   Query options for timeout and other execution settings
    * @param executablePath
    *   Original executable path for error reporting
    * @param args
    *   Original command arguments for error reporting
    * @param logger
    *   Logger instance for logging process execution events
    * @return
    *   Stream of parsed messages from the CLI process
    */
  def executeProcess(
      processBuilder: ProcessBuilder,
      options: QueryOptions,
      executablePath: String,
      args: List[String]
  )(using logger: Logger[IO]): Stream[IO, Message]

object ProcessManager:
  /** Default ProcessManager implementation. */
  val default: ProcessManager = new ProcessManagerImpl

/** Default implementation of ProcessManager. */
private class ProcessManagerImpl extends ProcessManager:
  def configureProcessBuilder(
      executablePath: String,
      args: List[String],
      options: QueryOptions
  ): ProcessBuilder =
    // Validate environment variables before creating ProcessBuilder
    options.environmentVariables.foreach(validateEnvironmentVariables)
    createBaseProcessBuilder(executablePath, args, options)

  def executeProcess(
      processBuilder: ProcessBuilder,
      options: QueryOptions,
      executablePath: String,
      args: List[String]
  )(using logger: Logger[IO]): Stream[IO, Message] =
    Stream
      .eval(logProcessStart(executablePath, args))
      .flatMap(_ =>
        runProcessAndParseOutput(processBuilder, options, executablePath, args)
      )

  private def createBaseProcessBuilder(
      executablePath: String,
      args: List[String],
      options: QueryOptions
  ): ProcessBuilder =
    val builderWithInheritance = ProcessBuilder(executablePath, args)
      .withInheritEnv(options.inheritEnvironment.getOrElse(true))

    val builderWithEnv =
      options.environmentVariables.fold(builderWithInheritance)(envVars =>
        builderWithInheritance.withExtraEnv(envVars)
      )

    options.cwd.fold(builderWithEnv)(cwd =>
      builderWithEnv.withWorkingDirectory(Path(cwd))
    )

  private def logProcessStart(executablePath: String, args: List[String])(using
      logger: Logger[IO]
  ): IO[Unit] =
    logger.info(s"Starting process: $executablePath ${args.mkString(" ")}")

  private def runProcessAndParseOutput(
      processBuilder: ProcessBuilder,
      options: QueryOptions,
      executablePath: String,
      args: List[String]
  )(using logger: Logger[IO]): Stream[IO, Message] =
    Stream
      .resource(processBuilder.spawn[IO])
      .flatMap(process =>
        executeProcessWithStreams(process, options, executablePath, args)
      )

  private def executeProcessWithStreams(
      process: fs2.io.process.Process[IO],
      options: QueryOptions,
      executablePath: String,
      args: List[String]
  )(using logger: Logger[IO]): Stream[IO, Message] =
    Stream.eval(captureStderr(process)).flatMap { stderrFiber =>
      val stdoutStream = parseStdoutMessagesStream(process)

      val streamWithErrorCheck = stdoutStream.onFinalize {
        for {
          exitCode <- process.exitValue
          _ <- logProcessCompletion(exitCode)
          stderrContent <- stderrFiber.joinWithNever
          _ <- handleProcessFailure(
            exitCode,
            stderrContent,
            executablePath,
            args
          )
        } yield ()
      }

      applyTimeoutIfSpecifiedStream(
        streamWithErrorCheck,
        options,
        executablePath,
        args
      )
    }

  private def captureStderr(
      process: fs2.io.process.Process[IO]
  ): IO[cats.effect.Fiber[IO, Throwable, String]] =
    process.stderr
      .through(fs2.text.utf8.decode)
      .compile
      .string
      .start

  private def parseStdoutMessagesStream(process: fs2.io.process.Process[IO])(
      using logger: Logger[IO]
  ): Stream[IO, Message] =
    val closeStdin = Stream.empty.through(process.stdin).compile.drain

    val stdout = process.stdout
      .through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
      .zipWithIndex
      .evalMap(parseJsonLine)
      .evalMap(handleParseResult)
      .unNone

    stdout
      .concurrently(Stream.eval(closeStdin))

  private def parseJsonLine(lineWithIndex: (String, Long))(using
      logger: Logger[IO]
  ): IO[Either[Throwable, Option[Message]]] =
    val (line, index) = lineWithIndex
    JsonParser.parseJsonLineWithContext(line, index.toInt + 1, logger)

  private def handleParseResult(
      result: Either[Throwable, Option[Message]]
  ): IO[Option[Message]] =
    result match
      case Left(jsonError)   => IO.raiseError(jsonError)
      case Right(messageOpt) => IO.pure(messageOpt)

  private def logProcessCompletion(exitCode: Int)(using
      logger: Logger[IO]
  ): IO[Unit] =
    logger.info(s"Process completed with exit code: $exitCode")

  private def handleProcessFailure(
      exitCode: Int,
      stderrContent: String,
      executablePath: String,
      args: List[String]
  )(using logger: Logger[IO]): IO[Unit] =
    if (exitCode != 0)
      logger.error(
        s"Process failed with exit code $exitCode: $stderrContent"
      ) *>
        IO.raiseError(
          ProcessExecutionError(
            exitCode,
            stderrContent,
            executablePath :: args
          )
        )
    else IO.unit

  private def applyTimeoutIfSpecified(
      processIO: IO[List[Message]],
      options: QueryOptions,
      executablePath: String,
      args: List[String]
  )(using logger: Logger[IO]): IO[List[Message]] =
    options.timeout match
      case Some(timeout) =>
        processIO.timeoutTo(
          timeout,
          logger.error(
            s"Process timed out after ${timeout.toSeconds} seconds"
          ) *>
            IO.raiseError(
              ProcessTimeoutError(
                timeout,
                executablePath :: args
              )
            )
        )
      case None => processIO

  private def applyTimeoutIfSpecifiedStream(
      processStream: Stream[IO, Message],
      options: QueryOptions,
      executablePath: String,
      args: List[String]
  )(using logger: Logger[IO]): Stream[IO, Message] =
    options.timeout match
      case Some(timeout) =>
        processStream.timeout(timeout).handleErrorWith { throwable =>
          Stream
            .eval(
              logger.error(
                s"Process timed out after ${timeout.toSeconds} seconds"
              ) *>
                IO.raiseError(
                  ProcessTimeoutError(
                    timeout,
                    executablePath :: args
                  )
                )
            )
            .drain
        }
      case None => processStream

  /** Validates environment variable names for obviously invalid cases.
    *
    * This validation catches the most problematic environment variable names
    * that are likely to cause issues across different systems:
    *   - Names starting with numbers (like "123_VAR")
    *   - Names containing hyphens (like "VAR-NAME")
    *
    * More lenient validation allows edge cases like dots and empty names to be
    * handled gracefully by the underlying system.
    *
    * @param envVars
    *   Map of environment variables to validate
    * @throws EnvironmentValidationError
    *   if any variable names are invalid
    */
  private def validateEnvironmentVariables(envVars: Map[String, String]): Unit =
    val invalidNames =
      envVars.keys.filter(name => !isValidEnvironmentVariableName(name)).toList

    if (invalidNames.nonEmpty)
      throw EnvironmentValidationError(
        invalidNames,
        "Environment variable names cannot start with numbers or contain hyphens"
      )

  /** Checks if an environment variable name is valid for most systems.
    *
    * This validation is intentionally lenient, only catching the most obviously
    * problematic cases to allow system-specific handling of edge cases.
    *
    * @param name
    *   The environment variable name to validate
    * @return
    *   true if the name is valid, false otherwise
    */
  private def isValidEnvironmentVariableName(name: String): Boolean =
    // Allow empty names (system will handle gracefully)
    if (name.isEmpty) true
    else
      // Reject names starting with numbers or containing hyphens
      !name.head.isDigit && !name.contains('-')
