// PURPOSE: Tests for direct-style ClaudeCode main API using both sync and async interfaces
// PURPOSE: Verifies both blocking sync API and streaming async API with Ox structured concurrency
package works.iterative.claude.direct

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.core.{
  ProcessExecutionError,
  ProcessTimeoutError,
  JsonParsingError,
  ConfigurationError
}
import works.iterative.claude.direct.internal.testing.{
  MockCliScript,
  TestConstants
}
import java.nio.file.Path
import scala.util.{Try, Using}
import scala.concurrent.duration.Duration

class ClaudeCodeTest extends munit.FunSuite:

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

  test("sync API: should ask simple question with blocking call") {
    given MockLogger = MockLogger()

    // Setup: Create realistic mock CLI script
    val mockBehavior =
      MockCliScript.CommonBehaviors.successfulQuery("What is 2+2?")
    val mockScript = MockCliScript.createTempScript(mockBehavior)
    createdScripts += mockScript

    val options = QueryOptions
      .simple("What is 2+2?")
      .withClaudeExecutable(mockScript.toString)

    // Execute: Call sync API - no supervised block needed
    val result = ClaudeCode.queryResult(options)

    // Verify: Should get text response
    assertEquals(result, "Response to: What is 2+2?")
  }

  test("sync API: should handle errors with blocking call") {
    given MockLogger = MockLogger()

    val options = QueryOptions
      .simple("Test prompt")
      .withClaudeExecutable(
        "/bin/false"
      ) // Command that always exits with code 1

    // Execute: Call sync API with failing CLI - should propagate error
    val exception = intercept[ProcessExecutionError] {
      ClaudeCode.queryResult(options)
    }

    // Verify: Should fail with ProcessExecutionError
    assertEquals(exception.exitCode, 1)
    assert(exception.command.contains("/bin/false"))
  }

  test("async API: should return streaming Flow for concurrent operations") {
    supervised {
      given MockLogger = MockLogger()

      // Setup: Create realistic mock CLI script
      val mockBehavior =
        MockCliScript.CommonBehaviors.successfulQuery("Hello Claude!")
      val mockScript = MockCliScript.createTempScript(mockBehavior)
      createdScripts += mockScript

      val options = QueryOptions
        .simple("Hello Claude!")
        .withClaudeExecutable(mockScript.toString)

      // Execute: Call async API within supervised scope
      val claude = ClaudeCode.concurrent
      val messageFlow = claude.query(options)
      val messages = messageFlow.runToList()

      // Verify: Should return Flow[Message] with all expected message types
      assertEquals(messages.length, 3)

      // Check message types
      messages(0) match
        case SystemMessage(subtype, _) => assertEquals(subtype, "user_context")
        case other => fail(s"Expected SystemMessage but got: $other")

      messages(1) match
        case AssistantMessage(content) =>
          assertEquals(content.length, 1)
          content.head match
            case TextBlock(text) =>
              assertEquals(text, "Response to: Hello Claude!")
            case other => fail(s"Expected TextBlock but got: $other")
        case other => fail(s"Expected AssistantMessage but got: $other")

      messages(2) match
        case ResultMessage(subtype, _, _, isError, _, _, _, _, _) =>
          assertEquals(subtype, "conversation_result")
          assertEquals(isError, false)
        case other => fail(s"Expected ResultMessage but got: $other")
    }
  }

  test("async API: should support concurrent queries") {
    supervised {
      given MockLogger = MockLogger()

      // Setup: Create mock scripts for different queries
      val mockBehavior1 =
        MockCliScript.CommonBehaviors.successfulQuery("Query 1")
      val mockBehavior2 =
        MockCliScript.CommonBehaviors.successfulQuery("Query 2")
      val mockScript1 = MockCliScript.createTempScript(mockBehavior1)
      val mockScript2 = MockCliScript.createTempScript(mockBehavior2)
      createdScripts += mockScript1
      createdScripts += mockScript2

      val options1 = QueryOptions
        .simple("Query 1")
        .withClaudeExecutable(mockScript1.toString)
      val options2 = QueryOptions
        .simple("Query 2")
        .withClaudeExecutable(mockScript2.toString)

      // Execute: Run concurrent queries using fork
      val claude = ClaudeCode.concurrent

      val result1Fork = fork { claude.queryResult(options1) }
      val result2Fork = fork { claude.queryResult(options2) }

      val result1 = result1Fork.join()
      val result2 = result2Fork.join()

      // Verify: Both queries should complete successfully
      assertEquals(result1, "Response to: Query 1")
      assertEquals(result2, "Response to: Query 2")
    }
  }

  test("async API: should handle process execution errors") {
    supervised {
      given MockLogger = MockLogger()

      val options = QueryOptions
        .simple("Test prompt")
        .withClaudeExecutable(
          "/bin/false"
        ) // Command that always exits with code 1

      // Execute: Call async API with failing CLI
      val claude = ClaudeCode.concurrent
      val exception = intercept[ProcessExecutionError] {
        val messageFlow = claude.query(options)
        messageFlow.runToList() // Force evaluation
      }

      // Verify: Should fail with ProcessExecutionError
      assertEquals(exception.exitCode, 1)
      assert(exception.command.contains("/bin/false"))
    }
  }

  test("sync API: should validate configuration before execution") {
    given MockLogger = MockLogger()

    val options = QueryOptions
      .simple("Hello Claude!")
      .withCwd(
        "/this/directory/definitely/does/not/exist"
      ) // Invalid working directory
      .withClaudeExecutable("/bin/echo") // Valid executable

    // Execute: Call sync API with invalid working directory
    val exception = intercept[ConfigurationError] {
      ClaudeCode.queryResult(options)
    }

    // Verify: Should fail with ConfigurationError
    assert(exception.parameter.contains("cwd"))
    assert(
      exception.value.contains("/this/directory/definitely/does/not/exist")
    )
  }

  test("should use fluent API for QueryOptions configuration") {
    given MockLogger = MockLogger()

    val mockBehavior =
      MockCliScript.CommonBehaviors.successfulQuery("Complex query")
    val mockScript = MockCliScript.createTempScript(mockBehavior)
    createdScripts += mockScript

    // Setup: Use fluent API to configure options
    val options = QueryOptions
      .simple("Complex query")
      .withMaxTurns(3)
      .withModel("claude-3-5-sonnet-20241022")
      .withPermissionMode(PermissionMode.AcceptEdits)
      .withClaudeExecutable(mockScript.toString)

    // Execute: Call sync API with fluent configuration
    val result = ClaudeCode.queryResult(options)

    // Verify: Should execute successfully with configured options
    assertEquals(result, "Response to: Complex query")
  }
