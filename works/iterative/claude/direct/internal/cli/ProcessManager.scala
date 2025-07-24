// PURPOSE: Direct-style process management using Ox for structured concurrency
// PURPOSE: Handles CLI process execution, streaming stdout parsing, and resource cleanup
package works.iterative.claude.direct.internal.cli

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.core.{ProcessExecutionError, ProcessTimeoutError}
import works.iterative.claude.direct.internal.parsing.JsonParser
import java.io.{BufferedReader, InputStreamReader}
import scala.jdk.CollectionConverters.*

object ProcessManager:

  def configureProcess(
      executablePath: String,
      args: List[String],
      options: QueryOptions
  ): ProcessBuilder =
    // GREEN Phase: Implementation to make T5.1, T5.2, T5.3, T5.4, T5.5 tests pass
    val processBuilder = new ProcessBuilder((executablePath :: args).asJava)

    // Set working directory when provided
    options.cwd.foreach { cwdPath =>
      processBuilder.directory(new java.io.File(cwdPath))
    }

    // Handle environment inheritance
    val environment = processBuilder.environment()
    options.inheritEnvironment match
      case Some(false) =>
        // Clear all inherited environment variables
        environment.clear()
      case Some(true) | None =>
        // Keep inherited environment (default ProcessBuilder behavior)
        ()

    // Set environment variables when provided
    options.environmentVariables.foreach { envVars =>
      envVars.foreach { case (key, value) =>
        environment.put(key, value)
      }
    }

    processBuilder

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
    // Create process builder using configureProcess to handle environment variables
    val processBuilder = configureProcess(executablePath, args, options)

    // Start the process
    val process = processBuilder.start()

    // Convert Duration to FiniteDuration for timeout
    val finiteDuration = timeoutDuration match
      case fd: scala.concurrent.duration.FiniteDuration => fd
      case _                                            =>
        scala.concurrent.duration
          .FiniteDuration(timeoutDuration.toMillis, "milliseconds")

    // Wait for process to complete with timeout first (like we did in the working version)
    val finished = process.waitFor(
      finiteDuration.toMillis,
      java.util.concurrent.TimeUnit.MILLISECONDS
    )

    if !finished then
      logger.error(s"Process timed out after ${timeoutDuration}")
      process.destroyForcibly()
      throw ProcessTimeoutError(finiteDuration, command)

    val exitCode = process.exitValue()

    // Process completed within timeout, now read stdout
    val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream)
    )
    val messages = scala.collection.mutable.ListBuffer[Message]()

    var lineNumber = 0
    var line: String = null
    while { line = reader.readLine(); line != null } do
      lineNumber += 1
      JsonParser.parseJsonLineWithContextWithLogging(line, lineNumber) match
        case Right(Some(message)) => messages += message
        case Right(None)          => // Skip empty lines
        case Left(error)          =>
          logger.error(s"JSON parsing failed: ${error.message}")
          // For this minimal implementation, continue processing other lines

    reader.close()

    // Read stderr synchronously after process completes - it won't hang since process is done
    val stderrReader = new BufferedReader(
      new InputStreamReader(process.getErrorStream)
    )
    val stderrContent = scala.collection.mutable.ListBuffer[String]()
    var stderrLine: String = null
    while { stderrLine = stderrReader.readLine(); stderrLine != null } do
      stderrContent += stderrLine
      logger.debug(s"stderr: $stderrLine")
    stderrReader.close()
    val stderrLines = stderrContent.toList

    logger.info(s"Process completed with exit code: $exitCode")

    if exitCode != 0 then throw ProcessExecutionError(exitCode, "", command)

    messages.toList

  private def executeProcessWithoutTimeout(
      executablePath: String,
      args: List[String],
      options: QueryOptions,
      command: List[String]
  )(using logger: Logger, ox: Ox): List[Message] =
    // Create process builder using configureProcess to handle environment variables
    val processBuilder = configureProcess(executablePath, args, options)

    // Start the process
    val process = processBuilder.start()

    // Concurrently capture stderr using Ox fork
    val stderrCapture = fork {
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
    }

    // Read stdout and parse JSON messages
    val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream)
    )
    val messages = scala.collection.mutable.ListBuffer[Message]()

    var lineNumber = 0
    var line: String = null
    while { line = reader.readLine(); line != null } do
      lineNumber += 1
      JsonParser.parseJsonLineWithContextWithLogging(line, lineNumber) match
        case Right(Some(message)) => messages += message
        case Right(None)          => // Skip empty lines
        case Left(error)          =>
          logger.error(s"JSON parsing failed: ${error.message}")
          // For this minimal implementation, continue processing other lines

    reader.close()

    // Wait for process to complete
    val exitCode = process.waitFor()

    // Wait for stderr capture to complete
    val stderrLines = stderrCapture.join()

    logger.info(s"Process completed with exit code: $exitCode")

    if exitCode != 0 then throw ProcessExecutionError(exitCode, "", command)

    messages.toList
