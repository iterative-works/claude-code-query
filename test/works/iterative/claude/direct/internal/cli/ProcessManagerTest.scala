// PURPOSE: Tests for direct-style process management with Ox
// PURPOSE: Verifies process execution, Flow streaming, and resource management using supervised scopes
package works.iterative.claude.direct.internal.cli

import ox.*
import works.iterative.claude.core.model.*
import works.iterative.claude.core.{
  ProcessExecutionError,
  ProcessTimeoutError,
  JsonParsingError
}
import works.iterative.claude.direct.internal.cli.{ProcessManager, Logger}
import works.iterative.claude.core.model.QueryOptions

class ProcessManagerTest extends munit.FunSuite:

  // Mock Logger for testing
  class MockLogger extends Logger:
    var debugMessages: List[String] = List.empty
    var infoMessages: List[String] = List.empty
    var warnMessages: List[String] = List.empty
    var errorMessages: List[String] = List.empty

    def debug(msg: String): Unit = debugMessages = msg :: debugMessages
    def info(msg: String): Unit = infoMessages = msg :: infoMessages
    def warn(msg: String): Unit = warnMessages = msg :: warnMessages
    def error(msg: String): Unit = errorMessages = msg :: errorMessages

  test("T4.1: executeProcess returns List of messages from stdout") {
    supervised {
      // Setup: Mock CLI executable outputting valid JSON messages
      given MockLogger = MockLogger()

      // Mock command that outputs system, user, assistant, and result messages
      val mockJsonOutput = List(
        """{"type":"system","subtype":"user_context","context_user_id":"user_123"}""",
        """{"type":"user","content":"Hello Claude!"}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"Hello! How can I help?"}]}}""",
        """{"type":"result","subtype":"conversation_result","duration_ms":1000,"duration_api_ms":500,"is_error":false,"num_turns":1,"session_id":"session_123"}"""
      ).mkString("\n")

      // Create a test script that outputs the mock JSON
      val testScript = "/bin/echo"
      val args = List(mockJsonOutput)
      val options = QueryOptions(
        prompt = "test prompt",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable = None,
        maxTurns = None,
        allowedTools = None,
        disallowedTools = None,
        systemPrompt = None,
        appendSystemPrompt = None,
        mcpTools = None,
        permissionMode = None,
        continueConversation = None,
        resume = None,
        model = None,
        maxThinkingTokens = None,
        timeout = None,
        inheritEnvironment = None,
        environmentVariables = None
      )

      // Execute: Call executeProcess with mocked CLI
      val messages = ProcessManager.executeProcess(testScript, args, options)

      // Verify: Should return List[Message] with parsed messages from stdout
      assertEquals(messages.length, 4)

      // Verify message types in order
      messages(0) match
        case SystemMessage(subtype, _) => assertEquals(subtype, "user_context")
        case other => fail(s"Expected SystemMessage but got: $other")

      messages(1) match
        case UserMessage(content) => assertEquals(content, "Hello Claude!")
        case other => fail(s"Expected UserMessage but got: $other")

      messages(2) match
        case AssistantMessage(content) =>
          assertEquals(content.length, 1)
          content.head match
            case TextBlock(text) => assertEquals(text, "Hello! How can I help?")
            case other           => fail(s"Expected TextBlock but got: $other")
        case other => fail(s"Expected AssistantMessage but got: $other")

      messages(3) match
        case ResultMessage(
              subtype,
              durationMs,
              durationApiMs,
              isError,
              numTurns,
              sessionId,
              _,
              _,
              _
            ) =>
          assertEquals(subtype, "conversation_result")
          assertEquals(durationMs, 1000)
          assertEquals(durationApiMs, 500)
          assertEquals(isError, false)
          assertEquals(numTurns, 1)
          assertEquals(sessionId, "session_123")
        case other => fail(s"Expected ResultMessage but got: $other")

      // Verify: Should log process start and completion
      val logger = summon[MockLogger]
      assert(logger.infoMessages.exists(_.contains("Starting process:")))
      assert(
        logger.infoMessages.exists(
          _.contains("Process completed with exit code: 0")
        )
      )
    }
  }

  test("T4.2: executeProcess captures stderr concurrently") {
    supervised {
      // Setup: Mock CLI executable that writes to both stdout and stderr
      given MockLogger = MockLogger()

      // Mock command that outputs to both stdout and stderr
      val testScript = "/bin/sh"
      val args = List(
        "-c",
        """echo '{"type":"user","content":"test"}' && echo 'stderr output' >&2"""
      )
      val options = QueryOptions(
        prompt = "test prompt",
        cwd = None,
        executable = None,
        executableArgs = None,
        pathToClaudeCodeExecutable = None,
        maxTurns = None,
        allowedTools = None,
        disallowedTools = None,
        systemPrompt = None,
        appendSystemPrompt = None,
        mcpTools = None,
        permissionMode = None,
        continueConversation = None,
        resume = None,
        model = None,
        maxThinkingTokens = None,
        timeout = None,
        inheritEnvironment = None,
        environmentVariables = None
      )

      // Execute: Call executeProcess with mocked CLI that writes to stderr
      val messages = ProcessManager.executeProcess(testScript, args, options)

      // Verify: Should return parsed messages from stdout
      assertEquals(messages.length, 1)
      messages(0) match
        case UserMessage(content) => assertEquals(content, "test")
        case other => fail(s"Expected UserMessage but got: $other")

      // Verify: Should capture stderr concurrently and make it available for error handling
      val logger = summon[MockLogger]
      assert(logger.debugMessages.exists(_.contains("stderr output")))
    }
  }

  test("T5.1: configureProcess sets working directory when provided") {
    // Setup: QueryOptions with cwd specified
    given MockLogger = MockLogger()

    val testCwd = "/tmp"
    val options = QueryOptions(
      prompt = "test prompt",
      cwd = Some(testCwd),
      executable = None,
      executableArgs = None,
      pathToClaudeCodeExecutable = None,
      maxTurns = None,
      allowedTools = None,
      disallowedTools = None,
      systemPrompt = None,
      appendSystemPrompt = None,
      mcpTools = None,
      permissionMode = None,
      continueConversation = None,
      resume = None,
      model = None,
      maxThinkingTokens = None,
      timeout = None,
      inheritEnvironment = None,
      environmentVariables = None
    )

    // Execute: Call configureProcess to configure ProcessBuilder
    val processBuilder =
      ProcessManager.configureProcess("/bin/echo", List("test"), options)

    // Verify: Should set working directory correctly
    assertEquals(processBuilder.directory().getAbsolutePath, testCwd)
  }

  test("T5.2: configureProcess handles missing working directory gracefully") {
    // Setup: QueryOptions with None for cwd
    given MockLogger = MockLogger()

    val options = QueryOptions(
      prompt = "test prompt",
      cwd = None, // No working directory specified
      executable = None,
      executableArgs = None,
      pathToClaudeCodeExecutable = None,
      maxTurns = None,
      allowedTools = None,
      disallowedTools = None,
      systemPrompt = None,
      appendSystemPrompt = None,
      mcpTools = None,
      permissionMode = None,
      continueConversation = None,
      resume = None,
      model = None,
      maxThinkingTokens = None,
      timeout = None,
      inheritEnvironment = None,
      environmentVariables = None
    )

    // Execute: Call configureProcess with no working directory
    val processBuilder =
      ProcessManager.configureProcess("/bin/echo", List("test"), options)

    // Verify: Should use current working directory (processBuilder.directory() should be null)
    assertEquals(processBuilder.directory(), null)
  }

  test("T5.3: configureProcess sets environment variables when specified") {
    // Setup: QueryOptions with custom environment variables
    given MockLogger = MockLogger()

    val customEnvVars =
      Map("TEST_VAR" -> "test_value", "ANOTHER_VAR" -> "another_value")
    val options = QueryOptions(
      prompt = "test prompt",
      cwd = None,
      executable = None,
      executableArgs = None,
      pathToClaudeCodeExecutable = None,
      maxTurns = None,
      allowedTools = None,
      disallowedTools = None,
      systemPrompt = None,
      appendSystemPrompt = None,
      mcpTools = None,
      permissionMode = None,
      continueConversation = None,
      resume = None,
      model = None,
      maxThinkingTokens = None,
      timeout = None,
      inheritEnvironment = None,
      environmentVariables = Some(customEnvVars)
    )

    // Execute: Call configureProcess with custom environment variables
    val processBuilder =
      ProcessManager.configureProcess("/bin/echo", List("test"), options)

    // Verify: Should set environment variables correctly
    val environment = processBuilder.environment()
    assertEquals(environment.get("TEST_VAR"), "test_value")
    assertEquals(environment.get("ANOTHER_VAR"), "another_value")
  }
