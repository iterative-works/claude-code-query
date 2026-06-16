// PURPOSE: Manages subprocess execution and configuration for the Claude Code CLI
// PURPOSE: Spawns the CLI with zio-process and streams parsed messages with typed errors

package works.iterative.claude.zio.internal.cli

import zio.*
import zio.stream.*
import zio.process.{Command, Process, ProcessInput, CommandError}
import java.io.File
import works.iterative.claude.core.model.{QueryOptions, Message}
import works.iterative.claude.core.{
  CLIError,
  ProcessExecutionError,
  ProcessTimeoutError,
  EnvironmentValidationError
}
import works.iterative.claude.zio.internal.parsing.JsonParser

/** Builds and runs the Claude Code CLI process, exposing its stdout as a stream
  * of parsed [[Message]] values.
  *
  * The process is spawned with zio-process. stdout lines are parsed into
  * messages; stderr is collected concurrently for error diagnostics. When the
  * stream completes naturally the process exit code is checked and a non-zero
  * code is surfaced as a [[ProcessExecutionError]]. An optional timeout kills
  * the process and surfaces a [[ProcessTimeoutError]].
  */
object ProcessManager:

  /** Validate environment variables and build the zio-process command for the
    * given executable, arguments, and options.
    */
  def buildCommand(
      executablePath: String,
      args: List[String],
      options: QueryOptions
  ): IO[EnvironmentValidationError, Command] =
    validateEnvironment(options.environmentVariables).as(
      configureCommand(executablePath, args, options)
    )

  /** Execute the configured process and stream parsed messages. */
  def executeProcess(
      executablePath: String,
      args: List[String],
      options: QueryOptions
  ): ZStream[Any, CLIError, Message] =
    val command = executablePath :: args
    ZStream.unwrapScoped:
      for
        _ <- ZIO.logInfo(
          s"Starting process: $executablePath ${args.mkString(" ")}"
        )
        builtCommand <- buildCommand(executablePath, args, options)
        process <- builtCommand.run
          .mapError(toProcessError(_, command))
        _ <- ZIO.addFinalizer(process.killForcibly.ignore)
        stderrFiber <- collectStderr(process).forkScoped
        timedOutRef <- Ref.make(false)
        _ <- scheduleTimeout(process, options, timedOutRef)
      yield parseMessages(process, command)
        ++ checkCompletion(
          process,
          stderrFiber,
          timedOutRef,
          options,
          command
        )

  private def configureCommand(
      executablePath: String,
      args: List[String],
      options: QueryOptions
  ): Command =
    // zio-process inherits the parent environment and merges extra vars on top.
    // It cannot clear the inherited environment, so `inheritEnvironment = false`
    // is honoured only as far as additive variables; full clearing is not
    // supported by the underlying library.
    val base = Command(executablePath, args*)
    val withEnv = options.environmentVariables.fold(base): envVars =>
      if envVars.nonEmpty then base.env(envVars) else base
    val withCwd = options.cwd.fold(withEnv): cwd =>
      withEnv.workingDirectory(new File(cwd))
    // The CLI reads its prompt from arguments; close stdin so it never blocks.
    withCwd.stdin(ProcessInput.fromStream(ZStream.empty))

  private def parseMessages(
      process: Process,
      command: List[String]
  ): ZStream[Any, CLIError, Message] =
    process.stdout.linesStream
      .mapError(toProcessError(_, command))
      .zipWithIndex
      .mapZIO: (line, index) =>
        JsonParser.parseJsonLineWithContext(line, index.toInt + 1)
      .collect { case Some(message) => message }

  /** Runs after the message stream completes naturally. Checks the exit code
    * and the timeout flag, failing with the appropriate typed error.
    */
  private def checkCompletion(
      process: Process,
      stderrFiber: Fiber[Nothing, String],
      timedOutRef: Ref[Boolean],
      options: QueryOptions,
      command: List[String]
  ): ZStream[Any, CLIError, Nothing] =
    ZStream
      .fromZIO:
        for
          exitCode <- process.exitCode.mapError(toProcessError(_, command))
          _ <- ZIO.logInfo(
            s"Process completed with exit code: ${exitCode.code}"
          )
          timedOut <- timedOutRef.get
          stderrContent <- stderrFiber.join
          _ <-
            if timedOut then failTimeout(options, command)
            else if exitCode.code != 0 then
              ZIO.logError(
                s"Process failed with exit code ${exitCode.code}: $stderrContent"
              ) *> ZIO.fail(
                ProcessExecutionError(exitCode.code, stderrContent, command)
              )
            else ZIO.unit
        yield ()
      .drain

  private def scheduleTimeout(
      process: Process,
      options: QueryOptions,
      timedOutRef: Ref[Boolean]
  ): URIO[Scope, Unit] =
    options.timeout match
      case Some(timeout) =>
        (ZIO.sleep(Duration.fromScala(timeout))
          *> timedOutRef.set(true)
          *> process.killForcibly.ignore).forkScoped.unit
      case None => ZIO.unit

  private def failTimeout(
      options: QueryOptions,
      command: List[String]
  ): IO[ProcessTimeoutError, Unit] =
    options.timeout match
      case Some(timeout) =>
        ZIO.logError(
          s"Process timed out after ${timeout.toSeconds} seconds"
        ) *> ZIO.fail(ProcessTimeoutError(timeout, command))
      case None => ZIO.unit

  /** Best-effort stderr capture; never fails so it cannot mask the real error.
    */
  private def collectStderr(process: Process): UIO[String] =
    process.stderr.linesStream.runCollect
      .map(_.mkString("\n"))
      .orElseSucceed("")

  private def toProcessError(
      error: CommandError,
      command: List[String]
  ): CLIError =
    val exitCode = error match
      case CommandError.NonZeroErrorCode(code) => code.code
      case _                                   => -1
    ProcessExecutionError(exitCode, error.getMessage, command)

  /** Validates environment variable names for obviously invalid cases.
    *
    * Catches names starting with a digit or containing a hyphen, which are
    * problematic across systems. Empty names are allowed and handled by the
    * underlying system.
    */
  private def validateEnvironment(
      environmentVariables: Option[Map[String, String]]
  ): IO[EnvironmentValidationError, Unit] =
    environmentVariables match
      case None          => ZIO.unit
      case Some(envVars) =>
        val invalidNames =
          envVars.keys.filterNot(isValidEnvironmentVariableName).toList
        ZIO
          .when(invalidNames.nonEmpty)(
            ZIO.fail(
              EnvironmentValidationError(
                invalidNames,
                "Environment variable names cannot start with numbers or contain hyphens"
              )
            )
          )
          .unit

  private def isValidEnvironmentVariableName(name: String): Boolean =
    if name.isEmpty then true
    else !name.head.isDigit && !name.contains('-')
