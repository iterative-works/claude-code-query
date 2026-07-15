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
  SessionEndedBeforeRequest,
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
  * number of `events`/`stateChanges` consumers each see the full stream. Scope
  * finalizers close stdin and kill the process on exit.
  */
object SessionProcess:

  /** Default sliding-buffer capacity for the `events` and `stateChanges` Hubs.
    * The buffer is a stated contract parameter, not a tuning knob: `events` is
    * lossy for rendering only, so a conservative default is right.
    */
  val DefaultEventsBufferSize: Int = 1024

  private[cli] val InterruptTimeout = 30.seconds

  /** How many trailing stderr lines to retain for a death diagnostic. */
  private val StderrTailSize = 20

  /** How long `recordEnd` waits for the stderr reader to drain the dead
    * process's remaining lines before building the death error.
    */
  private val StderrDrainTimeout = 5.seconds

  /** The correlated effect handles the single reader fiber owns and the session
    * also reads: the message and state Hubs, the readable state, the captured
    * session id, the id-known and termination signals, the pending
    * control-request registry, and the alive flag. Bundled so a new piece of
    * reader state is one field, not another parameter on every private
    * function.
    */
  private[claude] final case class ReaderContext(
      eventsHub: Hub[Message],
      stateChangesHub: Hub[SessionState],
      stateRef: Ref[SessionState],
      sessionIdRef: Ref[Option[SessionId]],
      idKnown: Promise[Nothing, Unit],
      terminated: Promise[Nothing, SessionEnd],
      pendingRequests: Ref[Map[RequestId, Promise[CLIError, ControlResponse]]],
      aliveRef: Ref[Boolean],
      stderrTail: Ref[Vector[String]],
      stderrDrained: Promise[Nothing, Unit]
  ):
    /** The retained stderr tail rendered as one string for a death diagnostic.
      */
    def stderrText: UIO[String] = stderrTail.get.map(_.mkString("\n"))

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
      sessionIdRef <- Ref.make(Option.empty[SessionId])
      idKnown <- Promise.make[Nothing, Unit]
      terminated <- Promise.make[Nothing, SessionEnd]
      pendingRequests <-
        Ref.make(Map.empty[RequestId, Promise[CLIError, ControlResponse]])
      requestCounter <- Ref.make(0L)
      aliveRef <- Ref.make(true)
      stderrTail <- Ref.make(Vector.empty[String])
      stderrDrained <- Promise.make[Nothing, Unit]
      context = ReaderContext(
        eventsHub,
        stateChangesHub,
        stateRef,
        sessionIdRef,
        idKnown,
        terminated,
        pendingRequests,
        aliveRef,
        stderrTail,
        stderrDrained
      )
      process <- buildCommand(executablePath, args, options, stdinQueue).run
        .mapError(toSessionError(_, command))
      _ <- startReader(process, context, archiveHook).forkScoped
      _ <- captureStderr(process, context).forkScoped
      // Finalizers run in reverse registration order, so registering the kill
      // last makes it run first on teardown: killing the process makes the
      // pipes hit EOF, letting the forked reader finish and the stdin pump
      // stop promptly. stdinQueue.shutdown then releases the input stream.
      _ <- ZIO.addFinalizer(stdinQueue.shutdown)
      _ <- ZIO.addFinalizer(process.killForcibly.ignore)
    yield make(stdinQueue, context, requestCounter)

  /** Test seam: builds a Session from its backing primitives. */
  private[claude] def make(
      stdinQueue: Queue[Chunk[Byte]],
      context: ReaderContext,
      requestCounter: Ref[Long]
  ): Session =
    new SessionImpl(stdinQueue, context, requestCounter)

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
      context: ReaderContext,
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
      .mapZIO(message => handleMessage(message, context, archiveHook))
      .runDrain
      .catchAll(cause =>
        ZIO.logWarning(s"Session stdout reader stream failed: $cause")
      )
      .zipRight(recordEnd(process, context, archiveHook))

  private def handleMessage(
      message: Message,
      context: ReaderContext,
      archiveHook: SessionArchiveHook
  ): UIO[Unit] =
    for
      _ <- captureSessionId(message, context.sessionIdRef, context.idKnown)
      _ <- routeControlResponse(message, context.pendingRequests)
      _ <- foldState(message, context, archiveHook)
      _ <- context.eventsHub.publish(message)
    yield ()

  /** Captures the session id from the init message and from every result, so a
    * turn that carries a new id keeps `sessionIdRef` current. Signals `idKnown`
    * the first time an id appears.
    */
  private def captureSessionId(
      message: Message,
      sessionIdRef: Ref[Option[SessionId]],
      idKnown: Promise[Nothing, Unit]
  ): UIO[Unit] =
    message match
      case SystemMessage(_, data) =>
        // Any system message that carries a session_id names the session — the
        // startup-hook messages do so before the CLI has read any input, so the
        // id is known without waiting for a turn (the CLI emits `init` only
        // after the first user message).
        data.get("session_id").map(_.toString) match
          case Some(id) => setSessionId(SessionId(id), sessionIdRef, idKnown)
          case None     => ZIO.unit
      case result: ResultMessage =>
        setSessionId(result.sessionId, sessionIdRef, idKnown)
      case _ => ZIO.unit

  private def setSessionId(
      id: SessionId,
      sessionIdRef: Ref[Option[SessionId]],
      idKnown: Promise[Nothing, Unit]
  ): UIO[Unit] =
    sessionIdRef.get.flatMap:
      case Some(current) if current == id => ZIO.unit
      case _                              =>
        sessionIdRef.set(Some(id))
          *> idKnown.succeed(()).unit
          *> ZIO.logInfo(s"Session named: $id")

  private def routeControlResponse(
      message: Message,
      pendingRequests: Ref[Map[RequestId, Promise[CLIError, ControlResponse]]]
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
      context: ReaderContext,
      archiveHook: SessionArchiveHook
  ): UIO[Unit] =
    for
      transition <- context.stateRef.modify: old =>
        val next = SessionState.fold(old, message)
        ((old, next), next)
      (old, next) = transition
      _ <- ZIO.when(old != next)(context.stateChangesHub.publish(next).unit)
      _ <- message match
        case result: ResultMessage if result.origin.isEmpty =>
          context.sessionIdRef.get.flatMap:
            case Some(id) => archiveHook.afterResult(id)
            case None     => ZIO.unit
        case _ => ZIO.unit
    yield ()

  /** Records the session's end once the reader's stream completes: flips the
    * alive flag, folds the end into the state, resolves `terminated`, fails any
    * pending control requests, mirrors on close, and shuts both the events and
    * stateChanges Hubs so their streams complete.
    */
  private def recordEnd(
      process: Process,
      context: ReaderContext,
      archiveHook: SessionArchiveHook
  ): UIO[Unit] =
    for
      _ <- context.aliveRef.set(false)
      exit <- process.exitCode.either
      exitCode = exit.toOption.map(_.code)
      // Wait briefly for the stderr reader to drain the dead process's tail so
      // the death error can carry it; proceed with whatever is captured on
      // timeout rather than blocking end indefinitely.
      _ <- context.stderrDrained.await.timeout(StderrDrainTimeout)
      stderr <- context.stderrText
      error = exitCode.collect:
        case code if code != 0 => SessionProcessDied(Some(code), stderr)
      end = SessionEnd(exitCode, error)
      endState <- context.stateRef.updateAndGet(SessionState.ended(_, end))
      _ <- context.stateChangesHub.publish(endState)
      _ <- context.terminated.succeed(end)
      _ <- failPendingRequests(context.pendingRequests, error, exitCode, stderr)
      _ <- context.sessionIdRef.get.flatMap:
        case Some(id) => archiveHook.onClose(id)
        case None     => ZIO.unit
      _ <- context.eventsHub.shutdown
      _ <- context.stateChangesHub.shutdown
    yield ()

  private def failPendingRequests(
      pendingRequests: Ref[Map[RequestId, Promise[CLIError, ControlResponse]]],
      error: Option[CLIError],
      exitCode: Option[Int],
      stderr: String
  ): UIO[Unit] =
    pendingRequests
      .getAndSet(Map.empty)
      .flatMap: pending =>
        // A non-zero death carries its own error; a clean exit that still had a
        // request in flight ended before that request could complete — which is
        // not an unexpected death.
        val failure =
          error.getOrElse(SessionEndedBeforeRequest(exitCode, stderr))
        ZIO.foreachDiscard(pending.values)(_.fail(failure))

  /** Reads stderr, retaining the last [[StderrTailSize]] lines for a death
    * diagnostic and logging each at debug. Signals `stderrDrained` when the
    * stream ends so `recordEnd` can read a complete tail.
    */
  private def captureStderr(
      process: Process,
      context: ReaderContext
  ): UIO[Unit] =
    process.stderr.linesStream
      .foreach: line =>
        context.stderrTail.update(tail =>
          (tail :+ line).takeRight(StderrTailSize)
        ) *> ZIO.logDebug(s"session stderr: $line")
      .catchAll(cause =>
        ZIO.logWarning(s"Session stderr reader stream failed: $cause")
      )
      .ensuring(context.stderrDrained.succeed(()))

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

