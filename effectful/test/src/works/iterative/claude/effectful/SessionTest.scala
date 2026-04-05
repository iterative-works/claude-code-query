// PURPOSE: Unit tests for effectful Session trait and SessionProcess implementation
// PURPOSE: Verifies session lifecycle, message flow, and session ID extraction using cats-effect IO
package works.iterative.claude.effectful

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.testing.TestingLogger
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.testing.SessionMockCliScript
import io.circe.parser
import java.nio.file.Files

class SessionTest extends CatsEffectSuite:

  given Logger[IO] = TestingLogger.impl[IO]()

  // ============================================================
  // T1: SDKUserMessage encoding test
  // ============================================================

  test("SDKUserMessage is correctly encoded for stdin") {
    import io.circe.syntax.*
    val msg = SDKUserMessage(content = "Hello, Claude!", sessionId = "sess-42")
    val json = msg.asJson.noSpaces
    val parsed = parser.parse(json).toOption.get
    val cursor = parsed.hcursor

    assertEquals(cursor.downField("type").as[String].toOption, Some("user"))
    assertEquals(
      cursor.downField("message").downField("role").as[String].toOption,
      Some("user")
    )
    assertEquals(
      cursor.downField("message").downField("content").as[String].toOption,
      Some("Hello, Claude!")
    )
    assertEquals(
      cursor.downField("session_id").as[String].toOption,
      Some("sess-42")
    )
  }

  // ============================================================
  // T2: Session ID defaults to "pending"
  // ============================================================

  test("session ID defaults to 'pending' before any send") {
    val script = SessionMockCliScript.createSessionScript(
      initMessages = Nil,
      turnResponses = Nil
    )
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        session.sessionId.map { id =>
          assertEquals(id, "pending")
        }
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // T3: Session ID extracted from init SystemMessage
  // ============================================================

  test(
    "session ID is extracted from init SystemMessage during Resource acquire"
  ) {
    val expectedSessionId = "init-extracted-001"
    val script = SessionMockCliScript.createSessionScript(
      initMessages = List(
        SessionMockCliScript.CommonResponses.initMessage(expectedSessionId)
      ),
      turnResponses = List(
        SessionMockCliScript.TurnResponse(
          List(
            SessionMockCliScript.CommonResponses.resultMessage(
              expectedSessionId
            )
          )
        )
      )
    )
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        session.sessionId.map { id =>
          assertEquals(id, expectedSessionId)
        }
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // T4: Session ID updated from ResultMessage
  // ============================================================

  test("session ID is updated from ResultMessage after stream completes") {
    val updatedSessionId = "updated-session-id-456"
    val script = SessionMockCliScript.createSessionScript(
      initMessages = Nil,
      turnResponses = List(
        SessionMockCliScript.TurnResponse(
          List(
            s"""{"type":"result","subtype":"conversation_result","duration_ms":100,"duration_api_ms":50,"is_error":false,"num_turns":1,"session_id":"$updatedSessionId"}"""
          )
        )
      )
    )
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        for
          _ <- session.send("test")
          _ <- session.stream.compile.drain
          id <- session.sessionId
        yield assertEquals(id, updatedSessionId)
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // T5: stream returns messages up to and including ResultMessage
  // ============================================================

  test("stream completes after emitting ResultMessage") {
    val sessionId = "sess-flow-test"
    val script = SessionMockCliScript.createSessionScript(
      initMessages = Nil,
      turnResponses = List(
        SessionMockCliScript.TurnResponse(
          List(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"Hello!"}]}}""",
            s"""{"type":"result","subtype":"conversation_result","duration_ms":100,"duration_api_ms":50,"is_error":false,"num_turns":1,"session_id":"$sessionId"}"""
          )
        )
      )
    )
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        for
          _ <- session.send("test")
          messages <- session.stream.compile.toList
        yield
          val resultMessages = messages.collect { case r: ResultMessage => r }
          assertEquals(resultMessages.length, 1)
          val assistantMessages = messages.collect { case a: AssistantMessage =>
            a
          }
          assertEquals(assistantMessages.length, 1)
          assertEquals(messages.length, 2)
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // T6: KeepAlive and StreamEvent pass through
  // ============================================================

  test("KeepAlive and StreamEvent messages pass through the stream") {
    val sessionId = "keepalive-stream-session"
    val script = SessionMockCliScript.createSessionScript(
      initMessages = List(
        SessionMockCliScript.CommonResponses.initMessage(sessionId)
      ),
      turnResponses = List(
        SessionMockCliScript.TurnResponse(
          List(
            SessionMockCliScript.CommonResponses.keepAliveMessage,
            SessionMockCliScript.CommonResponses.streamEventMessage("start"),
            SessionMockCliScript.CommonResponses.assistantMessage(
              "Answer with events"
            ),
            SessionMockCliScript.CommonResponses.resultMessage(sessionId)
          )
        )
      )
    )
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        for
          _ <- session.send("test")
          messages <- session.stream.compile.toList
        yield
          assert(
            messages.exists(_ == KeepAliveMessage),
            s"Expected KeepAliveMessage in stream: $messages"
          )
          assert(
            messages.exists(_.isInstanceOf[StreamEventMessage]),
            s"Expected StreamEventMessage in stream: $messages"
          )
          assert(
            messages.exists(_.isInstanceOf[AssistantMessage]),
            s"Expected AssistantMessage in stream: $messages"
          )
          assert(
            messages.exists(_.isInstanceOf[ResultMessage]),
            s"Expected ResultMessage in stream: $messages"
          )
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // T7: Two sequential sends return isolated streams
  // ============================================================

  test("two sequential sends return isolated message streams") {
    val turn1Response =
      """{"type":"assistant","message":{"content":[{"type":"text","text":"Turn 1 response"}]}}"""
    val turn1Result =
      """{"type":"result","subtype":"conversation_result","duration_ms":100,"duration_api_ms":50,"is_error":false,"num_turns":1,"session_id":"session-turn-1"}"""
    val turn2Response =
      """{"type":"assistant","message":{"content":[{"type":"text","text":"Turn 2 response"}]}}"""
    val turn2Result =
      """{"type":"result","subtype":"conversation_result","duration_ms":150,"duration_api_ms":60,"is_error":false,"num_turns":2,"session_id":"session-turn-2"}"""

    val script = SessionMockCliScript.createSessionScript(
      initMessages = Nil,
      turnResponses = List(
        SessionMockCliScript.TurnResponse(List(turn1Response, turn1Result)),
        SessionMockCliScript.TurnResponse(List(turn2Response, turn2Result))
      )
    )
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        for
          _ <- session.send("First prompt")
          turn1Messages <- session.stream.compile.toList
          _ <- session.send("Second prompt")
          turn2Messages <- session.stream.compile.toList
        yield
          assertEquals(turn1Messages.length, 2)
          val turn1Assistants = turn1Messages.collect {
            case a: AssistantMessage => a
          }
          assertEquals(turn1Assistants.length, 1)

          assertEquals(turn2Messages.length, 2)
          val turn2Assistants = turn2Messages.collect {
            case a: AssistantMessage => a
          }
          assertEquals(turn2Assistants.length, 1)

          val turn1Texts = turn1Assistants.flatMap(_.content.collect {
            case TextBlock(t) => t
          })
          assert(
            turn1Texts.exists(_.contains("Turn 1")),
            s"Expected 'Turn 1' text: $turn1Texts"
          )

          val turn2Texts = turn2Assistants.flatMap(_.content.collect {
            case TextBlock(t) => t
          })
          assert(
            turn2Texts.exists(_.contains("Turn 2")),
            s"Expected 'Turn 2' text: $turn2Texts"
          )
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // T8: Session ID propagates from first turn to second send
  // ============================================================

  test("session ID propagates from first turn ResultMessage to second send") {
    val firstTurnSessionId = "session-after-turn-1"
    val captureFile = Files.createTempFile("stdin-capture-effectful-", ".jsonl")
    val turn1Result =
      s"""{"type":"result","subtype":"conversation_result","duration_ms":100,"duration_api_ms":50,"is_error":false,"num_turns":1,"session_id":"$firstTurnSessionId"}"""
    val turn2Result =
      s"""{"type":"result","subtype":"conversation_result","duration_ms":120,"duration_api_ms":55,"is_error":false,"num_turns":2,"session_id":"session-after-turn-2"}"""

    val script = SessionMockCliScript.createSessionScript(
      initMessages = Nil,
      turnResponses = List(
        SessionMockCliScript.TurnResponse(List(turn1Result)),
        SessionMockCliScript.TurnResponse(List(turn2Result))
      ),
      captureStdinFile = Some(captureFile)
    )
    val options = SessionOptions().withClaudeExecutable(script.toString)
    val test = ClaudeCode
      .session(options)
      .use { session =>
        for
          _ <- session.send("First prompt")
          _ <- session.stream.compile.drain
          id <- session.sessionId
          _ = assertEquals(id, firstTurnSessionId)
          _ <- session.send("Second prompt")
          _ <- session.stream.compile.drain
        yield ()
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })

    test.flatMap { _ =>
      IO {
        val lines = Files.readAllLines(captureFile)
        assert(
          lines.size >= 2,
          s"Expected at least 2 stdin lines, got ${lines.size}"
        )

        val secondLine = lines.get(1)
        val secondJson = parser
          .parse(secondLine)
          .toOption
          .getOrElse(fail(s"Second stdin line not valid JSON: $secondLine"))
        assertEquals(
          secondJson.hcursor.downField("session_id").as[String].toOption,
          Some(firstTurnSessionId)
        )
        Files.delete(captureFile)
      }
    }
  }

  // ============================================================
  // T9: Three-turn cycling
  // ============================================================

  test("three sequential sends each return only their own turn's messages") {
    val turns = (1 to 3).map { i =>
      SessionMockCliScript.TurnResponse(
        List(
          s"""{"type":"assistant","message":{"content":[{"type":"text","text":"Response $i"}]}}""",
          s"""{"type":"result","subtype":"conversation_result","duration_ms":100,"duration_api_ms":50,"is_error":false,"num_turns":$i,"session_id":"session-turn-$i"}"""
        )
      )
    }.toList

    val script = SessionMockCliScript.createSessionScript(
      initMessages = Nil,
      turnResponses = turns
    )
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        def runTurn(i: Int): IO[List[Message]] =
          session.send(s"Prompt $i") *> session.stream.compile.toList

        for
          t1 <- runTurn(1)
          t2 <- runTurn(2)
          t3 <- runTurn(3)
        yield List(t1, t2, t3).zipWithIndex.foreach { case (msgs, idx) =>
          val i = idx + 1
          assertEquals(
            msgs.length,
            2,
            s"Turn $i: expected 2 messages, got ${msgs.length}"
          )
          assert(
            msgs.head.isInstanceOf[AssistantMessage],
            s"Turn $i: first should be AssistantMessage"
          )
          assert(
            msgs.last.isInstanceOf[ResultMessage],
            s"Turn $i: last should be ResultMessage"
          )
          val resultMsg = msgs.last.asInstanceOf[ResultMessage]
          assertEquals(
            resultMsg.sessionId,
            s"session-turn-$i",
            s"Turn $i: wrong session ID"
          )
        }
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }
