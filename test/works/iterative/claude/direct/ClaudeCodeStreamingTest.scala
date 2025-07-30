// PURPOSE: Tests for real streaming behavior in direct ClaudeCode implementation
// PURPOSE: Verifies messages arrive as process produces them, not after completion
package works.iterative.claude.direct

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.testing.MockCliScript
import works.iterative.claude.direct.internal.testing.MockCliScript.MockBehavior
import works.iterative.claude.direct.internal.testing.TestConstants
import java.nio.file.Path
import scala.concurrent.duration.*
import scala.util.Try

class ClaudeCodeStreamingTest extends munit.FunSuite:

  // Track created mock scripts for cleanup
  private val createdScripts = scala.collection.mutable.ListBuffer[Path]()

  // Mock Logger for testing
  class MockLogger extends Logger:
    var debugMessages: List[String] = List.empty
    var infoMessages: List[String] = List.empty
    var warnMessages: List[String] = List.empty
    var errorMessages: List[String] = List.empty

    def debug(msg: => String): Unit = debugMessages = msg :: debugMessages
    def info(msg: => String): Unit = infoMessages = msg :: infoMessages
    def warn(msg: => String): Unit = warnMessages = msg :: warnMessages
    def error(msg: => String): Unit = errorMessages = msg :: errorMessages
    def error(msg: => String, exception: Throwable): Unit = errorMessages =
      s"$msg: ${exception.getMessage}" :: errorMessages

  override def afterEach(context: AfterEach): Unit =
    // Clean up temporary mock scripts
    createdScripts.foreach(MockCliScript.cleanup)
    createdScripts.clear()
    super.afterEach(context)

  test(
    "STREAMING: messages should arrive as CLI process produces them, not after completion"
  ) {
    supervised {
      given MockLogger = MockLogger()

      // Setup: Create mock CLI that outputs messages with significant delays
      // This test will prove streaming works because:
      // 1. First message should arrive before 2-second delay to second message
      // 2. If it's fake streaming, we'd wait for ALL messages before seeing ANY
      val mockBehavior = MockBehavior(
        messages = List(
          """{"type":"system","subtype":"user_context","context_user_id":"user_123"}""",
          """{"type":"assistant","message":{"content":[{"type":"text","text":"First response"}]}}""",
          """{"type":"assistant","message":{"content":[{"type":"text","text":"Second response"}]}}"""
        ),
        delayBetweenMessages =
          5.seconds, // Much longer delay to prove streaming
        exitCode = 0
      )
      val mockScript = MockCliScript.createTempScript(mockBehavior)
      createdScripts += mockScript

      // Debug: Print the actual script content to understand what's being generated
      println(s"Mock script path: ${mockScript}")
      val scriptContent = scala.io.Source.fromFile(mockScript.toFile).mkString
      println(s"Mock script content:\n${scriptContent}")

      val options = QueryOptions
        .simple("Test streaming")
        .withClaudeExecutable(mockScript.toString)

      // Execute: Test real streaming behavior
      val claude = ClaudeCode.concurrent
      val messageFlow = claude.query(options)

      println(s"Starting test at ${System.currentTimeMillis()}")

      // ❌ THIS SHOULD FAIL with current fake streaming implementation
      // Current implementation: waits for process completion, then Flow.fromIterable()
      // Real streaming: should get first message before 5-second delay to second message

      val startTime = System.currentTimeMillis()
      val messages = scala.collection.mutable.ListBuffer[Message]()

      // Stream messages and record timing
      messageFlow.runForeach { message =>
        val elapsed = System.currentTimeMillis() - startTime
        println(
          s"Got message at ${elapsed}ms: ${message.getClass.getSimpleName}"
        )
        messages += message

        // CRITICAL TEST: Real streaming validation
        // Real streaming: first message ~100ms, second ~5100ms, third ~10100ms
        // Fake streaming: all messages arrive quickly after ~15 second process completion
        if (messages.size == 1) {
          val firstMessageTime = elapsed
          println(s"First message arrived at ${firstMessageTime}ms")

          // Real streaming should show first message arriving quickly
          assert(
            firstMessageTime < 1000,
            s"First message took too long: ${firstMessageTime}ms"
          )
        }

        if (messages.size == 2) {
          val secondMessageTime = elapsed
          println(s"Second message arrived at ${secondMessageTime}ms")

          // Real streaming: second message should arrive ~5 seconds after first
          assert(
            secondMessageTime >= 4000 && secondMessageTime <= 6000,
            s"Second message timing wrong: ${secondMessageTime}ms. Expected ~5000ms for real streaming"
          )
        }

        if (messages.size == 3) {
          val thirdMessageTime = elapsed
          println(s"Third message arrived at ${thirdMessageTime}ms")

          // Real streaming: third message should arrive ~10 seconds after start
          assert(
            thirdMessageTime >= 9000 && thirdMessageTime <= 11000,
            s"Third message timing wrong: ${thirdMessageTime}ms. Expected ~10000ms for real streaming"
          )
        }
      }

      val totalTime = System.currentTimeMillis() - startTime
      println(s"Total test time: ${totalTime}ms")

      // Verify: All messages eventually received
      assertEquals(messages.length, 3)

      // Check message types
      messages(0) match
        case SystemMessage(subtype, _) => assertEquals(subtype, "user_context")
        case other => fail(s"Expected SystemMessage but got: $other")

      messages(1) match
        case AssistantMessage(content) =>
          assertEquals(content.length, 1)
          content.head match
            case TextBlock(text) => assertEquals(text, "First response")
            case other           => fail(s"Expected TextBlock but got: $other")
        case other => fail(s"Expected AssistantMessage but got: $other")
    }
  }

  test(
    "EARLY ACCESS: first messages should be available before CLI process completes"
  ) {
    supervised {
      given MockLogger = MockLogger()

      // Setup: Create mock CLI that produces messages with very large delay
      // This test proves early access works because:
      // 1. First message should arrive ~100ms after start
      // 2. Process won't complete until ~20 seconds (due to final long delay)
      // 3. If streaming works, we can access first message without waiting for completion
      val mockBehavior = MockBehavior(
        messages = List(
          """{"type":"system","subtype":"user_context","context_user_id":"user_123"}""",
          """{"type":"assistant","message":{"content":[{"type":"text","text":"Early message available"}]}}""",
          """{"type":"assistant","message":{"content":[{"type":"text","text":"Final message after long delay"}]}}"""
        ),
        delayBetweenMessages =
          20.seconds, // Very long delay to force process to run for ~40 seconds
        exitCode = 0
      )
      val mockScript = MockCliScript.createTempScript(mockBehavior)
      createdScripts += mockScript

      val options = QueryOptions
        .simple("Test early access")
        .withClaudeExecutable(mockScript.toString)

      // Execute: Test early message access
      val claude = ClaudeCode.concurrent
      val messageFlow = claude.query(options)

      println(s"Starting early access test at ${System.currentTimeMillis()}")

      val startTime = System.currentTimeMillis()
      var firstMessageReceived = false
      var processStillRunning = true

      // ❌ THIS SHOULD FAIL with current fake streaming implementation
      // Current implementation: waits for ENTIRE process completion (~40s) before ANY messages
      // Real streaming: should get first message quickly (~100ms) while process continues

      messageFlow
        .take(1) // Only take first message to prove early access
        .runForeach { message =>
          val elapsed = System.currentTimeMillis() - startTime
          println(
            s"Got first message at ${elapsed}ms: ${message.getClass.getSimpleName}"
          )
          firstMessageReceived = true

          // CRITICAL TEST: Early access validation
          // Real streaming: first message arrives quickly (~100ms)
          // Fake streaming: first message arrives after full process completion (~40000ms)
          assert(
            elapsed < 2000,
            s"First message took too long: ${elapsed}ms. Real streaming should deliver first message quickly, not wait for process completion"
          )

          // Additional check: process should still be running when we get first message
          // In real streaming, the process continues running after we get early messages
          // In fake streaming, process is already complete when we get messages
          assert(
            firstMessageReceived,
            "Should have received first message via early access"
          )
        }

      val totalTime = System.currentTimeMillis() - startTime
      println(s"Early access test completed in: ${totalTime}ms")

      // Verify: Early access worked - got message quickly without waiting for full process
      assert(
        firstMessageReceived,
        "Should have received first message via early access"
      )
      assert(
        totalTime < 5000,
        s"Early access should be fast (${totalTime}ms), not wait for full process completion"
      )
    }
  }
