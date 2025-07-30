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

  test("STREAMING: messages should arrive as CLI process produces them, not after completion") {
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
        delayBetweenMessages = 5.seconds, // Much longer delay to prove streaming
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
        println(s"Got message at ${elapsed}ms: ${message.getClass.getSimpleName}")
        messages += message
        
        // CRITICAL TEST: If this is real streaming, first message should arrive quickly (~100ms)
        // But the TOTAL time from start to finish should be ~15 seconds (3 messages * 5 second delays)
        // If fake streaming: ALL messages arrive quickly after process completes (~15 seconds total wait)
        if (messages.size == 1) {
          val firstMessageTime = elapsed
          println(s"First message arrived at ${firstMessageTime}ms")
          
          // This is the key insight: fake streaming shows messages arriving quickly 
          // but only AFTER the entire process has completed
          // Real streaming would show first message arriving quickly (~100ms)
          // AND subsequent messages arriving with the actual delays (5 seconds apart)
          
          fail(s"FAKE STREAMING DETECTED: First message arrived at ${firstMessageTime}ms, but the CLI script has 5-second delays. This means all messages were collected after process completion and then streamed from memory via Flow.fromIterable(). Real streaming should show first message ~100ms, second at ~5100ms, third at ~10100ms.")
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
            case other => fail(s"Expected TextBlock but got: $other")
        case other => fail(s"Expected AssistantMessage but got: $other")
    }
  }