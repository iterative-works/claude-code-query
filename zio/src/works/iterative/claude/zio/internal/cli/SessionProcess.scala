// PURPOSE: Scoped ZIO Session implementation backed by a long-lived CLI process
// PURPOSE: Handles session startup, multi-turn message routing, and deterministic process cleanup

package works.iterative.claude.zio.internal.cli

import zio.*
import zio.stream.ZStream
import zio.process.{Command, Process, ProcessInput, CommandError}
import java.nio.charset.StandardCharsets
import io.circe.syntax.*
import works.iterative.claude.core.{
  CLIError,
  ProcessExecutionError,
  SessionProcessDied
}
import works.iterative.claude.core.model.*
import works.iterative.claude.core.cli.CLIArgumentBuilder
import works.iterative.claude.zio.Session
import works.iterative.claude.zio.internal.parsing.JsonParser

/** Starts and manages a long-lived Claude Code CLI session process.
  *
  * The process is spawned once and kept alive for multiple turns. stdin is fed
  * from a queue (keeping the pipe open across sends); a background fiber reads
  * stdout lines into a message queue; another captures stderr for diagnostics.
  * Scope finalizers close stdin and kill the process on exit.
  */
object SessionProcess:

  private val InitReadTimeout = 1.second

  def start(
      executablePath: String,
      options: SessionOptions
  ): ZIO[Scope, CLIError, Session] =
    val args = "--verbose" :: CLIArgumentBuilder.buildSessionArgs(options)
    val command = executablePath :: args
    for
      stdinQueue <- Queue.unbounded[Chunk[Byte]]
      messageQueue <- Queue.unbounded[Option[Message]]
      sessionIdRef <- Ref.make("pending")
      aliveRef <- Ref.make(true)
      pendingErrorRef <- Ref.make(Option.empty[CLIError])
      process <- buildCommand(executablePath, args, options, stdinQueue).run
        .mapError(toSessionError(_, command))
      _ <- startStdoutReader(
        process,
        messageQueue,
        sessionIdRef,
        aliveRef,
        pendingErrorRef
      ).forkScoped
      _ <- captureStderr(process).forkScoped
      // Finalizers run in reverse registration order, so registering the kill
      // last makes it run first on teardown: killing the process makes the
      // pipes hit EOF, letting the forked readers finish and the stdin pump
      // stop promptly. stdinQueue.shutdown then releases the input stream.
      _ <- ZIO.addFinalizer(stdinQueue.shutdown)
      _ <- ZIO.addFinalizer(process.killForcibly.ignore)
      _ <- readInitMessage(messageQueue, sessionIdRef)
    yield make(
      stdinQueue,
      sessionIdRef,
      messageQueue,
      aliveRef,
      pendingErrorRef
    )

  /** Test seam: builds a Session from its backing queues and refs. */
  private[claude] def make(
      stdinQueue: Queue[Chunk[Byte]],
      sessionIdRef: Ref[String],
      messageQueue: Queue[Option[Message]],
      aliveRef: Ref[Boolean],
      pendingErrorRef: Ref[Option[CLIError]]
  ): Session =
    new SessionImpl(
      stdinQueue,
      sessionIdRef,
      messageQueue,
      aliveRef,
      pendingErrorRef
    )

  private def buildCommand(
      executablePath: String,
      args: List[String],
      options: SessionOptions,
      stdinQueue: Queue[Chunk[Byte]]
  ): Command =
    ProcessManager
      .baseCommand(
        executablePath,
        args,
        options.inheritEnvironment,
        options.environmentVariables,
        options.cwd
      )
      .stdin(ProcessInput.fromQueue(stdinQueue))

  /** Background fiber: reads stdout lines, parses into messages, and enqueues
    * them. Malformed lines are logged and skipped. When stdout completes the
    * process is dead: a non-zero exit records a SessionProcessDied in
    * pendingErrorRef. Always offers None to signal EOF.
    */
  private def startStdoutReader(
      process: Process,
      messageQueue: Queue[Option[Message]],
      sessionIdRef: Ref[String],
      aliveRef: Ref[Boolean],
      pendingErrorRef: Ref[Option[CLIError]]
  ): UIO[Unit] =
    process.stdout.linesStream.zipWithIndex
      .mapZIO: (line, index) =>
        JsonParser
          .parseJsonLineWithContext(line, index.toInt + 1)
          .either
          .flatMap:
            case Left(error) =>
              ZIO
                .logWarning(s"Malformed JSON line skipped: ${error.message}")
                .as(Option.empty[Message])
            case Right(None) => ZIO.succeed(Option.empty[Message])
            case Right(Some(result: ResultMessage)) =>
              sessionIdRef.set(result.sessionId).as(Some(result))
            case Right(Some(message)) => ZIO.succeed(Some(message))
      .collect { case Some(message) => message }
      .mapZIO(message => messageQueue.offer(Some(message)))
      .runDrain
      .catchAll(_ => ZIO.unit)
      .zipRight(recordProcessDeath(process, aliveRef, pendingErrorRef))
      .ensuring(messageQueue.offer(None).unit)

  private def recordProcessDeath(
      process: Process,
      aliveRef: Ref[Boolean],
      pendingErrorRef: Ref[Option[CLIError]]
  ): UIO[Unit] =
    aliveRef.set(false) *> process.exitCode.either.flatMap:
      case Right(code) if code.code != 0 =>
        pendingErrorRef.set(Some(SessionProcessDied(Some(code.code), "")))
      case _ => ZIO.unit

  /** Waits up to InitReadTimeout for the first message. If it is the init
    * SystemMessage, extracts the session ID; otherwise re-enqueues it so it
    * appears in the user's stream. On timeout the session ID stays "pending".
    *
    * The wait is forced `interruptible`: `timeout` completes by interrupting
    * the losing `take` when the sleep wins, so it can only fire if the `take`
    * is interruptible. A caller may run `start` inside an uninterruptible
    * region (for example a forked request handler that inherits its parent's
    * interrupt status); there an uninterruptible `take` could never be
    * interrupted, the timeout would never fire, and a slow CLI init would hang
    * forever.
    */
  private[claude] def readInitMessage(
      messageQueue: Queue[Option[Message]],
      sessionIdRef: Ref[String]
  ): UIO[Unit] =
    messageQueue.take
      .timeout(InitReadTimeout)
      .interruptible
      .flatMap:
        case None       => ZIO.unit
        case Some(None) => messageQueue.offer(None).unit
        case Some(Some(SystemMessage("init", data))) =>
          data.get("session_id").map(_.toString) match
            case Some(id) =>
              ZIO.logInfo(s"Session ID extracted from init message: $id")
                *> sessionIdRef.set(id)
            case None => ZIO.unit
        case Some(Some(message)) =>
          messageQueue.offer(Some(message)).unit

  private def captureStderr(process: Process): UIO[Unit] =
    process.stderr.linesStream
      .foreach(line => ZIO.logDebug(s"session stderr: $line"))
      .catchAll(_ => ZIO.unit)

  private def toSessionError(
      error: CommandError,
      command: List[String]
  ): CLIError =
    val exitCode = error match
      case CommandError.NonZeroErrorCode(code) => code.code
      case _                                   => -1
    ProcessExecutionError(exitCode, error.getMessage, command)

