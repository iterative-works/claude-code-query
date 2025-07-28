// PURPOSE: Direct-style process management using Ox for structured concurrency
// PURPOSE: Handles CLI process execution, streaming stdout parsing, and resource cleanup
package works.iterative.claude.direct.internal.cli

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.core.{ProcessExecutionError, ProcessTimeoutError}
import works.iterative.claude.direct.internal.parsing.JsonParser
import works.iterative.claude.direct.Logger
import java.io.{BufferedReader, InputStreamReader}
import scala.jdk.CollectionConverters.*

object ProcessManager:

  def executeProcess(
      executablePath: String,
      args: List[String],
      options: QueryOptions
  )(using logger: Logger, ox: Ox): List[Message] =
    val command = executablePath :: args
    logger.info(
      s"Starting process: $executablePath with args: ${args.mkString(" ")}"
    )

    // Apply timeout if specified
    options.timeout match
      case Some(timeoutDuration) =>
        executeProcessWithTimeout(
          executablePath,
          args,
          options,
          command,
          timeoutDuration
        )
      case None =>
        executeProcessWithoutTimeout(executablePath, args, options, command)

  private def executeProcessWithTimeout(
      executablePath: String,
      args: List[String],
      options: QueryOptions,
      command: List[String],
      timeoutDuration: scala.concurrent.duration.Duration
  )(using logger: Logger, ox: Ox): List[Message] =
    val processBuilder = configureProcess(executablePath, args, options)

    // Log the command being executed for debugging
    logger.debug(s"Executing command: $executablePath ${args.mkString(" ")}")

    // Convert timeout duration to FiniteDuration for Ox
    val finiteDuration = timeoutDuration match {
      case fd: scala.concurrent.duration.FiniteDuration => fd
      case _                                            =>
        scala.concurrent.duration
          .FiniteDuration(timeoutDuration.toMillis, "milliseconds")
    }

    // Start the process outside the timeout block so we can access it in the catch block
    val process = processBuilder.start()

    // Close stdin immediately since prompt is passed as command-line argument
    try {
      process.getOutputStream().close()
    } catch {
      case _: Exception => // Ignore if already closed
    }

    // Use direct process timeout without relying on Ox timeout for stream operations
    val finished = process.waitFor(
      finiteDuration.toMillis,
      java.util.concurrent.TimeUnit.MILLISECONDS
    )

    if (!finished) {
      // Process timed out - destroy it and throw timeout error
      process.destroyForcibly()
      logger.error(s"Process timed out after ${finiteDuration}")
      throw ProcessTimeoutError(finiteDuration, command)
    }

    // Process completed within timeout - read streams concurrently
    val (stdout, stderr) = par(
      {
        val reader = new java.io.BufferedReader(
          new java.io.InputStreamReader(process.getInputStream)
        )
        val lines = scala.collection.mutable.ListBuffer[String]()
        var line: String = null
        while ({ line = reader.readLine(); line != null }) {
          lines += line
        }
        reader.close()
        lines.toList
      }, {
        val reader = new java.io.BufferedReader(
          new java.io.InputStreamReader(process.getErrorStream)
        )
        val lines = scala.collection.mutable.ListBuffer[String]()
        var line: String = null
        while ({ line = reader.readLine(); line != null }) {
          lines += line
        }
        reader.close()
        lines.toList
      }
    )

    val exitCode = process.exitValue()

    // Log process completion (required by tests)
    logger.info(s"Process completed with exit code: $exitCode")

    // Log stderr content if present (required by tests)
    if stderr.nonEmpty then
      stderr.foreach(line => logger.debug(s"stderr: $line"))

    // Check for process execution errors (essential for tests)
    if exitCode != 0 then
      val stderrContent = stderr.mkString("\n")
      throw ProcessExecutionError(exitCode, stderrContent, command)

    // Parse the stdout lines into messages with logging
    stdout.zipWithIndex.flatMap { case (line, index) =>
      JsonParser.parseJsonLineWithContextWithLogging(line, index + 1) match {
        case Right(Some(message)) => Some(message)
        case Right(None)          => None // Empty line, skip
        case Left(error)          =>
          logger.error(s"JSON parsing failed: ${error.message}")
          None
      }
    }

  private def executeProcessWithoutTimeout(
      executablePath: String,
      args: List[String],
      options: QueryOptions,
      command: List[String]
  )(using logger: Logger, ox: Ox): List[Message] =
    val processBuilder = configureProcess(executablePath, args, options)

    // Log the command being executed for debugging
    logger.debug(s"Executing command: $executablePath ${args.mkString(" ")}")

    val process = processBuilder.start()

    // Close stdin immediately since prompt is passed as command-line argument
    try {
      process.getOutputStream().close()
    } catch {
      case _: Exception => // Ignore if already closed
    }

    // Use the same simple working pattern
    val (stdout, stderr) = par(
      {
        val reader = new java.io.BufferedReader(
          new java.io.InputStreamReader(process.getInputStream)
        )
        val lines = scala.collection.mutable.ListBuffer[String]()
        var line: String = null
        while ({ line = reader.readLine(); line != null }) {
          lines += line
        }
        reader.close()
        lines.toList
      }, {
        val reader = new java.io.BufferedReader(
          new java.io.InputStreamReader(process.getErrorStream)
        )
        val lines = scala.collection.mutable.ListBuffer[String]()
        var line: String = null
        while ({ line = reader.readLine(); line != null }) {
          lines += line
        }
        reader.close()
        lines.toList
      }
    )

    val exitCode = process.waitFor() // Should return immediately

    // Log process completion (required by tests)
    logger.info(s"Process completed with exit code: $exitCode")

    // Log stderr content if present (required by tests)
    if stderr.nonEmpty then
      stderr.foreach(line => logger.debug(s"stderr: $line"))

    // Check for process execution errors (essential for tests)
    if exitCode != 0 then
      val stderrContent = stderr.mkString("\n")
      throw ProcessExecutionError(exitCode, stderrContent, command)

    // Parse the stdout lines into messages with logging
    stdout.zipWithIndex.flatMap { case (line, index) =>
      JsonParser.parseJsonLineWithContextWithLogging(line, index + 1) match {
        case Right(Some(message)) => Some(message)
        case Right(None)          => None // Empty line, skip
        case Left(error)          =>
          logger.error(s"JSON parsing failed: ${error.message}")
          None
      }
    }

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

  private def waitForProcessWithTimeout(
      process: Process,
      timeoutDuration: scala.concurrent.duration.Duration,
      command: List[String]
  )(using logger: Logger): Unit =
    val finiteDuration = timeoutDuration match
      case fd: scala.concurrent.duration.FiniteDuration => fd
      case _                                            =>
        scala.concurrent.duration
          .FiniteDuration(timeoutDuration.toMillis, "milliseconds")

    val finished = process.waitFor(
      finiteDuration.toMillis,
      java.util.concurrent.TimeUnit.MILLISECONDS
    )

    if !finished then
      logger.error(s"Process timed out after ${timeoutDuration}")
      process.destroyForcibly()
      throw ProcessTimeoutError(finiteDuration, command)

  private def readProcessOutput(process: Process)(using
      logger: Logger
  ): List[Message] =
    val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream)
    )
    val messages = scala.collection.mutable.ListBuffer[Message]()

    var lineNumber = 0
    var line: String = null
    while { line = reader.readLine(); line != null } do
      lineNumber += 1
      parseJsonLineToMessage(line, lineNumber) match
        case Some(message) => messages += message
        case None          => // Skip empty or invalid lines

    reader.close()
    messages.toList

  private def parseJsonLineToMessage(line: String, lineNumber: Int)(using
      logger: Logger
  ): Option[Message] =
    JsonParser.parseJsonLineWithContextWithLogging(line, lineNumber) match
      case Right(Some(message)) => Some(message)
      case Right(None)          => None
      case Left(error)          =>
        logger.error(s"JSON parsing failed: ${error.message}")
        None

  /** Reads from an InputStream with proper interruption handling for Ox
    * timeout. This method checks for thread interruption during blocking
    * readLine() operations and handles InterruptedException properly for
    * structured concurrency.
    */
  private def readStreamWithInterruption(
      stream: java.io.InputStream
  ): List[String] =
    val reader = new java.io.BufferedReader(
      new java.io.InputStreamReader(stream)
    )
    val lines = scala.collection.mutable.ListBuffer[String]()

    try {
      var line: String = null
      while ({
        // Check for interruption before each blocking read
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException("Stream reading interrupted")
        }
        line = reader.readLine()
        line != null && !Thread.currentThread().isInterrupted()
      }) {
        lines += line
      }
      lines.toList
    } catch {
      case _: InterruptedException =>
        // Restore interrupt status and return partial results
        Thread.currentThread().interrupt()
        lines.toList
      case _: java.io.IOException =>
        // Stream was closed (e.g., process terminated), return partial results
        lines.toList
    } finally {
      try {
        reader.close()
      } catch {
        case _: Exception => // Ignore close errors
      }
    }

  private def captureStderrConcurrently(
      process: Process
  )(using logger: Logger, ox: Ox) =
    fork {
      captureStderrStream(process)
    }

  private def captureStderrSynchronously(process: Process)(using
      logger: Logger
  ): List[String] =
    captureStderrStream(process)

  private def captureStderrStream(process: Process)(using
      logger: Logger
  ): List[String] =
    val stderrReader = new BufferedReader(
      new InputStreamReader(process.getErrorStream)
    )
    val stderrContent = scala.collection.mutable.ListBuffer[String]()
    var stderrLine: String = null
    while { stderrLine = stderrReader.readLine(); stderrLine != null } do
      stderrContent += stderrLine
      logger.debug(s"stderr: $stderrLine")
    stderrReader.close()
    stderrContent.toList

  private def handleProcessCompletion(process: Process, command: List[String])(
      using logger: Logger
  ): Unit =
    val exitCode = process.exitValue()
    logger.info(s"Process completed with exit code: $exitCode")

    if exitCode != 0 then throw ProcessExecutionError(exitCode, "", command)
