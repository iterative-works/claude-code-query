// PURPOSE: Integration tests for effectful Session using mock CLI scripts
// PURPOSE: Verifies full session lifecycle, Resource cleanup, stdin JSON format, and ClaudeCode factory
package works.iterative.claude.effectful

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.testing.TestingLogger
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.testing.SessionMockCliScript
import io.circe.parser
import java.nio.file.Files

class SessionIntegrationTest extends CatsEffectSuite:

  given Logger[IO] = TestingLogger.impl[IO]()

  // ============================================================
  // IT1: Full single-turn session lifecycle
  // ============================================================

  test("full single-turn session lifecycle with mock CLI") {
    val sessionId = "integ-session-001"
    val script = SessionMockCliScript.createSessionScript(
      initMessages = List(
        SessionMockCliScript.CommonResponses.initMessage(sessionId)
      ),
      turnResponses = List(
        SessionMockCliScript.TurnResponse(
          List(
            SessionMockCliScript.CommonResponses.assistantMessage(
              "The answer is 42"
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
          _ <- session.send("What is the answer?")
          messages <- session.stream.compile.toList
        yield
          assertEquals(messages.length, 2)
          messages.head match
            case AssistantMessage(content) =>
              val texts = content.collect { case TextBlock(t) => t }
              assert(texts.exists(_.contains("The answer is 42")))
            case other => fail(s"Expected AssistantMessage, got: $other")
          messages.last match
            case r: ResultMessage =>
              assertEquals(r.subtype, "conversation_result")
              assertEquals(r.isError, false)
              assertEquals(r.sessionId, sessionId)
            case other => fail(s"Expected ResultMessage, got: $other")
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // IT2: Resource cleanup on normal exit
  // ============================================================

  test("Resource cleanup terminates process on normal exit") {
    val script = SessionMockCliScript.createSessionScript(
      initMessages = Nil,
      turnResponses = List(
        SessionMockCliScript.TurnResponse(
          List(SessionMockCliScript.CommonResponses.resultMessage())
        )
      )
    )
    val options = SessionOptions().withClaudeExecutable(script.toString)
    // Track whether cleanup ran (we verify indirectly by checking that
    // the Resource.use block completes without hanging)
    ClaudeCode
      .session(options)
      .use { session =>
        session.send("test") *> session.stream.compile.drain
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // IT3: Resource cleanup on error
  // ============================================================

  test("Resource cleanup terminates process when exception occurs inside use") {
    val script = SessionMockCliScript.createSessionScript(
      initMessages = Nil,
      turnResponses = Nil
    )
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { _ =>
        IO.raiseError(new RuntimeException("deliberate test error"))
      }
      .attempt
      .map {
        case Left(e: RuntimeException) =>
          assertEquals(e.getMessage, "deliberate test error")
        case Left(other) =>
          fail(
            s"Expected RuntimeException, got: ${other.getClass.getSimpleName}"
          )
        case Right(_) =>
          fail("Expected exception to propagate through Resource.use")
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // IT4: Stdin JSON verification
  // ============================================================

  test("stdin carries correct SDKUserMessage JSON") {
    val captureFile = Files.createTempFile("stdin-integ-", ".jsonl")
    val script = SessionMockCliScript.createSessionScript(
      initMessages = Nil,
      turnResponses = List(
        SessionMockCliScript.TurnResponse(
          List(SessionMockCliScript.CommonResponses.resultMessage())
        )
      ),
      captureStdinFile = Some(captureFile)
    )
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        session.send(
          "Hello from integration test"
        ) *> session.stream.compile.drain
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
      .flatMap { _ =>
        IO {
          val lines = Files.readAllLines(captureFile)
          assert(lines.size >= 1, "Expected at least one stdin line")
          val json = parser
            .parse(lines.get(0))
            .toOption
            .getOrElse(fail(s"stdin not valid JSON: ${lines.get(0)}"))
          val cursor = json.hcursor
          assertEquals(
            cursor.downField("type").as[String].toOption,
            Some("user")
          )
          assertEquals(
            cursor
              .downField("message")
              .downField("content")
              .as[String]
              .toOption,
            Some("Hello from integration test")
          )
          Files.delete(captureFile)
        }
      }
  }

  // ============================================================
  // IT5: Session ID from init message
  // ============================================================

  test("session ID is set from mock init message during acquire") {
    val expectedSessionId = "integ-init-session-999"
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
  // IT6: Two-turn lifecycle
  // ============================================================

  test("two-turn lifecycle returns correct responses per turn") {
    val initSessionId = "two-turn-init-001"
    val turn1SessionId = "two-turn-result-001"
    val turn2SessionId = "two-turn-result-002"
    val script = SessionMockCliScript.createSessionScript(
      initMessages = List(
        SessionMockCliScript.CommonResponses.initMessage(initSessionId)
      ),
      turnResponses = List(
        SessionMockCliScript.TurnResponse(
          List(
            SessionMockCliScript.CommonResponses.assistantMessage(
              "First turn answer"
            ),
            SessionMockCliScript.CommonResponses.resultMessage(turn1SessionId)
          )
        ),
        SessionMockCliScript.TurnResponse(
          List(
            SessionMockCliScript.CommonResponses.assistantMessage(
              "Second turn answer"
            ),
            SessionMockCliScript.CommonResponses.resultMessage(turn2SessionId)
          )
        )
      )
    )
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        for
          initId <- session.sessionId
          _ = assertEquals(initId, initSessionId)
          _ <- session.send("First question")
          turn1 <- session.stream.compile.toList
          _ = assertEquals(turn1.length, 2)
          _ = turn1.head match
            case AssistantMessage(content) =>
              val texts = content.collect { case TextBlock(t) => t }
              assert(texts.exists(_.contains("First turn answer")))
            case other => fail(s"Expected AssistantMessage, got: $other")
          _ = turn1.last match
            case r: ResultMessage => assertEquals(r.sessionId, turn1SessionId)
            case other => fail(s"Expected ResultMessage, got: $other")
          id1 <- session.sessionId
          _ = assertEquals(id1, turn1SessionId)
          _ <- session.send("Second question")
          turn2 <- session.stream.compile.toList
          _ = assertEquals(turn2.length, 2)
          _ = turn2.head match
            case AssistantMessage(content) =>
              val texts = content.collect { case TextBlock(t) => t }
              assert(texts.exists(_.contains("Second turn answer")))
            case other => fail(s"Expected AssistantMessage, got: $other")
          _ = turn2.last match
            case r: ResultMessage => assertEquals(r.sessionId, turn2SessionId)
            case other => fail(s"Expected ResultMessage, got: $other")
          id2 <- session.sessionId
          _ = assertEquals(id2, turn2SessionId)
        yield ()
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // IT7: Session ID progression across turns
  // ============================================================

  test("stdin capture shows correct session ID progression across turns") {
    val initSessionId = "progression-init-001"
    val turn1SessionId = "progression-turn-001"
    val captureFile = Files.createTempFile("stdin-progression-integ-", ".jsonl")
    val script = SessionMockCliScript.createSessionScript(
      initMessages = List(
        SessionMockCliScript.CommonResponses.initMessage(initSessionId)
      ),
      turnResponses = List(
        SessionMockCliScript.TurnResponse(
          List(
            SessionMockCliScript.CommonResponses.resultMessage(turn1SessionId)
          )
        ),
        SessionMockCliScript.TurnResponse(
          List(
            SessionMockCliScript.CommonResponses.resultMessage(
              "progression-turn-002"
            )
          )
        )
      ),
      captureStdinFile = Some(captureFile)
    )
    val options = SessionOptions().withClaudeExecutable(script.toString)
    ClaudeCode
      .session(options)
      .use { session =>
        for
          _ <- session.send("Prompt one")
          _ <- session.stream.compile.drain
          _ <- session.send("Prompt two")
          _ <- session.stream.compile.drain
        yield ()
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
      .flatMap { _ =>
        IO {
          val lines = Files.readAllLines(captureFile)
          assert(
            lines.size >= 2,
            s"Expected at least 2 stdin lines, got ${lines.size}"
          )

          val firstJson = parser
            .parse(lines.get(0))
            .toOption
            .getOrElse(fail(s"First stdin line not valid JSON"))
          assertEquals(
            firstJson.hcursor.downField("session_id").as[String].toOption,
            Some(initSessionId),
            "First send should carry init session ID"
          )

          val secondJson = parser
            .parse(lines.get(1))
            .toOption
            .getOrElse(fail(s"Second stdin line not valid JSON"))
          assertEquals(
            secondJson.hcursor.downField("session_id").as[String].toOption,
            Some(turn1SessionId),
            "Second send should carry session ID from first turn's ResultMessage"
          )
          Files.delete(captureFile)
        }
      }
  }

  // ============================================================
  // IT8: Variable message counts per turn
  // ============================================================

  test("turns with different message counts emit exactly the right messages") {
    val sessionId = "msg-count-session"
    val script = SessionMockCliScript.createSessionScript(
      initMessages = List(
        SessionMockCliScript.CommonResponses.initMessage(sessionId)
      ),
      turnResponses = List(
        // Turn 1: assistant + result (2 messages)
        SessionMockCliScript.TurnResponse(
          List(
            SessionMockCliScript.CommonResponses.assistantMessage("Short"),
            SessionMockCliScript.CommonResponses.resultMessage(sessionId)
          )
        ),
        // Turn 2: keepalive + stream_event + assistant + result (4 messages)
        SessionMockCliScript.TurnResponse(
          List(
            SessionMockCliScript.CommonResponses.keepAliveMessage,
            SessionMockCliScript.CommonResponses.streamEventMessage("start"),
            SessionMockCliScript.CommonResponses.assistantMessage("Verbose"),
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
          _ <- session.send("Short turn")
          turn1 <- session.stream.compile.toList
          _ = assertEquals(
            turn1.length,
            2,
            s"Turn 1 should have 2 messages, got: $turn1"
          )
          _ = assert(
            turn1.exists(_.isInstanceOf[AssistantMessage]),
            "Turn 1 must have AssistantMessage"
          )
          _ = assert(
            turn1.exists(_.isInstanceOf[ResultMessage]),
            "Turn 1 must have ResultMessage"
          )
          _ = assert(
            !turn1.exists(_ == KeepAliveMessage),
            "Turn 1 must not have KeepAliveMessage"
          )
          _ <- session.send("Verbose turn")
          turn2 <- session.stream.compile.toList
          _ = assertEquals(
            turn2.length,
            4,
            s"Turn 2 should have 4 messages, got: $turn2"
          )
          _ = assert(
            turn2(0) == KeepAliveMessage,
            s"Turn 2[0] should be KeepAliveMessage"
          )
          _ = assert(
            turn2(1).isInstanceOf[StreamEventMessage],
            s"Turn 2[1] should be StreamEventMessage"
          )
          _ = assert(
            turn2(2).isInstanceOf[AssistantMessage],
            s"Turn 2[2] should be AssistantMessage"
          )
          _ = assert(
            turn2(3).isInstanceOf[ResultMessage],
            s"Turn 2[3] should be ResultMessage"
          )
        yield ()
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }

  // ============================================================
  // IT9: ClaudeCode.session factory
  // ============================================================

  test("ClaudeCode.session factory produces a working session Resource") {
    val sessionId = "factory-session-002"
    val script = SessionMockCliScript.createSessionScript(
      initMessages = List(
        SessionMockCliScript.CommonResponses.initMessage(sessionId)
      ),
      turnResponses = List(
        SessionMockCliScript.TurnResponse(
          List(
            SessionMockCliScript.CommonResponses.assistantMessage(
              "Factory response"
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
          _ <- session.send("factory test")
          messages <- session.stream.compile.toList
        yield
          assert(
            messages.exists(_.isInstanceOf[AssistantMessage]),
            "Expected AssistantMessage"
          )
          assert(
            messages.exists(_.isInstanceOf[ResultMessage]),
            "Expected ResultMessage"
          )
      }
      .guarantee(IO { SessionMockCliScript.cleanup(script): Unit })
  }