/** Session backed by a stdin queue and a shared message queue. */
private final class SessionImpl(
    stdinQueue: Queue[Chunk[Byte]],
    sessionIdRef: Ref[String],
    messageQueue: Queue[Option[Message]],
    aliveRef: Ref[Boolean],
    pendingErrorRef: Ref[Option[CLIError]]
) extends Session:

  def sessionId: UIO[String] = sessionIdRef.get

  def send(prompt: String): IO[CLIError, Unit] =
    aliveRef.get.flatMap:
      case false => failProcessGone
      case true  =>
        // A shut-down stdin queue means the session has been released or the
        // process has gone; offering to it would interrupt the caller's fiber.
        // Surface the recorded death as a typed error instead.
        stdinQueue.isShutdown.flatMap:
          case true  => failProcessGone
          case false =>
            sessionIdRef.get.flatMap: currentId =>
              val message =
                SDKUserMessage(content = prompt, sessionId = currentId)
              val json = message.asJson.noSpaces + "\n"
              ZIO.logDebug(
                s"Writing SDKUserMessage to stdin: ${message.asJson.noSpaces}"
              ) *> stdinQueue
                .offer(Chunk.fromArray(json.getBytes(StandardCharsets.UTF_8)))
                .unit

  /** Fails with the recorded death error, or a generic [[SessionProcessDied]]
    * when the process is gone but no specific error was captured.
    */
  private def failProcessGone: IO[CLIError, Nothing] =
    pendingErrorRef.get.flatMap:
      case Some(error) => ZIO.fail(error)
      case None        => ZIO.fail(SessionProcessDied(None, ""))

  def stream: ZStream[Any, CLIError, Message] =
    ZStream.unwrap:
      Ref
        .make(false)
        .map: resultSeenRef =>
          // Pull one element at a time: the message queue is shared and
          // long-lived across turns, so a larger chunk would let takeUntil
          // over-pull and discard messages buffered after this turn's
          // ResultMessage instead of leaving them for the next turn's stream.
          val body = ZStream
            .fromQueue(messageQueue, maxChunkSize = 1)
            .collectWhile { case Some(message) => message }
            .tap:
              case _: ResultMessage => resultSeenRef.set(true)
              case _                => ZIO.unit
            .takeUntil(_.isInstanceOf[ResultMessage])
          // After the turn ends without a ResultMessage (the queue signalled
          // EOF because the process died mid-turn), surface the recorded death
          // error. A consumer that abandons the stream early instead observes
          // the error on its next `send`, which checks the same refs.
          val check = ZStream.fromZIO:
            resultSeenRef.get.flatMap:
              case true  => ZIO.unit
              case false =>
                pendingErrorRef.get.flatMap:
                  case Some(error) => ZIO.fail(error)
                  case None        => ZIO.unit
          body ++ check.drain
