// PURPOSE: Internal implementation of Session backed by a long-lived CLI process
// PURPOSE: Manages stdin writing, stdout streaming, stderr capture, and process lifecycle
package works.iterative.claude.direct.internal.cli

import ox.*
import ox.flow.Flow
import works.iterative.claude.core.model.*
import works.iterative.claude.core.cli.CLIArgumentBuilder
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
import java.util.concurrent.atomic.AtomicReference
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

  private val currentSessionId = new AtomicReference[String]("pending")

  def sessionId: String = currentSessionId.get()

  def send(prompt: String): Flow[Message] =
    val msg =
      SDKUserMessage(content = prompt, sessionId = currentSessionId.get())
    val json = msg.asJson.noSpaces
    logger.debug(s"Writing SDKUserMessage to stdin: $json")
    stdinWriter.write(json)
    stdinWriter.newLine()
    stdinWriter.flush()

    Flow.usingEmit { emit =>
      var done = false
      var lineNumber = 0
      while !done do
        val line = stdoutReader.readLine()
        if line == null then
          logger.debug("stdout EOF reached during send")
          done = true
        else
          lineNumber += 1
          JsonParser.parseJsonLineWithContextWithLogging(line, lineNumber) match
            case Right(Some(message)) =>
              logger.debug(
                s"Emitting message: ${message.getClass.getSimpleName}"
              )
              emit(message)
              message match
                case result: ResultMessage =>
                  currentSessionId.set(result.sessionId)
                  done = true
                case _ => ()
            case Right(None) => ()
            case Left(error) =>
              logger.error(s"JSON parsing failed: ${error.message}")
    }

  def close(): Unit =
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

    val stderrBuffer = new StringBuilder
    val _ = fork {
      captureStderr(process, stderrBuffer)
    }

    val session = new SessionProcess(process, stdinWriter, stdoutReader)

    // Attempt to read the init message from stdout to extract the session ID.
    // If the process is a long-lived session process, it will emit the init
    // message before waiting for stdin. If no init message arrives (or the
    // process exits quickly), session ID stays "pending".
    readInitMessages(process, stdoutReader, session)

    session

  private val MaxInitLines = 20
  private val InitReadTimeoutMs = 500L // Wait up to 500ms for init message
  private val InitReadRetryDelayMs = 10L // Sleep between ready() checks

  /** Reads from stdout to extract the session_id from the CLI's init message.
    *
    * Waits up to InitReadTimeoutMs for the process to emit an init message.
    * Only reads if the process is still alive (to avoid consuming messages from
    * quick-exit mock scripts used in tests). Stops as soon as the init message
    * is found, a non-init message is encountered, or the timeout expires.
    *
    * If no init message is found, the session ID remains "pending" and will be
    * updated from the first ResultMessage when `send` is called.
    */
  private def readInitMessages(
      process: Process,
      reader: BufferedReader,
      session: SessionProcess
  )(using logger: Logger): Unit =
    val deadline = System.currentTimeMillis() + InitReadTimeoutMs
    var linesRead = 0
    var done = false
    while !done && linesRead < MaxInitLines && System
        .currentTimeMillis() < deadline
    do
      if reader.ready() then
        val line = reader.readLine()
        if line != null then
          linesRead += 1
          JsonParser.parseJsonLineWithContext(line, linesRead) match
            case Right(Some(SystemMessage("init", data))) =>
              data.get("session_id").map(_.toString).foreach { id =>
                logger.info(s"Session ID extracted from init message: $id")
                session.currentSessionId.set(id)
              }
              done = true
            case Right(Some(_)) =>
              // Non-init message found before init — stop (init won't come later)
              done = true
            case _ =>
              () // Empty/unparseable line — continue reading
        else done = true // EOF — process exited
      else if !process.isAlive() then
        done = true // Process already exited — don't wait for init
      else Thread.sleep(InitReadRetryDelayMs) // Wait briefly for init message

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

  private def captureStderr(process: Process, buffer: StringBuilder)(using
      logger: Logger
  ): Unit =
    val reader =
      new BufferedReader(new InputStreamReader(process.getErrorStream))
    try
      var line: String = null
      while { line = reader.readLine(); line != null } do
        logger.debug(s"session stderr: $line")
        buffer.synchronized {
          if buffer.nonEmpty then buffer.append('\n'): Unit
          buffer.append(line): Unit
        }
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
