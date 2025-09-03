// PURPOSE: Direct-style process management using Ox for structured concurrency
// PURPOSE: Handles CLI process execution, streaming stdout parsing, and resource cleanup
package works.iterative.claude.direct.internal.cli

import ox.*
import ox.flow.Flow
import works.iterative.claude.core.model.*
import works.iterative.claude.core.{
  CLIError,
  ProcessExecutionError,
  ProcessTimeoutError
}
import works.iterative.claude.direct.internal.parsing.JsonParser
import works.iterative.claude.direct.Logger
import java.io.{BufferedReader, InputStreamReader}
import scala.jdk.CollectionConverters.*

object ProcessManager:

  /** Executes a process and returns a real-time streaming Flow of messages.
    * Messages are emitted as soon as they are produced by the CLI process, not
    * after the process completes. Supports timeout handling.
    */
  def executeProcessStreaming(
      executablePath: String,
      args: List[String],
      options: QueryOptions
  )(using logger: Logger, ox: Ox): Flow[Message] =
    Flow.usingEmit { emit =>
      options.timeout match {
        case Some(timeoutDuration) =>
          executeStreamingCoreWithTimeout(
            executablePath,
            args,
            options,
            timeoutDuration,
            emit
          )
        case None =>
          executeStreamingCore(executablePath, args, options, emit)
      }
    }

  private def executeStreamingCore(
      executablePath: String,
      args: List[String],
      options: QueryOptions,
      emit: ox.flow.FlowEmit[Message]
  )(using logger: Logger, ox: Ox): Unit =
    val command = executablePath :: args
    logger.info(
      s"Starting process: $executablePath with args: ${args.mkString(" ")}"
    )

    val processBuilder = configureProcess(executablePath, args, options)
    val process = processBuilder.start()

    // Close stdin immediately since prompt is passed as command-line argument
    try {
      process.getOutputStream().close()
    } catch {
      case _: Exception => // Ignore if already closed
    }

    // Start process monitoring (will be interrupted by Ox timeout if needed)
    val processErrorFork = startProcessMonitoringForStreaming(process, command)

    // Fork stderr capture to run in background
    fork {
      captureStderrStream(process)
    }

    // Main streaming task - read stdout and emit messages
    val stdoutEndedNaturally = processStdoutStream(process, emit)

    // Handle cleanup - process monitoring will be interrupted by timeout if needed
    handleStreamingCleanup(processErrorFork, stdoutEndedNaturally)

  private def executeStreamingCoreWithTimeout(
      executablePath: String,
      args: List[String],
      options: QueryOptions,
      timeoutDuration: scala.concurrent.duration.Duration,
      emit: ox.flow.FlowEmit[Message]
  )(using logger: Logger, ox: Ox): Unit =
    val command = executablePath :: args
    val finiteDuration = timeoutDuration match {
      case fd: scala.concurrent.duration.FiniteDuration => fd
      case _                                            =>
        scala.concurrent.duration
          .FiniteDuration(timeoutDuration.toMillis, "milliseconds")
    }

    logger.info(
      s"Starting process: $executablePath with args: ${args.mkString(" ")}"
    )

    val processBuilder = configureProcess(executablePath, args, options)
    val process = processBuilder.start()

    try {
      process.getOutputStream().close()
    } catch {
      case _: Exception => // Ignore if already closed
    }

    // Race the streaming operations against a timeout
    val result = raceSuccess(
      {
        // Streaming branch - runs the actual process streaming
        val processErrorFork =
          startProcessMonitoringForStreaming(process, command)

        fork {
          captureStderrStream(process)
        }

        // Stream stdout messages (this is the main blocking operation)
        val stdoutEndedNaturally = processStdoutStream(process, emit)

        // Handle cleanup
        handleStreamingCleanup(processErrorFork, stdoutEndedNaturally)

        "STREAMING_COMPLETED"
      }, {
        // Timeout branch - destroy process immediately when timeout occurs
        sleep(finiteDuration)
        logger.error(s"Process timed out after ${finiteDuration}")
        process
          .destroyForcibly() // Destroy process to unblock readLine() operations
        "TIMEOUT_REACHED"
      }
    )

    result match {
      case "STREAMING_COMPLETED" =>
      // Normal completion - do nothing
      case "TIMEOUT_REACHED" =>
        // Process already destroyed in timeout branch - just throw error
        throw ProcessTimeoutError(finiteDuration, command)
    }

  private def startProcessMonitoringForStreaming(
      process: Process,
      command: List[String]
  )(using logger: Logger, ox: Ox): Fork[Option[CLIError]] =
    fork {
      try {
        val exitCode = process.waitFor()
        logger.info(s"Process completed with exit code: $exitCode")
        validateStreamingProcessExitCode(exitCode, command)
      } catch {
        case _: InterruptedException =>
          // Process was interrupted by timeout - clean up and don't throw here
          logger.debug(
            "Process monitoring interrupted by timeout - cleaning up process"
          )
          process.destroyForcibly()
          None
      }
    }

  private def captureStderrStream(process: Process)(using
      logger: Logger
  ): Unit =
    val stderrReader = new BufferedReader(
      new InputStreamReader(process.getErrorStream)
    )
    try {
      var stderrLine: String = null
      while ({
        stderrLine = stderrReader.readLine(); stderrLine != null
      }) {
        logger.debug(s"stderr: $stderrLine")
      }
    } catch {
      case _: InterruptedException =>
        // Interrupted by timeout - clean up and exit
        logger.debug("Stderr capture interrupted by timeout")
      case _: Exception =>
        // Stream closed or process terminated
        logger.debug("Stderr stream closed")
    } finally {
      try {
        stderrReader.close()
      } catch {
        case _: Exception => // Ignore close errors
      }
    }

  private def processStdoutStream(
      process: Process,
      emit: ox.flow.FlowEmit[Message]
  )(using logger: Logger): Boolean =
    val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream)
    )
    var stdoutEndedNaturally = false
    try {
      var lineNumber = 0
      var line: String = null
      while ({ line = reader.readLine(); line != null }) {
        lineNumber += 1
        parseJsonLineToMessage(line, lineNumber) match {
          case Some(message) =>
            logger.debug(
              s"Emitting message: ${message.getClass.getSimpleName}"
            )
            emit(message)
          case None => // Skip empty or invalid lines
        }
      }
      stdoutEndedNaturally = true
      logger.debug("Stdout stream ended naturally")
    } catch {
      case _: InterruptedException =>
        // Interrupted by timeout - clean up and exit
        logger.debug("Stdout reading interrupted by timeout")
      case _: Exception =>
        // Stream was closed (likely due to process termination or Flow cancellation)
        logger.debug("Stdout stream reading interrupted or closed")
    } finally {
      try {
        reader.close()
      } catch {
        case _: Exception => // Ignore close errors
      }
    }
    stdoutEndedNaturally

  private def handleStreamingCleanup(
      processErrorFork: Fork[Option[CLIError]],
      stdoutEndedNaturally: Boolean
  )(using logger: Logger): Unit =
    if (stdoutEndedNaturally) {
      processErrorFork.join() match {
        case Some(error) => throw error
        case None        => // Process completed successfully
      }
    } else {
      // Early termination or timeout - don't wait for process to complete
      logger.debug(
        "Flow terminated early or timed out - not waiting for process completion"
      )
    }

  private def validateStreamingProcessExitCode(
      exitCode: Int,
      command: List[String]
  )(using logger: Logger): Option[CLIError] =
    if (exitCode != 0) {
      // Exit code 141 is SIGPIPE, which happens when the process tries to write
      // to stdout after we've closed it (e.g., due to early termination with .take())
      // This is expected behavior for streaming and should not be treated as an error
      if (exitCode == 141) {
        logger.debug(
          s"Process terminated with SIGPIPE (exit code 141) - expected during early stream termination"
        )
        None
      } else {
        logger.error(s"Process failed with exit code: $exitCode")
        Some(
          ProcessExecutionError(
            exitCode,
            "Process failed during streaming",
            command
          )
        )
      }
    } else {
      None
    }

  def executeProcess(
      executablePath: String,
      args: List[String],
      options: QueryOptions
  )(using logger: Logger, ox: Ox): List[Message] =
    // Use streaming implementation and collect all messages
    executeProcessStreaming(executablePath, args, options).runToList()

  def configureProcess(
      executablePath: String,
      args: List[String],
      options: QueryOptions
  ): ProcessBuilder =
    val processBuilder = createProcessBuilder(executablePath, args)
    setWorkingDirectory(processBuilder, options)
    configureEnvironment(processBuilder, options)
    processBuilder

  private def createProcessBuilder(
      executablePath: String,
      args: List[String]
  ): ProcessBuilder =
    new ProcessBuilder((executablePath :: args).asJava)

  private def setWorkingDirectory(
      processBuilder: ProcessBuilder,
      options: QueryOptions
  ): Unit =
    options.cwd.foreach { cwdPath =>
      processBuilder.directory(new java.io.File(cwdPath))
    }

  private def configureEnvironment(
      processBuilder: ProcessBuilder,
      options: QueryOptions
  ): Unit =
    val environment = processBuilder.environment()
    options.inheritEnvironment match
      case Some(false) =>
        environment.clear()
      case Some(true) | None =>
        ()

    options.environmentVariables.foreach { envVars =>
      envVars.foreach { case (key, value) =>
        environment.put(key, value)
      }
    }

  private def parseJsonLineToMessage(line: String, lineNumber: Int)(using
      logger: Logger
  ): Option[Message] =
    JsonParser.parseJsonLineWithContextWithLogging(line, lineNumber) match
      case Right(Some(message)) => Some(message)
      case Right(None)          => None
      case Left(error)          =>
        logger.error(s"JSON parsing failed: ${error.message}")
        None
