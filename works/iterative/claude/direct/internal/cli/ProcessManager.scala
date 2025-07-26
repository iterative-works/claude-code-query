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
    
    val process = processBuilder.start()
    
    // Close stdin immediately since prompt is passed as command-line argument
    try {
      process.getOutputStream().close()
    } catch {
      case _: Exception => // Ignore if already closed
    }

    // Use the simple working pattern with timeout wrapper
    val finiteDuration = timeoutDuration match {
      case fd: scala.concurrent.duration.FiniteDuration => fd
      case _                                            =>
        scala.concurrent.duration
          .FiniteDuration(timeoutDuration.toMillis, "milliseconds")
    }
    
    try {
      timeout(finiteDuration) {
        // Simple pattern that we know works
        val (stdout, stderr) = par(
          {
            val reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream))
            val lines = scala.collection.mutable.ListBuffer[String]()
            var line: String = null
            while ({ line = reader.readLine(); line != null }) {
              lines += line
            }
            reader.close()
            lines.toList
          },
          {
            val reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream))
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
        
        // Parse the stdout lines into messages
        stdout.zipWithIndex.flatMap { case (line, index) =>
          JsonParser.parseJsonLineWithContext(line, index + 1) match {
            case Right(Some(message)) => Some(message)
            case _ => None
          }
        }
      }
    } catch {
      case _: java.util.concurrent.TimeoutException =>
        process.destroyForcibly()
        throw ProcessTimeoutError(finiteDuration, command)
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
        val reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream))
        val lines = scala.collection.mutable.ListBuffer[String]()
        var line: String = null
        while ({ line = reader.readLine(); line != null }) {
          lines += line
        }
        reader.close()
        lines.toList
      },
      {
        val reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream))
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
    
    // Parse the stdout lines into messages
    stdout.zipWithIndex.flatMap { case (line, index) =>
      JsonParser.parseJsonLineWithContext(line, index + 1) match {
        case Right(Some(message)) => Some(message)
        case _ => None
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
