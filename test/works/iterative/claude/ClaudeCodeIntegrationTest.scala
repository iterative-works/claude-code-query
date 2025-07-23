// PURPOSE: Integration tests for the main ClaudeCode API methods
// PURPOSE: Verifies end-to-end functionality including error handling and message processing

package works.iterative.claude

import cats.effect.IO
import fs2.Stream
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.testing.TestingLogger
import works.iterative.claude.effectful.internal.cli.CLIDiscovery
import works.iterative.claude.core.{
  CLIError,
  ProcessExecutionError,
  JsonParsingError,
  ProcessTimeoutError,
  ConfigurationError
}
import scala.concurrent.duration.*
import works.iterative.claude.core.model.*

class ClaudeCodeIntegrationTest extends CatsEffectSuite:

  // Create a testing logger for all tests
  given Logger[IO] = TestingLogger.impl[IO]()

  // Test query() method with simple prompt
  test("query with simple prompt returns assistant message and result"):
    val mockClaudePath = "./test/bin/mock-claude"
    val options = QueryOptions(
      prompt = "What is 2+2?",
      pathToClaudeCodeExecutable = Some(mockClaudePath)
    )

    ClaudeCode
      .query(options)
      .compile
      .toList
      .map: messages =>
        assertEquals(
          messages.length,
          3
        ) // SystemMessage + AssistantMessage + ResultMessage

        // Find AssistantMessage (should be second message after SystemMessage)
        val assistantMessage = messages.collectFirst {
          case am: AssistantMessage => am
        }
        assert(assistantMessage.isDefined, "Should contain AssistantMessage")
        assistantMessage.get match
          case AssistantMessage(content) =>
            assertEquals(content.length, 1)
            content.head match
              case TextBlock(text) => assertEquals(text, "4")
              case _               => fail("Expected TextBlock")

        // Find ResultMessage
        val resultMessage = messages.collectFirst { case rm: ResultMessage =>
          rm
        }
        assert(resultMessage.isDefined, "Should contain ResultMessage")
        resultMessage.get match
          case result: ResultMessage =>
            assertEquals(result.subtype, "query")
            assertEquals(result.isError, false)
            assertEquals(result.numTurns, 1)
            assertEquals(result.sessionId, "test-session-123")
            assertEquals(result.totalCostUsd, Some(0.001))
            assert(result.usage.isDefined)

  // Test queryResult() convenience method
  test("queryResult returns only the final answer text"):
    val mockClaudePath = "./test/bin/mock-claude"
    val options = QueryOptions(
      prompt = "What is 5+5?",
      pathToClaudeCodeExecutable = Some(mockClaudePath)
    )

    ClaudeCode
      .queryResult(options)
      .map: result =>
        assertEquals(result, "10")

  // Test querySync() method
  test("querySync blocks and returns completed conversation messages"):
    val mockClaudePath = "./test/bin/mock-claude"
    val options = QueryOptions(
      prompt = "What is 3+3?",
      pathToClaudeCodeExecutable = Some(mockClaudePath)
    )

    ClaudeCode
      .querySync(options)
      .map: messages =>
        assertEquals(
          messages.length,
          3
        ) // SystemMessage + AssistantMessage + ResultMessage

        // Find AssistantMessage
        val assistantMessage = messages.collectFirst {
          case am: AssistantMessage => am
        }
        assert(assistantMessage.isDefined, "Should contain AssistantMessage")
        assistantMessage.get match
          case AssistantMessage(content) =>
            assertEquals(content.length, 1)
            content.head match
              case TextBlock(text) => assertEquals(text, "6")
              case _               => fail("Expected TextBlock")

        // Find ResultMessage
        val resultMessage = messages.collectFirst { case rm: ResultMessage =>
          rm
        }
        assert(resultMessage.isDefined, "Should contain ResultMessage")
        resultMessage.get match
          case result: ResultMessage =>
            assertEquals(result.subtype, "query")
            assertEquals(result.isError, false)
            assertEquals(result.numTurns, 1)
            assertEquals(result.sessionId, "test-session-123")
            assertEquals(result.totalCostUsd, Some(0.001))
            assert(result.usage.isDefined)

  // Test ProcessExecutionError handling
  test(
    "query fails with ProcessExecutionError when CLI returns non-zero exit code"
  ):
    val mockFailClaudePath = "./test/bin/mock-claude-fail"
    val options = QueryOptions(
      prompt = "Test prompt",
      pathToClaudeCodeExecutable = Some(mockFailClaudePath)
    )

    ClaudeCode
      .query(options)
      .compile
      .toList
      .attempt
      .map:
        case Left(error: ProcessExecutionError) =>
          assertEquals(error.exitCode, 1)
          assert(error.stderr.contains("Authentication failed"))
          assert(error.command.contains(mockFailClaudePath))
        case Left(other) =>
          fail(
            s"Expected ProcessExecutionError but got: ${other.getClass.getSimpleName}"
          )
        case Right(_) =>
          fail("Expected query to fail with ProcessExecutionError")

  // Test JsonParsingError handling
  test("query fails with JsonParsingError when CLI outputs malformed JSON"):
    val mockBadJsonPath = "./test/bin/mock-claude-bad-json"
    val options = QueryOptions(
      prompt = "Test prompt",
      pathToClaudeCodeExecutable = Some(mockBadJsonPath)
    )

    ClaudeCode
      .query(options)
      .compile
      .toList
      .attempt
      .map:
        case Left(error: JsonParsingError) =>
          assert(error.lineNumber > 0)
          assert(
            error.line.contains("malformed") || error.line
              .contains("invalid") || error.line.contains("broken")
          )
          assert(error.cause != null)
        case Left(other) =>
          fail(
            s"Expected JsonParsingError but got: ${other.getClass.getSimpleName}"
          )
        case Right(_) =>
          fail("Expected query to fail with JsonParsingError")

  // Test ProcessTimeoutError handling
  test("query fails with ProcessTimeoutError when CLI process hangs"):
    val mockHangPath = "./test/bin/mock-claude-hang"
    val options = QueryOptions(
      prompt = "Test prompt",
      pathToClaudeCodeExecutable = Some(mockHangPath),
      timeout = Some(500.millis) // Very short timeout to avoid slow tests
    )

    ClaudeCode
      .query(options)
      .compile
      .toList
      .attempt
      .map:
        case Left(error: ProcessTimeoutError) =>
          assertEquals(error.timeoutDuration, 500.millis)
          assert(clue(error.command).contains(mockHangPath))
        case Left(other) =>
          fail(
            s"Expected ProcessTimeoutError but got: ${other.getClass.getSimpleName}"
          )
        case Right(_) =>
          fail("Expected query to fail with ProcessTimeoutError")

  // Test ConfigurationError handling
  test("query fails with ConfigurationError on invalid working directory"):
    val mockClaudePath = "./test/bin/mock-claude"
    val invalidCwd = "/this/directory/does/not/exist/anywhere"
    val options = QueryOptions(
      prompt = "Test prompt",
      pathToClaudeCodeExecutable = Some(mockClaudePath),
      cwd = Some(invalidCwd)
    )

    ClaudeCode
      .query(options)
      .compile
      .toList
      .attempt
      .map:
        case Left(error: ConfigurationError) =>
          assertEquals(error.parameter, "cwd")
          assertEquals(error.value, invalidCwd)
          assert(
            error.reason.contains("does not exist") || error.reason
              .contains("not found")
          )
        case Left(other) =>
          fail(
            s"Expected ConfigurationError but got: ${other.getClass.getSimpleName}"
          )
        case Right(_) =>
          fail("Expected query to fail with ConfigurationError")

  // Test QueryOptions defaults
  test("query options are properly constructed with defaults"):
    val options = QueryOptions(prompt = "Test prompt")

    assertEquals(options.prompt, "Test prompt")
    assertEquals(options.cwd, None)
    assertEquals(options.maxTurns, None)
    assertEquals(options.allowedTools, None)
    assertEquals(options.systemPrompt, None)
    assertEquals(options.permissionMode, None)

  // Test QueryOptions with custom values
  test("query options can be configured with custom values"):
    val options = QueryOptions(
      prompt = "Complex query",
      cwd = Some("/custom/path"),
      maxTurns = Some(5),
      allowedTools = Some(List("bash", "read")),
      systemPrompt = Some("You are a helpful assistant"),
      permissionMode = Some(PermissionMode.AcceptEdits)
    )

    assertEquals(options.prompt, "Complex query")
    assertEquals(options.cwd, Some("/custom/path"))
    assertEquals(options.maxTurns, Some(5))
    assertEquals(options.allowedTools, Some(List("bash", "read")))
    assertEquals(options.systemPrompt, Some("You are a helpful assistant"))
    assertEquals(options.permissionMode, Some(PermissionMode.AcceptEdits))

  // Test all message types parsing
  test("query can parse and return all message types"):
    val mockAllMessagesPath = "./test/bin/mock-claude-all-messages"
    val options = QueryOptions(
      prompt = "Test all message types",
      pathToClaudeCodeExecutable = Some(mockAllMessagesPath)
    )

    ClaudeCode
      .query(options)
      .compile
      .toList
      .map: messages =>
        // Should receive 5 messages total
        assert(
          messages.length >= 4,
          s"Expected at least 4 messages, got ${messages.length}"
        )

        // Check we have all message types
        val messageTypes = messages.map(_.getClass.getSimpleName).toSet
        val expectedTypes = Set(
          "UserMessage",
          "AssistantMessage",
          "SystemMessage",
          "ResultMessage"
        )

        expectedTypes.foreach { expectedType =>
          assert(
            messageTypes.contains(expectedType),
            s"Missing message type: $expectedType. Found types: ${messageTypes.mkString(", ")}"
          )
        }

        // Verify specific message content
        val userMessage = messages.collectFirst { case um: UserMessage => um }
        assert(userMessage.isDefined, "Should contain UserMessage")
        assert(
          userMessage.get.content.contains("Hello Claude"),
          "UserMessage should contain expected content"
        )

        val assistantMessage = messages.collectFirst {
          case am: AssistantMessage => am
        }
        assert(assistantMessage.isDefined, "Should contain AssistantMessage")
        assert(
          assistantMessage.get.content.nonEmpty,
          "AssistantMessage should have content"
        )

        val systemMessages = messages.collect { case sm: SystemMessage => sm }
        assert(
          systemMessages.length >= 1,
          "Should contain at least one SystemMessage"
        )
        val initMessage = systemMessages.find(_.subtype == "init")
        assert(initMessage.isDefined, "Should contain system init message")

        val resultMessage = messages.collectFirst { case rm: ResultMessage =>
          rm
        }
        assert(resultMessage.isDefined, "Should contain ResultMessage")
        assertEquals(resultMessage.get.subtype, "query")

  // Test environment variables integration
  test("query with environment variables passes them to subprocess"):
    val mockEnvTestPath = "./test/bin/mock-claude-env-test"
    val options = QueryOptions(
      prompt = "Test environment variables",
      pathToClaudeCodeExecutable = Some(mockEnvTestPath),
      inheritEnvironment = Some(false),
      environmentVariables = Some(Map("TEST_ENV_VAR" -> "test-value-123"))
    )

    ClaudeCode
      .query(options)
      .compile
      .toList
      .map: messages =>
        // Find AssistantMessage that should contain our environment variable
        val assistantMessage = messages.collectFirst {
          case am: AssistantMessage => am
        }
        assert(assistantMessage.isDefined, "Should contain AssistantMessage")

        assistantMessage.get match
          case AssistantMessage(content) =>
            assertEquals(content.length, 1)
            content.head match
              case TextBlock(text) =>
                assert(
                  text.contains("TEST_ENV_VAR is set to: test-value-123"),
                  s"Expected environment variable in response, got: $text"
                )
              case _ => fail("Expected TextBlock")

  // Test environment variable options
  test("query options can be configured with environment variables"):
    val envVars = Map("API_KEY" -> "test-key", "DEBUG" -> "true")
    val options = QueryOptions(
      prompt = "Test environment",
      inheritEnvironment = Some(false),
      environmentVariables = Some(envVars)
    )

    assertEquals(options.inheritEnvironment, Some(false))
    assertEquals(options.environmentVariables, Some(envVars))

  // Test environment inheritance
  test(
    "query with inheritEnvironment=true passes parent environment variables"
  ):
    val mockInheritEnvPath = "./test/bin/mock-claude-inherit-env"
    val options = QueryOptions(
      prompt = "Test environment inheritance",
      pathToClaudeCodeExecutable = Some(mockInheritEnvPath),
      inheritEnvironment = Some(true)
    )

    ClaudeCode
      .query(options)
      .compile
      .toList
      .map: messages =>
        // Find AssistantMessage that should indicate environment was inherited
        val assistantMessage = messages.collectFirst {
          case am: AssistantMessage => am
        }
        assert(assistantMessage.isDefined, "Should contain AssistantMessage")

        assistantMessage.get match
          case AssistantMessage(content) =>
            assertEquals(content.length, 1)
            content.head match
              case TextBlock(text) =>
                assert(
                  text.contains("Environment inherited: PATH is present"),
                  s"Expected environment inheritance confirmation, got: $text"
                )
              case _ => fail("Expected TextBlock")

  // Test custom environment without inheritance
  test(
    "query with inheritEnvironment=false still allows custom environment variables"
  ):
    val mockEnvTestPath = "./test/bin/mock-claude-env-test"
    val options = QueryOptions(
      prompt = "Test custom environment without inheritance",
      pathToClaudeCodeExecutable = Some(mockEnvTestPath),
      inheritEnvironment = Some(false),
      environmentVariables = Some(Map("TEST_ENV_VAR" -> "custom-only-value"))
    )

    ClaudeCode
      .query(options)
      .compile
      .toList
      .map: messages =>
        // Find AssistantMessage - should have our custom var
        val assistantMessage = messages.collectFirst {
          case am: AssistantMessage => am
        }
        assert(assistantMessage.isDefined, "Should contain AssistantMessage")

        assistantMessage.get match
          case AssistantMessage(content) =>
            assertEquals(content.length, 1)
            content.head match
              case TextBlock(text) =>
                assert(
                  text.contains("TEST_ENV_VAR is set to: custom-only-value"),
                  s"Expected custom environment variable, got: $text"
                )
              case _ => fail("Expected TextBlock")

  // Test environment variable override
  test("query with custom environment variables overrides inherited ones"):
    val mockEnvTestPath = "./test/bin/mock-claude-env-test"
    val options = QueryOptions(
      prompt = "Test environment variable override",
      pathToClaudeCodeExecutable = Some(mockEnvTestPath),
      inheritEnvironment = Some(true), // Inherit environment
      environmentVariables = Some(Map("TEST_ENV_VAR" -> "override-value"))
    )

    ClaudeCode
      .query(options)
      .compile
      .toList
      .map: messages =>
        // Find AssistantMessage - should have our override value
        val assistantMessage = messages.collectFirst {
          case am: AssistantMessage => am
        }
        assert(assistantMessage.isDefined, "Should contain AssistantMessage")

        assistantMessage.get match
          case AssistantMessage(content) =>
            assertEquals(content.length, 1)
            content.head match
              case TextBlock(text) =>
                assert(
                  text.contains("TEST_ENV_VAR is set to: override-value"),
                  s"Expected override environment variable, got: $text"
                )
              case _ => fail("Expected TextBlock")

  // Test ANTHROPIC_API_KEY environment variable
  test("query with custom ANTHROPIC_API_KEY through environment variables"):
    // Test that ANTHROPIC_API_KEY can be passed through environmentVariables
    // This verifies the new environment variable functionality
    val options = QueryOptions(
      prompt = "What is the capital of France?",
      inheritEnvironment = Some(false), // Don't inherit from parent
      environmentVariables =
        Some(Map("ANTHROPIC_API_KEY" -> "test-api-key-value"))
    )

    ClaudeCode
      .query(options)
      .compile
      .toList
      .attempt
      .map:
        case Left(error: ProcessExecutionError) =>
          // Should fail with authentication error since test key is invalid
          // or with Node.js not found if Node.js is not installed
          assert(
            error.stderr.contains("Authentication") ||
              error.stderr.contains("API key") ||
              error.stderr.contains("Invalid") ||
              error.stderr.contains("Unauthorized") ||
              error.stderr.contains("node': No such file or directory"),
            s"Expected authentication error or Node.js not found, got: ${error.stderr}"
          )
          assert(error.exitCode != 0, "Should fail with non-zero exit code")
        case Left(other) =>
          fail(
            s"Expected ProcessExecutionError but got: ${other.getClass.getSimpleName}"
          )
        case Right(_) =>
          fail("Expected query to fail with invalid API key")

  // Test real CLI integration (if available)
  test("query with real CLI discovery finds claude and executes successfully"):
    // This test verifies the complete integration: discovery → execution
    // Assumes Claude CLI is installed (as stated in requirements)
    val options = QueryOptions(prompt = "What is 1+1?")

    ClaudeCode
      .query(options)
      .compile
      .toList
      .map: messages =>
        // Verify we got real messages back (not just that something happened)
        assert(messages.nonEmpty, "Should receive messages from Claude CLI")

        // Should contain at least an assistant response
        val hasAssistantMessage = messages.exists:
          case _: AssistantMessage => true
          case _                   => false
        assert(
          hasAssistantMessage,
          "Should receive at least one AssistantMessage"
        )

        // Should contain a result message
        val hasResultMessage = messages.exists:
          case _: ResultMessage => true
          case _                => false
        assert(hasResultMessage, "Should receive a ResultMessage")
