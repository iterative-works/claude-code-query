// PURPOSE: Resource-based effectful Session implementation backed by a long-lived CLI process
// PURPOSE: Handles session startup, multi-turn message routing, and deterministic process cleanup
package works.iterative.claude.effectful.internal.cli

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Queue
import fs2.{Chunk, Stream}
import scala.concurrent.duration.*
import fs2.io.process.ProcessBuilder
import fs2.io.file.Path
import io.circe.syntax.*
import org.typelevel.log4cats.Logger
import works.iterative.claude.core.{CLIError, SessionProcessDied}
import works.iterative.claude.core.model.*
import works.iterative.claude.core.cli.CLIArgumentBuilder
import works.iterative.claude.effectful.Session
import works.iterative.claude.effectful.internal.parsing.JsonParser

/** Starts and manages a long-lived Claude Code CLI session process.
  *
  * The process is spawned once and kept alive for multiple turns. A background
  * fiber writes stdin from a queue (keeping the pipe open across sends); a
  * second fiber continuously reads stdout lines into a message queue; a third
  * fiber captures stderr for diagnostics. Cleanup is handled by the Resource
  * finalizer.
  */
object SessionProcess:

  def start(
      executablePath: String,
      options: SessionOptions
  )(using logger: Logger[IO]): Resource[IO, Session] =
    val args = "--verbose" :: CLIArgumentBuilder.buildSessionArgs(options)

    for
      process <- buildProcess(executablePath, args, options).spawn[IO]
      // stdin queue: Some(chunk) = data, None = close signal
      stdinQueue <- Resource.eval(Queue.unbounded[IO, Option[Chunk[Byte]]])
      // message queue: Some(msg) = message, None = stdout EOF
      messageQueue <- Resource.eval(Queue.unbounded[IO, Option[Message]])
      sessionIdRef <- Resource.eval(Ref.of[IO, String]("pending"))
      // alive ref: set to false when the process dies
      aliveRef <- Resource.eval(Ref.of[IO, Boolean](true))
      // pendingError ref: carries process-death error from stdout reader to stream
      pendingErrorRef <- Resource.eval(
        Ref.of[IO, Option[CLIError]](None)
      )
      // Background fiber: drains stdinQueue into process.stdin
      _ <- Resource.make(
        startStdinWriter(process, stdinQueue).start
      )(_.cancel)
      // Background fiber: reads process.stdout into messageQueue
      _ <- Resource.make(
        startStdoutReader(
          process,
          messageQueue,
          sessionIdRef,
          aliveRef,
          pendingErrorRef
        ).start
      )(_.cancel)
      // Background fiber: captures stderr for diagnostics
      _ <- Resource.make(
        captureStderr(process).start
      )(_.cancel)
      // Read init message to prime sessionId before returning the session
      _ <- Resource.eval(readInitMessage(messageQueue, sessionIdRef))
    yield new SessionImpl(
      stdinQueue,
      sessionIdRef,
      messageQueue,
      aliveRef,
      pendingErrorRef
    )

  private def buildProcess(
      executablePath: String,
      args: List[String],
      options: SessionOptions
  ): ProcessBuilder =
    val base = ProcessBuilder(executablePath, args)
      .withInheritEnv(options.inheritEnvironment.getOrElse(true))

    val withEnv = options.environmentVariables.fold(base)(envVars =>
      base.withExtraEnv(envVars)
    )

    options.cwd.fold(withEnv)(cwd => withEnv.withWorkingDirectory(Path(cwd)))

  /** Background fiber: reads from stdinQueue and writes to process.stdin.
    * Terminates when None is dequeued (closes the pipe).
    */
  private def startStdinWriter(
      process: fs2.io.process.Process[IO],
      stdinQueue: Queue[IO, Option[Chunk[Byte]]]
  ): IO[Unit] =
    Stream
      .fromQueueNoneTerminated(stdinQueue)
      .flatMap(Stream.chunk)
      .through(process.stdin)
      .compile
      .drain

  /** Background fiber: reads stdout lines, parses into messages, and enqueues
    * them. Malformed JSON lines are logged and skipped. When stdout completes,
    * checks the process exit code: non-zero exit sets aliveRef to false and
    * stores a SessionProcessDied in pendingErrorRef. Always offers None to
    * signal EOF.
    */
  private def startStdoutReader(
      process: fs2.io.process.Process[IO],
      messageQueue: Queue[IO, Option[Message]],
      sessionIdRef: Ref[IO, String],
      aliveRef: Ref[IO, Boolean],
      pendingErrorRef: Ref[IO, Option[CLIError]]
  )(using logger: Logger[IO]): IO[Unit] =
    process.stdout
      .through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
      .zipWithIndex
      .evalMap { case (line, idx) =>
        JsonParser.parseJsonLineWithContext(line, idx.toInt + 1, logger)
      }
      .evalMap {
        case Left(err) =>
          // Malformed JSON: log and skip rather than terminating the reader
          logger
            .warn(s"Malformed JSON line skipped: ${err.message}")
            .as(
              None: Option[Message]
            )
        case Right(None)      => IO.pure(None: Option[Message])
        case Right(Some(msg)) =>
          msg match
            case result: ResultMessage =>
              sessionIdRef.set(result.sessionId).as(Some(msg): Option[Message])
            case _ => IO.pure(Some(msg): Option[Message])
      }
      .unNone
      .evalMap(msg => messageQueue.offer(Some(msg)))
      .compile
      .drain
      .flatMap { _ =>
        // When stdout stream completes, the process is dead regardless of exit code
        aliveRef.set(false) *>
          IO(process.exitValue).flatten.attempt.flatMap {
            case Right(code) if code != 0 =>
              pendingErrorRef.set(
                Some(SessionProcessDied(Some(code), ""))
              )
            case _ => IO.unit
          }
      }
      .guarantee(messageQueue.offer(None))

  private val InitReadTimeout = 1.second

  /** Waits for the first message from the stdout reader and checks if it's the
    * init SystemMessage. If so, extracts the session ID. Otherwise, re-enqueues
    * the message so it appears in the user's stream.
    *
    * Waits up to InitReadTimeout. If no message arrives within the timeout, the
    * session ID stays "pending" (the init message either was not emitted or has
    * not yet been buffered by the stdout reader fiber).
    */
  private def readInitMessage(
      messageQueue: Queue[IO, Option[Message]],
      sessionIdRef: Ref[IO, String]
  )(using logger: Logger[IO]): IO[Unit] =
    // Wait for the first message with a timeout using race
    IO.race(
      messageQueue.take,
      IO.sleep(InitReadTimeout)
    ).flatMap {
      case Left(None) =>
        // Stdout closed before any message
        messageQueue.offer(None)
      case Left(Some(SystemMessage("init", data))) =>
        data.get("session_id").map(_.toString) match
          case Some(id) =>
            logger.info(s"Session ID extracted from init message: $id") *>
              sessionIdRef.set(id)
          case None => IO.unit
      case Left(Some(msg)) =>
        // Non-init message; put it back for the user's stream
        messageQueue.offer(Some(msg))
      case Right(()) =>
        // Timeout: no message arrived — session ID stays "pending"
        IO.unit
    }

  private def captureStderr(
      process: fs2.io.process.Process[IO]
  )(using logger: Logger[IO]): IO[Unit] =
    process.stderr
      .through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
      .evalMap(line => logger.debug(s"session stderr: $line"))
      .compile
      .drain

