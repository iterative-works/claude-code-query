// PURPOSE: Internal implementation of Session backed by a long-lived CLI process
// PURPOSE: Manages stdin writing, stdout streaming, stderr capture, and process lifecycle
package works.iterative.claude.direct.internal.cli

import ox.*
import ox.flow.Flow
import works.iterative.claude.core.model.*
import works.iterative.claude.core.cli.CLIArgumentBuilder
import works.iterative.claude.core.{SessionClosedError, SessionProcessDied}
import works.iterative.claude.direct.{Logger, Session}
import works.iterative.claude.direct.internal.parsing.JsonParser
import io.circe.syntax.*
import java.io.{
  BufferedReader,
  BufferedWriter,
  InputStreamReader,
  OutputStreamWriter
}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

/** Session implementation backed by a long-lived CLI process.
  *
  * The process is started in the factory method and stays alive across multiple
  * `send` calls. A background fork captures stderr for diagnostics. The session
  * ID is extracted from the CLI's initial SystemMessage(subtype="init") if it
  * arrives before the first send; otherwise "pending" is used until the first
  * ResultMessage updates it.
  */
private[direct] class SessionProcess(
    process: Process,
    stdinWriter: BufferedWriter,
    stdoutReader: BufferedReader
)(using logger: Logger)
    extends Session:

  private[direct] def underlyingProcess: Process = process

  private val currentSessionId = new AtomicReference[String]("pending")
  private val alive = new AtomicBoolean(true)

  private def safeExitCode(): Option[Int] =
    try Some(process.exitValue())
    catch case _: Exception => None

  def sessionId: String = currentSessionId.get()

  def send(prompt: String): Unit =
    if !alive.get() then
      // Defect: caller violated session contract
      throw SessionClosedError(currentSessionId.get())
    if !process.isAlive then
      alive.set(false)
      // Defect: underlying process crashed
      throw SessionProcessDied(safeExitCode(), captureRemainingStderr())
    val msg =
      SDKUserMessage(content = prompt, sessionId = currentSessionId.get())
    val json = msg.asJson.noSpaces
    logger.debug(s"Writing SDKUserMessage to stdin: $json")
    try
      stdinWriter.write(json)
      stdinWriter.newLine()
      stdinWriter.flush()
    catch
      case e: java.io.IOException =>
        alive.set(false)
        // Defect: I/O failure on process pipe
        throw SessionProcessDied(
          safeExitCode(),
          e.getMessage
        ) // scalafix:ok DisableSyntax.throw

  def stream(): Flow[Message] =
    Flow.usingEmit { emit =>
      @tailrec
      def loop(lineNumber: Int): Boolean =
        Option(stdoutReader.readLine()) match
          case None =>
            logger.debug("stdout EOF reached during stream")
            false
          case Some(line) =>
            val nextLineNumber = lineNumber + 1
            JsonParser
              .parseJsonLineWithContextWithLogging(line, nextLineNumber) match
              case Right(Some(message)) =>
                logger.debug(
                  s"Emitting message: ${message.getClass.getSimpleName}"
                )
                emit(message)
                message match
                  case result: ResultMessage =>
                    currentSessionId.set(result.sessionId)
                    true
                  case _ =>
                    loop(nextLineNumber)
              case Right(None) =>
                loop(nextLineNumber)
              case Left(error) =>
                logger.error(s"JSON parsing failed: ${error.message}")
                loop(nextLineNumber)

      val resultSeen = loop(0)
      if !resultSeen then
        // Unexpected EOF before ResultMessage — process died mid-turn
        alive.set(false)
        // Defect: process died mid-turn
        throw SessionProcessDied(
          safeExitCode(),
          captureRemainingStderr()
        ) // scalafix:ok DisableSyntax.throw
    }

  def close(): Unit =
    if alive.compareAndSet(true, false) then
      logger.debug("Closing session process")
      try stdinWriter.close()
      catch case _: Exception => ()
      try
        val exited = process.waitFor(5, TimeUnit.SECONDS)
        if !exited then
          logger.debug("Process did not exit in time, destroying forcibly")
          process.destroyForcibly(): Unit
      catch
        case _: InterruptedException =>
          process.destroyForcibly(): Unit
      try stdoutReader.close()
      catch case _: Exception => ()
    else logger.debug("close() called on already-closed session — ignoring")

  /** Attempt to drain any remaining stderr for error context. Returns empty
    * string if nothing is available.
    */
  private def captureRemainingStderr(): String =
    val stderrReader =
      new BufferedReader(new InputStreamReader(process.getErrorStream))
    try
      Iterator
        .continually(stderrReader)
        .takeWhile(_.ready())
        .flatMap(r => Option(r.readLine()))
        .mkString("\n")
        .trim
    catch case _: Exception => ""
    finally
      try stderrReader.close()
      catch case _: Exception => ()