/** Session backed by a stdin queue, the reader's shared channels, and a request
  * counter.
  */
private final class SessionImpl(
    stdinQueue: Queue[Chunk[Byte]],
    context: SessionProcess.ReaderContext,
    requestCounter: Ref[Long]
) extends Session:

  // The reader's `terminated` promise is reached as `context.terminated` to
  // avoid clashing with this session's own `terminated` method.
  import context.{
    eventsHub,
    stateChangesHub,
    stateRef,
    sessionIdRef,
    idKnown,
    pendingRequests,
    aliveRef
  }

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
              val line =
                SessionStdin.userInputLine(
                  input,
                  idOpt.map(_.value).getOrElse("")
                )
              // Never log the user's text or context — only a structural summary.
              ZIO.logDebug(
                s"Writing user input to stdin (${UserInput.encode(input).size} blocks, " +
                  s"channel=${input.channel}, sessionIdKnown=${idOpt.isDefined})"
              ) *> offer(line)

  def interrupt: IO[CLIError, InterruptOutcome] =
    aliveRef.get.flatMap:
      case false => failProcessGone
      case true  =>
        for
          n <- requestCounter.updateAndGet(_ + 1)
          requestId = RequestId(s"req-$n")
          promise <- Promise.make[CLIError, ControlResponse]
          _ <- pendingRequests.update(_ + (requestId -> promise))
          // recordEnd flips `aliveRef` to false strictly before it drains the
          // pending registry. If the process died in the window between the
          // liveness gate above and this registration, that drain may already
          // have run and would never fail this promise — leaving `await` to
          // block until the interrupt timeout. Re-checking liveness after
          // registration closes the window: a dead session fails fast here, and
          // a still-live one is guaranteed to be drained (and thus failed) on
          // end because our promise is now in the registry.
          alive <- aliveRef.get
          request = ControlRequest(requestId, ControlRequestBody.Interrupt)
          outcome <-
            if !alive then
              pendingRequests.update(_ - requestId) *> failProcessGone
            else
              (offer(SessionStdin.controlRequestLine(request))
                *> promise.await.timeoutFail(
                  SessionProcess.interruptTimedOut
                )(SessionProcess.InterruptTimeout))
                .ensuring(pendingRequests.update(_ - requestId))
                .map(response => InterruptOutcome(stillQueued(response)))
        yield outcome

  def events: ZStream[Any, Nothing, Message] =
    ZStream.fromHub(eventsHub)

  def state: UIO[SessionState] = stateRef.get

  def stateChanges: ZStream[Any, Nothing, SessionState] =
    ZStream.fromHub(stateChangesHub)

  def terminated: IO[Nothing, SessionEnd] = context.terminated.await

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
                  case None      => awaitChange(subscription) *> loop
        loop

  /** Blocks until the state hub delivers a change. Session end shuts the hub
    * down, which interrupts a blocked take; that shutdown is itself the change
    * to observe, so it returns normally and lets the loop read the ended state.
    * A genuine caller interruption (the hub still live) is propagated.
    */
  private def awaitChange(
      subscription: Dequeue[SessionState]
  ): UIO[Unit] =
    subscription.take.unit.foldCauseZIO(
      cause =>
        subscription.isShutdown.flatMap:
          case true  => ZIO.unit
          case false => ZIO.refailCause(cause)
      ,
      _ => ZIO.unit
    )

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
    * interrupt the losing branch — an uninterruptible losing `await` would
    * wedge it forever. This is the same hazard commit 21594a3 fixed for the
    * init read.
    */
  private def requireSessionId: IO[CLIError, SessionId] =
    sessionIdRef.get.flatMap:
      case Some(id) => ZIO.succeed(id)
      case None     =>
        for
          _ <- idKnown.await
            .raceFirst(context.terminated.await.flatMap(failEnd))
            .interruptible
          id <- sessionIdRef.get.someOrElseZIO(failProcessGone)
        yield id

  private def failProcessGone: IO[CLIError, Nothing] =
    stateRef.get.flatMap: current =>
      current.ended match
        case Some(end) => failEnd(end)
        case None      =>
          context.stderrText.flatMap(s => ZIO.fail(SessionProcessDied(None, s)))

  private def failEnd(end: SessionEnd): IO[CLIError, Nothing] =
    end.error match
      case Some(err) => ZIO.fail(err)
      case None      =>
        context.stderrText.flatMap(s =>
          ZIO.fail(SessionProcessDied(end.exitCode, s))
        )

  private def stillQueued(response: ControlResponse): List[String] =
    response.payload.hcursor
      .get[List[String]]("still_queued")
      .toOption
      .getOrElse(Nil)
