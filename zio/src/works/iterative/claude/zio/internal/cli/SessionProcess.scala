// PURPOSE: Scoped ZIO Session backed by a long-lived CLI process with one internal reader fanning out a Hub
// PURPOSE: Folds results into monotone state, correlates control requests, and cleans the process up deterministically

package works.iterative.claude.zio.internal.cli

import zio.*
import zio.stream.ZStream
import zio.process.{Command, Process, ProcessInput, CommandError}
import java.nio.charset.StandardCharsets
import works.iterative.claude.core.{
  CLIError,
  ProcessExecutionError,
  ProcessTimeoutError,
  SessionProcessDied
}
import works.iterative.claude.core.model.*
import works.iterative.claude.core.cli.CLIArgumentBuilder
import works.iterative.claude.zio.Session
import works.iterative.claude.zio.internal.parsing.JsonParser

/** Starts and manages a long-lived Claude Code CLI session process.
  *
  * The process is spawned once and kept alive for the whole session. stdin is
  * fed from a queue (keeping the pipe open across sends). ONE internal reader
  * fiber owns the stdout stream: it parses each line, captures the session id,
  * routes control responses to their waiting request, folds results into the
  * readable [[SessionState]], and fans every message out over a Hub — so any
  * number of `events`/`stateChanges` consumers see the full stream, resolving
  * the old exactly-one-consumer constraint by construction. Scope finalizers
  * close stdin and kill the process on exit.
  */