object SessionProcess:

  /** Starts a CLI process for the given options and returns a ready Session.
    *
    * The factory:
    *   1. Builds CLI args from SessionOptions
    *   2. Starts the process with stdin/stdout pipes
    *   3. Forks stderr capture in the background
    *   4. Reads initial stdout lines to extract the session_id from the init
    *      SystemMessage (up to a reasonable number of lines)
    *   5. Returns the constructed SessionProcess
    */
  def start(
      executablePath: String,
      options: SessionOptions
  )(using logger: Logger, ox: Ox): Session =
    // --verbose is required for stream-json output format but is not part of
    // the core SessionOptions CLI flags (which focus on session configuration).
    // It is added here at the process boundary, consistent with how query mode
    // adds --verbose in ClaudeCode.buildCliArguments.
    val args = "--verbose" :: CLIArgumentBuilder.buildSessionArgs(options)
    logger.info(
      s"Starting session process: $executablePath ${args.mkString(" ")}"
    )

    val processBuilder = configureSessionProcess(executablePath, args, options)
    val process = processBuilder.start()

    val stdinWriter = new BufferedWriter(
      new OutputStreamWriter(process.getOutputStream)
    )
    val stdoutReader = new BufferedReader(
      new InputStreamReader(process.getInputStream)
    )

    val _ = fork {
      captureStderr(process)
    }

    val session = new SessionProcess(process, stdinWriter, stdoutReader)

    // Attempt to read the init message from stdout to extract the session ID.
    // If the process is a long-lived session process, it will emit the init
    // message before waiting for stdin. If no init message arrives (or the
    // process exits quickly), session ID stays "pending".
    readInitMessages(process, stdoutReader).foreach { id =>
      session.currentSessionId.set(id)
    }

    session

  private val MaxInitLines = 20
  private val InitReadTimeoutMs = 500L // Wait up to 500ms for init message
  private val InitReadRetryDelayMs = 10L // Sleep between ready() checks

  /** Reads from stdout to find the session_id in the CLI's init message.
    *
    * Returns the extracted session ID if an init message is found within the
    * timeout, or None if the timeout expires, the process exits, or a non-init
    * message is encountered first.
    */
  private def readInitMessages(
      process: Process,
      reader: BufferedReader
  )(using logger: Logger): Option[String] =
    val deadline = System.currentTimeMillis() + InitReadTimeoutMs

    @tailrec
    def loop(linesRead: Int): Option[String] =
      if linesRead >= MaxInitLines || System.currentTimeMillis() >= deadline
      then None
      else if reader.ready() then
        Option(reader.readLine()) match
          case None       => None // EOF — process exited
          case Some(line) =>
            val lineNumber = linesRead + 1
            JsonParser.parseJsonLineWithContext(line, lineNumber) match
              case Right(Some(SystemMessage("init", data))) =>
                val sessionId = data.get("session_id").map(_.toString)
                sessionId.foreach { id =>
                  logger.info(s"Session ID extracted from init message: $id")
                }
                sessionId
              case Right(Some(_)) =>
                // Non-init message found before init — stop (init won't come later)
                None
              case _ =>
                // Empty/unparseable line — continue reading
                loop(lineNumber)
      else if !process.isAlive() then
        None // Process already exited — don't wait for init
      else
        Thread.sleep(InitReadRetryDelayMs) // Wait briefly for init message
        loop(linesRead)

    loop(0)

  private def configureSessionProcess(
      executablePath: String,
      args: List[String],
      options: SessionOptions
  ): ProcessBuilder =
    val pb = new ProcessBuilder((executablePath :: args).asJava)
    options.cwd.foreach { cwdPath =>
      pb.directory(new java.io.File(cwdPath))
    }
    val environment = pb.environment()
    options.inheritEnvironment match
      case Some(false) => environment.clear()
      case _           => ()
    options.environmentVariables.foreach { envVars =>
      envVars.foreach { case (k, v) => environment.put(k, v) }
    }
    pb

  private def captureStderr(process: Process)(using
      logger: Logger
  ): Unit =
    val reader =
      new BufferedReader(new InputStreamReader(process.getErrorStream))
    try
      Iterator
        .continually(reader.readLine())
        .takeWhile(_ != null) // scalafix:ok DisableSyntax.null
        .foreach(line => logger.debug(s"session stderr: $line"))
    catch
      case _: InterruptedException =>
        // Interrupted by scope cancellation — kill the process to release the pipe
        logger.debug("Stderr capture interrupted — destroying process")
        process.destroyForcibly(): Unit
      case _: Exception =>
        logger.debug("Session stderr stream closed")
    finally
      try reader.close()
      catch case _: Exception => ()
