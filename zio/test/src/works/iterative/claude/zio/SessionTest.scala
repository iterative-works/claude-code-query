// PURPOSE: Unit tests for the ZIO Session implementation's turn and lifecycle logic
// PURPOSE: Drives the impl through queues and refs without spawning a real process

package works.iterative.claude.zio

import zio.*
import java.nio.charset.StandardCharsets
import zio.test.*
import works.iterative.claude.core.{CLIError, SessionProcessDied}
import works.iterative.claude.core.model.*
import works.iterative.claude.zio.internal.cli.SessionProcess
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object SessionTest extends ClaudeZioSpec:

  private def makeSession(
      alive: Boolean,
      pendingError: Option[CLIError],
      messages: List[Option[Message]] = Nil,
      sessionId: String = "pending"
  ): UIO[Session] =
    for
      stdinQueue      <- Queue.unbounded[Chunk[Byte]]
      messageQueue    <- Queue.unbounded[Option[Message]]
      _               <- ZIO.foreachDiscard(messages)(messageQueue.offer)
      sessionIdRef    <- Ref.make(sessionId)
      aliveRef        <- Ref.make(alive)
      pendingErrorRef <- Ref.make(pendingError)
    yield SessionProcess.make(
      stdinQueue,
      sessionIdRef,
      messageQueue,
      aliveRef,
      pendingErrorRef
    )

  private val assistant = AssistantMessage(List(TextBlock("hi")))
  private val result    = ResultMessage("success", 1, 1, false, 1, "sess-1")

  def spec = suite("Session")(
    test("send fails with the pending error when the process has died"):
      for
        session <- makeSession(
                     alive = false,
                     pendingError = Some(SessionProcessDied(Some(1), "boom"))
                   )
        error   <- session.send("hi").flip
      yield assertTrue(error == SessionProcessDied(Some(1), "boom")),
    test("send fails with SessionProcessDied(None) when dead without a recorded error"):
      for
        session <- makeSession(alive = false, pendingError = None)
        error   <- session.send("hi").flip
      yield assertTrue(error == SessionProcessDied(None, "")),
    test("send fails with the pending error when the stdin queue is shut down"):
      for
        stdinQueue      <- Queue.unbounded[Chunk[Byte]]
        messageQueue    <- Queue.unbounded[Option[Message]]
        sessionIdRef    <- Ref.make("sess-1")
        aliveRef        <- Ref.make(true)
        pendingErrorRef <- Ref.make(
                             Option[CLIError](SessionProcessDied(Some(7), "gone"))
                           )
        session          = SessionProcess.make(
                             stdinQueue,
                             sessionIdRef,
                             messageQueue,
                             aliveRef,
                             pendingErrorRef
                           )
        _               <- stdinQueue.shutdown
        error           <- session.send("hi").flip
      yield assertTrue(error == SessionProcessDied(Some(7), "gone")),
    test("send fails with SessionProcessDied(None) when the stdin queue is shut down without a recorded error"):
      for
        stdinQueue      <- Queue.unbounded[Chunk[Byte]]
        messageQueue    <- Queue.unbounded[Option[Message]]
        sessionIdRef    <- Ref.make("sess-1")
        aliveRef        <- Ref.make(true)
        pendingErrorRef <- Ref.make(Option.empty[CLIError])
        session          = SessionProcess.make(
                             stdinQueue,
                             sessionIdRef,
                             messageQueue,
                             aliveRef,
                             pendingErrorRef
                           )
        _               <- stdinQueue.shutdown
        error           <- session.send("hi").flip
      yield assertTrue(error == SessionProcessDied(None, "")),
    test("send enqueues an SDKUserMessage line carrying the session id"):
      for
        stdinQueue      <- Queue.unbounded[Chunk[Byte]]
        messageQueue    <- Queue.unbounded[Option[Message]]
        sessionIdRef    <- Ref.make("sess-1")
        aliveRef        <- Ref.make(true)
        pendingErrorRef <- Ref.make(Option.empty[CLIError])
        session          = SessionProcess.make(
                             stdinQueue,
                             sessionIdRef,
                             messageQueue,
                             aliveRef,
                             pendingErrorRef
                           )
        _               <- session.send("hello")
        chunk           <- stdinQueue.take
        line             = new String(chunk.toArray, StandardCharsets.UTF_8)
      yield assertTrue(
        line.contains("\"session_id\":\"sess-1\""),
        line.contains("hello"),
        line.endsWith("\n")
      ),
    test("stream emits messages up to and including the ResultMessage"):
      for
        session <- makeSession(
                     alive = true,
                     pendingError = None,
                     messages = List(Some(assistant), Some(result), None)
                   )
        msgs    <- session.stream.runCollect
      yield assertTrue(msgs.toList == List(assistant, result)),
    test("stream leaves messages buffered after the ResultMessage for the next turn"):
      val extra = AssistantMessage(List(TextBlock("turn two")))
      for
        stdinQueue      <- Queue.unbounded[Chunk[Byte]]
        messageQueue    <- Queue.unbounded[Option[Message]]
        // Two turns' worth of messages buffered up front in the shared queue.
        _               <- ZIO.foreachDiscard(
                             List(Some(assistant), Some(result), Some(extra), Some(result))
                           )(messageQueue.offer)
        sessionIdRef    <- Ref.make("sess-1")
        aliveRef        <- Ref.make(true)
        pendingErrorRef <- Ref.make(Option.empty[CLIError])
        session          = SessionProcess.make(
                             stdinQueue,
                             sessionIdRef,
                             messageQueue,
                             aliveRef,
                             pendingErrorRef
                           )
        firstTurn       <- session.stream.runCollect
        secondTurn      <- session.stream.runCollect
      yield assertTrue(
        firstTurn.toList == List(assistant, result),
        secondTurn.toList == List(extra, result)
      ),
    test("stream fails with the pending error on EOF before a ResultMessage"):
      for
        session <- makeSession(
                     alive = false,
                     pendingError = Some(SessionProcessDied(Some(2), "died")),
                     messages = List(None)
                   )
        error   <- session.stream.runCollect.flip
      yield assertTrue(error == SessionProcessDied(Some(2), "died")),
    test("sessionId reads the current value"):
      for
        session <- makeSession(alive = true, pendingError = None, sessionId = "abc")
        id      <- session.sessionId
      yield assertTrue(id == "abc")
  )