/** Internal Session implementation that writes to a stdin queue and reads from
  * a shared message queue.
  */
private class SessionImpl(
    stdinQueue: Queue[IO, Option[Chunk[Byte]]],
    sessionIdRef: Ref[IO, String],
    messageQueue: Queue[IO, Option[Message]],
    aliveRef: Ref[IO, Boolean],
    pendingErrorRef: Ref[IO, Option[CLIError]]
)(using logger: Logger[IO])
    extends Session:

  def sessionId: IO[String] = sessionIdRef.get

  def send(prompt: String): IO[Unit] =
    aliveRef.get.flatMap { isAlive =>
      if !isAlive then
        pendingErrorRef.get.flatMap {
          case Some(err) => IO.raiseError(err)
          case None      =>
            IO.raiseError(
              SessionProcessDied(None, "")
            )
        }
      else
        sessionIdRef.get.flatMap { currentId =>
          val msg = SDKUserMessage(content = prompt, sessionId = currentId)
          val json = msg.asJson.noSpaces + "\n"
          logger.debug(
            s"Writing SDKUserMessage to stdin: ${msg.asJson.noSpaces}"
          ) *>
            stdinQueue.offer(
              Some(
                Chunk.array(
                  json.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
              )
            )
        }
    }

  /** Reads messages from the shared queue until (and including) a
    * ResultMessage, then stops. If the queue signals EOF (None) before a
    * ResultMessage is received, checks pendingErrorRef and raises the error if
    * present (process died mid-turn).
    */
  /** Reads messages from the shared queue until (and including) a
    * ResultMessage, then stops. If the queue signals EOF (None) before a
    * ResultMessage is received, checks pendingErrorRef and raises the error if
    * present (process died mid-turn).
    *
    * resultSeenRef tracks whether the stream completed normally (via
    * ResultMessage). pendingErrorRef carries the process-death error from the
    * stdout reader fiber. Both are needed because the queue EOF (None) is the
    * same signal for normal process shutdown and abnormal exit — resultSeenRef
    * disambiguates the two cases so onFinalize only raises on abnormal exit.
    */
  def stream: Stream[IO, Message] =
    Stream.eval(IO.ref(false)).flatMap { resultSeenRef =>
      Stream
        .fromQueueNoneTerminated(messageQueue)
        .evalTap {
          case _: ResultMessage => resultSeenRef.set(true)
          case _                => IO.unit
        }
        .takeThrough(!_.isInstanceOf[ResultMessage])
        .onFinalize {
          resultSeenRef.get.flatMap { resultSeen =>
            if !resultSeen then
              pendingErrorRef.get.flatMap {
                case Some(err) => IO.raiseError(err)
                case None      => IO.unit
              }
            else IO.unit
          }
        }
    }
