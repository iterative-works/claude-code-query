// PURPOSE: Resource-based effectful Session implementation backed by a long-lived CLI process
// PURPOSE: Manages stdin writing, stdout streaming via Queue, stderr capture, and process lifecycle
package works.iterative.claude.effectful.internal.cli

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Queue
import fs2.{Chunk, Stream}
import fs2.io.process.ProcessBuilder
import fs2.io.file.Path
import io.circe.syntax.*
import org.typelevel.log4cats.Logger
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
      // Background fiber: drains stdinQueue into process.stdin
      _ <- Resource.make(
        startStdinWriter(process, stdinQueue).start
      )(_.cancel)
      // Background fiber: reads process.stdout into messageQueue
      _ <- Resource.make(
        startStdoutReader(process, messageQueue, sessionIdRef).start
      )(_.cancel)
      // Background fiber: captures stderr for diagnostics
      _ <- Resource.make(
        captureStderr(process).start
      )(_.cancel)
      // Read init message to prime sessionId before returning the session
      _ <- Resource.eval(readInitMessage(messageQueue, sessionIdRef))
    yield new SessionImpl(stdinQueue, sessionIdRef, messageQueue)

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
    * them. Also updates sessionIdRef on ResultMessage. Signals EOF with None.
    */
  private def startStdoutReader(
      process: fs2.io.process.Process[IO],
      messageQueue: Queue[IO, Option[Message]],
      sessionIdRef: Ref[IO, String]
  )(using logger: Logger[IO]): IO[Unit] =
    process.stdout
      .through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
      .zipWithIndex
      .evalMap { case (line, idx) =>
        JsonParser.parseJsonLineWithContext(line, idx.toInt + 1, logger)
      }
      .evalMap {
        case Left(err)        => IO.raiseError(err)
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
      .guarantee(messageQueue.offer(None))

  private val InitReadTimeout =
    scala.concurrent.duration.FiniteDuration(1000, "ms")

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
    messageQueue: Queue[IO, Option[Message]]
)(using logger: Logger[IO])
    extends Session:

  def sessionId: IO[String] = sessionIdRef.get

  def send(prompt: String): IO[Unit] =
    sessionIdRef.get.flatMap { currentId =>
      val msg = SDKUserMessage(content = prompt, sessionId = currentId)
      val json = msg.asJson.noSpaces + "\n"
      logger.debug(
        s"Writing SDKUserMessage to stdin: ${msg.asJson.noSpaces}"
      ) *>
        stdinQueue.offer(
          Some(
            Chunk.array(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          )
        )
    }

  /** Reads messages from the shared queue until (and including) a
    * ResultMessage, then stops.
    */
  def stream: Stream[IO, Message] =
    Stream.eval(messageQueue.take).flatMap {
      case None =>
        // Stdout closed — no more messages
        Stream.empty
      case Some(msg) =>
        msg match
          case _: ResultMessage =>
            Stream.emit(msg)
          case _ =>
            Stream.emit(msg) ++ stream
    }