object SessionProcess:

  /** Default sliding-buffer capacity for the `events` and `stateChanges` Hubs.
    * The buffer is a stated contract parameter, not a tuning knob: `events` is
    * lossy for rendering only, so a conservative default is right.
    */
  val DefaultEventsBufferSize: Int = 1024

  private[cli] val InterruptTimeout = 30.seconds

  def start(
      executablePath: String,
      options: SessionOptions,
      archiveHook: SessionArchiveHook = SessionArchiveHook.none,
      eventsBufferSize: Int = DefaultEventsBufferSize
  ): ZIO[Scope, CLIError, Session] =
    val args = "--verbose" :: CLIArgumentBuilder.buildSessionArgs(options)
    val command = executablePath :: args
    for
      stdinQueue <- Queue.unbounded[Chunk[Byte]]
      eventsHub <- Hub.sliding[Message](eventsBufferSize)
      stateChangesHub <- Hub.sliding[SessionState](eventsBufferSize)
      stateRef <- Ref.make(SessionState.initial)
      sessionIdRef <- Ref.make(Option.empty[String])
      idKnown <- Promise.make[Nothing, Unit]
      terminated <- Promise.make[Nothing, SessionEnd]
      pendingRequests <-
        Ref.make(Map.empty[String, Promise[CLIError, ControlResponse]])
      requestCounter <- Ref.make(0L)
      aliveRef <- Ref.make(true)
      process <- buildCommand(executablePath, args, options, stdinQueue).run
        .mapError(toSessionError(_, command))
      _ <- startReader(
        process,
        eventsHub,
        stateChangesHub,
        stateRef,
        sessionIdRef,
        idKnown,
        terminated,
        pendingRequests,
        aliveRef,
        archiveHook
      ).forkScoped
      _ <- captureStderr(process).forkScoped
      // Finalizers run in reverse registration order, so registering the kill
      // last makes it run first on teardown: killing the process makes the
      // pipes hit EOF, letting the forked reader finish and the stdin pump
      // stop promptly. stdinQueue.shutdown then releases the input stream.
      _ <- ZIO.addFinalizer(stdinQueue.shutdown)
      _ <- ZIO.addFinalizer(process.killForcibly.ignore)
    yield make(
      stdinQueue,
      eventsHub,
      stateChangesHub,
      stateRef,
      sessionIdRef,
      idKnown,
      terminated,
      pendingRequests,
      requestCounter,
      aliveRef
    )

  /** Test seam: builds a Session from its backing primitives. */
  private[claude] def make(
      stdinQueue: Queue[Chunk[Byte]],
      eventsHub: Hub[Message],
      stateChangesHub: Hub[SessionState],
      stateRef: Ref[SessionState],
      sessionIdRef: Ref[Option[String]],
      idKnown: Promise[Nothing, Unit],
      terminated: Promise[Nothing, SessionEnd],
      pendingRequests: Ref[Map[String, Promise[CLIError, ControlResponse]]],
      requestCounter: Ref[Long],
      aliveRef: Ref[Boolean]
  ): Session =
    new SessionImpl(
      stdinQueue,
      eventsHub,
      stateChangesHub,
      stateRef,
      sessionIdRef,
      idKnown,
      terminated,
      pendingRequests,
      requestCounter,
      aliveRef
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

  /** The single internal reader. Parses each stdout line, handles it, and on
    * stream completion records the session's end. Malformed lines are logged
    * and skipped.
    */
  private def startReader(
      process: Process,
      eventsHub: Hub[Message],
      stateChangesHub: Hub[SessionState],
      stateRef: Ref[SessionState],
      sessionIdRef: Ref[Option[String]],
      idKnown: Promise[Nothing, Unit],
      terminated: Promise[Nothing, SessionEnd],
      pendingRequests: Ref[Map[String, Promise[CLIError, ControlResponse]]],
      aliveRef: Ref[Boolean],
      archiveHook: SessionArchiveHook
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
            case Right(message) => ZIO.succeed(message)
      .collect { case Some(message) => message }
      .mapZIO: message =>
        handleMessage(
          message,
          eventsHub,
          stateChangesHub,
          stateRef,
          sessionIdRef,
          idKnown,
          pendingRequests,
          archiveHook
        )
      .runDrain
      .catchAll(_ => ZIO.unit)
      .zipRight(
        recordEnd(
          process,
          stateChangesHub,
          stateRef,
          sessionIdRef,
          terminated,
          pendingRequests,
          aliveRef,
          eventsHub,
          archiveHook
        )
      )

  private def handleMessage(
      message: Message,
      eventsHub: Hub[Message],
      stateChangesHub: Hub[SessionState],
      stateRef: Ref[SessionState],
      sessionIdRef: Ref[Option[String]],
      idKnown: Promise[Nothing, Unit],
      pendingRequests: Ref[Map[String, Promise[CLIError, ControlResponse]]],
      archiveHook: SessionArchiveHook
  ): UIO[Unit] =
    for
      _ <- captureSessionId(message, sessionIdRef, idKnown)
      _ <- routeControlResponse(message, pendingRequests)
      _ <- foldState(
        message,
        stateChangesHub,
        stateRef,
        sessionIdRef,
        archiveHook
      )
      _ <- eventsHub.publish(message)
    yield ()

  /** Captures the session id from the init message and from every result, so a
    * turn that carries a new id keeps `sessionIdRef` current. Signals `idKnown`
    * the first time an id appears.
    */
  private def captureSessionId(
      message: Message,
      sessionIdRef: Ref[Option[String]],
      idKnown: Promise[Nothing, Unit]
  ): UIO[Unit] =
    message match
      case SystemMessage(_, data) =>
        // Any system message that carries a session_id names the session — the
        // startup-hook messages do so before the CLI has read any input, so the
        // id is known without waiting for a turn (the CLI emits `init` only
        // after the first user message).
        data.get("session_id").map(_.toString) match
          case Some(id) => setSessionId(id, sessionIdRef, idKnown)
          case None     => ZIO.unit
      case result: ResultMessage =>
        setSessionId(result.sessionId, sessionIdRef, idKnown)
      case _ => ZIO.unit

  private def setSessionId(
      id: String,
      sessionIdRef: Ref[Option[String]],
      idKnown: Promise[Nothing, Unit]
  ): UIO[Unit] =
    sessionIdRef.get.flatMap:
      case Some(current) if current == id => ZIO.unit
      case _ =>
        sessionIdRef.set(Some(id))
          *> idKnown.succeed(()).unit
          *> ZIO.logInfo(s"Session named: $id")

  private def routeControlResponse(
      message: Message,
      pendingRequests: Ref[Map[String, Promise[CLIError, ControlResponse]]]
  ): UIO[Unit] =
    message match
      case response: ControlResponse =>
        pendingRequests
          .modify(m => (m.get(response.requestId), m - response.requestId))
          .flatMap:
            case Some(promise) => promise.succeed(response).unit
            case None          => ZIO.unit
      case _ => ZIO.unit

  /** Folds the message into the readable state, publishing a change and firing
    * the archive hook after a real (origin-absent) result.
    */
  private def foldState(
      message: Message,
      stateChangesHub: Hub[SessionState],
      stateRef: Ref[SessionState],
      sessionIdRef: Ref[Option[String]],
      archiveHook: SessionArchiveHook
  ): UIO[Unit] =
    for
      transition <- stateRef.modify: old =>
        val next = SessionState.fold(old, message)
        ((old, next), next)
      (old, next) = transition
      _ <- ZIO.when(old != next)(stateChangesHub.publish(next).unit)
      _ <- message match
        case result: ResultMessage if result.origin.isEmpty =>
          sessionIdRef.get.flatMap:
            case Some(id) => archiveHook.afterResult(id)
            case None     => ZIO.unit
        case _ => ZIO.unit
    yield ()

  /** Records the session's end once the reader's stream completes: flips the
    * alive flag, folds the end into the state, resolves `terminated`, fails any
    * pending control requests, mirrors on close, and shuts the events Hub so
    * `events` streams complete.
    */
  private def recordEnd(
      process: Process,
      stateChangesHub: Hub[SessionState],
      stateRef: Ref[SessionState],
      sessionIdRef: Ref[Option[String]],
      terminated: Promise[Nothing, SessionEnd],
      pendingRequests: Ref[Map[String, Promise[CLIError, ControlResponse]]],
      aliveRef: Ref[Boolean],
      eventsHub: Hub[Message],
      archiveHook: SessionArchiveHook
  ): UIO[Unit] =
    for
      _ <- aliveRef.set(false)
      exit <- process.exitCode.either
      exitCode = exit.toOption.map(_.code)
      error = exitCode.collect:
        case code if code != 0 => SessionProcessDied(Some(code), "")
      end = SessionEnd(exitCode, error)
      endState <- stateRef.updateAndGet(SessionState.ended(_, end))
      _ <- stateChangesHub.publish(endState)
      _ <- terminated.succeed(end)
      _ <- failPendingRequests(pendingRequests, error, exitCode)
      _ <- sessionIdRef.get.flatMap:
        case Some(id) => archiveHook.onClose(id)
        case None     => ZIO.unit
      _ <- eventsHub.shutdown
    yield ()

  private def failPendingRequests(
      pendingRequests: Ref[Map[String, Promise[CLIError, ControlResponse]]],
      error: Option[CLIError],
      exitCode: Option[Int]
  ): UIO[Unit] =
    pendingRequests
      .getAndSet(Map.empty)
      .flatMap: pending =>
        val failure = error.getOrElse(SessionProcessDied(exitCode, ""))
        ZIO.foreachDiscard(pending.values)(_.fail(failure))

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

  private[cli] def interruptTimedOut: CLIError =
    ProcessTimeoutError(
      scala.concurrent.duration.FiniteDuration(
        InterruptTimeout.toMillis,
        java.util.concurrent.TimeUnit.MILLISECONDS
      ),
      List("control_request", "interrupt")
    )

/** Session backed by a stdin queue, a message Hub, and readable state. */
private final class SessionImpl(
    stdinQueue: Queue[Chunk[Byte]],
    eventsHub: Hub[Message],
    stateChangesHub: Hub[SessionState],
    stateRef: Ref[SessionState],
    sessionIdRef: Ref[Option[String]],
    idKnown: Promise[Nothing, Unit],
    terminatedPromise: Promise[Nothing, SessionEnd],
    pendingRequests: Ref[Map[String, Promise[CLIError, ControlResponse]]],
    requestCounter: Ref[Long],
    aliveRef: Ref[Boolean]
) extends Session:

  def info: IO[CLIError, SessionInfo] =
    requireSessionId.map(SessionInfo(_))

  def send(input: UserInput): IO[CLIError, Unit] =
    aliveRef.get.flatMap:
      case false => failProcessGone
      case true  =>
        // A shut-down stdin queue means the session has been released or the
        // process has gone; offering to it would interrupt the caller's fiber.
        // Surface the recorded death as a typed error instead.
        stdinQueue.isShutdown.flatMap:
          case true  => failProcessGone
          case false =>
            // Fire-and-forget: never block on the session id. The CLI emits its
            // `init` (which names the session) only AFTER it reads the first
            // user message, so a send that waited for the id would deadlock the
            // first turn. The session_id field on the wire is not load-bearing —
            // the process IS the session — so an empty value is accepted until
            // the id is known.
            sessionIdRef.get.flatMap: idOpt =>
              val line = SessionStdin.userInputLine(input, idOpt.getOrElse(""))
              ZIO.logDebug(s"Writing user input to stdin: ${line.trim}")
                *> offer(line)

  def interrupt: IO[CLIError, InterruptOutcome] =
    aliveRef.get.flatMap:
      case false => failProcessGone
      case true  =>
        for
          n <- requestCounter.updateAndGet(_ + 1)
          requestId = s"req-$n"
          promise <- Promise.make[CLIError, ControlResponse]
          _ <- pendingRequests.update(_ + (requestId -> promise))
          request = ControlRequest(requestId, ControlRequestBody.Interrupt)
          response <-
            (offer(SessionStdin.controlRequestLine(request))
              *> promise.await.timeoutFail(
                SessionProcess.interruptTimedOut
              )(SessionProcess.InterruptTimeout))
              .ensuring(pendingRequests.update(_ - requestId))
        yield InterruptOutcome(stillQueued(response))

  def events: ZStream[Any, Nothing, Message] =
    ZStream.fromHub(eventsHub)

  def state: UIO[SessionState] = stateRef.get

  def stateChanges: ZStream[Any, Nothing, SessionState] =
    ZStream.fromHub(stateChangesHub)

  def terminated: IO[Nothing, SessionEnd] = terminatedPromise.await

  def awaitResultAfter(seen: Long): IO[CLIError, ResultMessage] =
    ZIO.scoped:
      stateChangesHub.subscribe.flatMap: subscription =>
        def loop: IO[CLIError, ResultMessage] =
          stateRef.get.flatMap: current =>
            current.lastResult match
              case Some(result) if current.resultsSeen > seen =>
                ZIO.succeed(result)
              case _ =>
                current.ended match
                  case Some(end) => failEnd(end)
                  case None      => subscription.take *> loop
        loop

  private def offer(line: String): UIO[Unit] =
    stdinQueue
      .offer(Chunk.fromArray(line.getBytes(StandardCharsets.UTF_8)))
      .unit

  /** Resolves the session id, waiting for the CLI to name the session if it has
    * not yet. The wait races `terminated` so a process that dies before naming
    * the session fails with the recorded error rather than hanging.
    *
    * The race is forced `interruptible`: a caller may run within an
    * uninterruptible region (e.g. a forked request handler that inherits its
    * parent's interrupt status), and `raceFirst` completes only once it can
    * interrupt the losing branch — an uninterruptible losing `await` would wedge
    * it forever. This is the same hazard commit 21594a3 fixed for the init read.
    */
  private def requireSessionId: IO[CLIError, String] =
    sessionIdRef.get.flatMap:
      case Some(id) => ZIO.succeed(id)
      case None     =>
        idKnown.await
          .raceFirst(terminatedPromise.await.flatMap(failEnd))
          .interruptible
        *> sessionIdRef.get.flatMap:
          case Some(id) => ZIO.succeed(id)
          case None     => failProcessGone

  private def failProcessGone: IO[CLIError, Nothing] =
    stateRef.get.flatMap: current =>
      current.ended match
        case Some(end) => failEnd(end)
        case None      => ZIO.fail(SessionProcessDied(None, ""))

  private def failEnd(end: SessionEnd): IO[CLIError, Nothing] =
    ZIO.fail(end.error.getOrElse(SessionProcessDied(end.exitCode, "")))

  private def stillQueued(response: ControlResponse): List[String] =
    response.payload.hcursor
      .get[List[String]]("still_queued")
      .toOption
      .getOrElse(Nil)
