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
import java.util.concurrent.{CountDownLatch, TimeUnit, ConcurrentLinkedQueue}
import scala.collection.concurrent.TrieMap
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import java.lang.management.ManagementFactory
import scala.util.{Try, Success, Failure}
import scala.concurrent.duration.*

class ProcessManagerTest extends munit.FunSuite:

  // Process resource tracking utilities for cleanup verification
  private def countRunningProcesses(): Int =
    Try {
      val processBuilder =
        new ProcessBuilder("ps", "-eo", "pid,ppid,comm", "--no-headers")
      val process = processBuilder.start()
      val reader = new java.io.BufferedReader(
        new java.io.InputStreamReader(process.getInputStream)
      )
      var count = 0
      var line: String = null
      while { line = reader.readLine(); line != null } do
        // Count processes related to our tests (sh, echo, sleep, etc.)
        if line.contains("sh") || line.contains("echo") || line.contains(
            "sleep"
          )
        then count += 1
      reader.close()
      process.waitFor()
      count
    }.getOrElse(0)

  // Check for zombie processes specifically
  private def countZombieProcesses(): Int =
    Try {
      val processBuilder =
        new ProcessBuilder("ps", "-eo", "pid,stat", "--no-headers")
      val process = processBuilder.start()
      val reader = new java.io.BufferedReader(
        new java.io.InputStreamReader(process.getInputStream)
      )
      var zombieCount = 0
      var line: String = null
      while { line = reader.readLine(); line != null } do
        if line.contains("Z") then zombieCount += 1
      reader.close()
      process.waitFor()
      zombieCount
    }.getOrElse(0)

  // Eventually utility for asynchronous resource cleanup verification
  private def eventually[T](
      assertion: => T,
      timeout: Duration = 5.seconds,
      interval: Duration = 100.millis
  ): T =
    val startTime = System.currentTimeMillis()
    val timeoutMs = timeout.toMillis
    var lastException: Option[Throwable] = None

    while (System.currentTimeMillis() - startTime) < timeoutMs do
      try return assertion
      catch
        case e: Throwable =>
          lastException = Some(e)
          Thread.sleep(interval.toMillis)

    lastException match
      case Some(e) => throw e
      case None    =>
        throw new RuntimeException(s"Eventually timed out after $timeout")

  // Thread-safe Mock Logger for testing with synchronization capabilities
  class MockLogger extends Logger:
    private val debugMessages = new ConcurrentLinkedQueue[String]()
    private val infoMessages = new ConcurrentLinkedQueue[String]()
    private val warnMessages = new ConcurrentLinkedQueue[String]()
    private val errorMessages = new ConcurrentLinkedQueue[String]()

    // Optional synchronization points for testing
    private val stderrLatch = new CountDownLatch(1)
    private val processCompletionLatch = new CountDownLatch(1)

    def debug(msg: String): Unit =
      debugMessages.add(msg)
      if msg.contains("stderr") then stderrLatch.countDown()

    def info(msg: String): Unit =
      infoMessages.add(msg)
      if msg.contains("Process completed") then
        processCompletionLatch.countDown()

    def warn(msg: String): Unit = warnMessages.add(msg)
    def error(msg: String): Unit = errorMessages.add(msg)

    // Thread-safe accessors that return immutable collections
    def getDebugMessages: List[String] = debugMessages.asScala.toList
    def getInfoMessages: List[String] = infoMessages.asScala.toList
    def getWarnMessages: List[String] = warnMessages.asScala.toList
    def getErrorMessages: List[String] = errorMessages.asScala.toList

    // Synchronization helpers for tests
    def waitForStderrCapture(timeoutSeconds: Long = 5): Boolean =
      stderrLatch.await(timeoutSeconds, TimeUnit.SECONDS)

    def waitForProcessCompletion(timeoutSeconds: Long = 5): Boolean =
      processCompletionLatch.await(timeoutSeconds, TimeUnit.SECONDS)

    // Helper methods for assertions with proper synchronization
    def hasDebugMessage(predicate: String => Boolean): Boolean =
      getDebugMessages.exists(predicate)

    def hasInfoMessage(predicate: String => Boolean): Boolean =
      getInfoMessages.exists(predicate)

    def hasErrorMessage(predicate: String => Boolean): Boolean =
      getErrorMessages.exists(predicate)

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
      assert(logger.hasInfoMessage(_.contains("Starting process:")))
      assert(logger.waitForProcessCompletion())
      assert(
        logger.hasInfoMessage(
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
      // Wait for stderr capture to complete before asserting
      assert(logger.waitForStderrCapture())
      assert(logger.hasDebugMessage(_.contains("stderr output")))
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

  test(
    "T5.4: configureProcess inherits environment when inheritEnvironment is true"
  ) {
    // Setup: QueryOptions with inheritEnvironment=true
    given MockLogger = MockLogger()

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
      inheritEnvironment = Some(true),
      environmentVariables = None
    )

    // Execute: Call configureProcess with inheritEnvironment=true
    val processBuilder =
      ProcessManager.configureProcess("/bin/echo", List("test"), options)

    // Verify: Should preserve parent environment variables
    val environment = processBuilder.environment()
    // Test with PATH which should exist in parent environment
    assert(environment.containsKey("PATH"))
    assert(environment.get("PATH") != null)
    assert(environment.get("PATH").nonEmpty)
  }

  test(
    "T5.5: configureProcess isolates environment when inheritEnvironment is false"
  ) {
    // Setup: QueryOptions with inheritEnvironment=false
    given MockLogger = MockLogger()

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
      inheritEnvironment = Some(false),
      environmentVariables = None
    )

    // Execute: Call configureProcess with inheritEnvironment=false
    val processBuilder =
      ProcessManager.configureProcess("/bin/echo", List("test"), options)

    // Verify: Should start with clean environment (no inherited variables)
    val environment = processBuilder.environment()
    // When inheritEnvironment is false, the environment should be cleared
    // PATH should not be present unless explicitly set
    assert(!environment.containsKey("PATH"))
  }

  test("T4.3: executeProcess handles process failure with exit codes") {
    supervised {
      // Setup: Mock CLI that exits with non-zero code and stderr
      given MockLogger = MockLogger()

      // Mock command that exits with failure (exit code 1) and writes to stderr
      val testScript = "/bin/sh"
      val args = List("-c", "echo 'error message' >&2 && exit 1")
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

      // Execute: Call executeProcess with failing CLI
      val exception = intercept[ProcessExecutionError] {
        ProcessManager.executeProcess(testScript, args, options)
      }

      // Verify: Should throw ProcessExecutionError with exit code and stderr content
      assertEquals(exception.exitCode, 1)
      assertEquals(exception.command, testScript :: args)

      // Verify: Should capture and log stderr information
      val logger = summon[MockLogger]
      // Wait for process completion and stderr capture
      assert(logger.waitForProcessCompletion())
      assert(logger.hasDebugMessage(_.contains("error message")))
      assert(
        logger.hasInfoMessage(
          _.contains("Process completed with exit code: 1")
        )
      )
    }
  }

  test("T4.4: executeProcess applies timeout when specified") {
    supervised {
      // Setup: Mock hanging process with short timeout
      given MockLogger = MockLogger()

      // Mock command that hangs (sleeps for a long time)
      val testScript = "/bin/sh"
      val args = List("-c", "sleep 30") // Sleep for 30 seconds
      val timeoutDuration =
        scala.concurrent.duration.FiniteDuration(500, "milliseconds")
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
        timeout = Some(timeoutDuration),
        inheritEnvironment = None,
        environmentVariables = None
      )

      // Execute: Call executeProcess with hanging CLI and short timeout
      // Measure actual timeout duration to verify timing precision
      val startTime = System.currentTimeMillis()
      val exception = intercept[ProcessTimeoutError] {
        ProcessManager.executeProcess(testScript, args, options)
      }
      val actualDuration = System.currentTimeMillis() - startTime

      // Verify: Should throw ProcessTimeoutError after specified duration
      assertEquals(exception.timeoutDuration, timeoutDuration)
      assertEquals(exception.command, testScript :: args)

      // Verify: Timeout should occur within reasonable bounds (allow for some overhead)
      val expectedMs = timeoutDuration.toMillis
      assert(
        actualDuration >= expectedMs,
        s"Timeout too fast: ${actualDuration}ms < ${expectedMs}ms"
      )
      assert(
        actualDuration <= expectedMs + 200,
        s"Timeout too slow: ${actualDuration}ms > ${expectedMs + 200}ms"
      )

      // Verify: Should log timeout information with proper synchronization
      val logger = summon[MockLogger]
      assert(logger.hasErrorMessage(_.contains("Process timed out")))
    }
  }

  test("T4.5: executeProcess handles JSON parsing errors gracefully") {
    supervised {
      // Setup: Mock CLI outputting malformed JSON
      given MockLogger = MockLogger()

      // Mock command that outputs malformed JSON mixed with valid JSON
      val mockOutput = List(
        """{"type":"system","subtype":"user_context","context_user_id":"user_123"}""", // Valid JSON
        """{"type":"user","content":"Hello" invalid_json}""", // Malformed JSON
        """{"type":"assistant","message":{"content":[{"type":"text","text":"Hello!"}]}}""" // Valid JSON
      ).mkString("\n")

      val testScript = "/bin/echo"
      val args = List(mockOutput)
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

      // Execute: Call executeProcess with CLI that outputs malformed JSON
      val messages = ProcessManager.executeProcess(testScript, args, options)

      // Verify: Should continue processing and return valid messages despite parsing errors
      assertEquals(
        messages.length,
        2
      ) // Only the valid JSON messages should be parsed

      // Verify first message (SystemMessage)
      messages(0) match
        case SystemMessage(subtype, _) => assertEquals(subtype, "user_context")
        case other => fail(s"Expected SystemMessage but got: $other")

      // Verify second message (AssistantMessage)
      messages(1) match
        case AssistantMessage(content) =>
          assertEquals(content.length, 1)
          content.head match
            case TextBlock(text) => assertEquals(text, "Hello!")
            case other           => fail(s"Expected TextBlock but got: $other")
        case other => fail(s"Expected AssistantMessage but got: $other")

      // Verify: Should log JSON parsing error but continue processing
      val logger = summon[MockLogger]
      assert(logger.waitForProcessCompletion())
      assert(logger.hasErrorMessage(_.contains("JSON parsing failed")))
      assert(
        logger.hasInfoMessage(
          _.contains("Process completed with exit code: 0")
        )
      )
    }
  }

  test("T4.6: executeProcess logs process lifecycle events") {
    supervised {
      // Setup: Any simple command with logging verification
      given MockLogger = MockLogger()

      val mockOutput = """{"type":"user","content":"test message"}"""
      val testScript = "/bin/echo"
      val args = List(mockOutput)
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

      // Execute: Call executeProcess and verify logging
      val messages = ProcessManager.executeProcess(testScript, args, options)

      // Verify: Process executes successfully
      assertEquals(messages.length, 1)
      messages(0) match
        case UserMessage(content) => assertEquals(content, "test message")
        case other => fail(s"Expected UserMessage but got: $other")

      // Verify: Should log process lifecycle events throughout execution
      val logger = summon[MockLogger]

      // Wait for process completion to ensure all logging is done
      assert(logger.waitForProcessCompletion())

      // Should log process start
      assert(
        logger.hasInfoMessage(msg =>
          msg.contains("Starting process:") &&
            msg.contains("/bin/echo") &&
            msg.contains(mockOutput)
        )
      )

      // Should log JSON parsing attempts (via parseJsonLineWithContextWithLogging)
      assert(logger.hasDebugMessage(_.contains("Parsing JSON line")))
      assert(
        logger.hasDebugMessage(
          _.contains("Successfully parsed message of type user")
        )
      )

      // Should log process completion
      assert(
        logger.hasInfoMessage(
          _.contains("Process completed with exit code: 0")
        )
      )
    }
  }

  // === Resource Cleanup Exception Testing ===
  // These tests verify that process resources are properly cleaned up when exceptions occur
  // during JSON parsing, process execution failures, and timeout scenarios.

  test(
    "CLEANUP-1: should cleanup process resources when JSON parsing fails with large malformed output"
  ) {
    supervised {
      given MockLogger = MockLogger()

      // Capture initial zombie process count
      val initialZombieCount = countZombieProcesses()

      // Create large amount of malformed JSON that will cause parsing failures
      val malformedOutput = "invalid json " * 1000 + "\n" +
        """{"type":"user","content":"valid after invalid"}""" + "\n" +
        "more invalid json" * 500

      val testScript = "/bin/echo"
      val args = List(malformedOutput)
      val options = QueryOptions(
        prompt = "test cleanup",
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

      // Execute process - should continue despite JSON parsing errors
      val messages = ProcessManager.executeProcess(testScript, args, options)

      // Verify that valid JSON was still parsed
      assertEquals(messages.length, 1)
      messages(0) match
        case UserMessage(content) =>
          assertEquals(content, "valid after invalid")
        case other => fail(s"Expected UserMessage but got: $other")

      // Verify JSON parsing errors were logged
      val logger = summon[MockLogger]
      assert(logger.waitForProcessCompletion())
      assert(logger.hasErrorMessage(_.contains("JSON parsing failed")))

      // Verify no zombie processes remain after cleanup
      eventually {
        val currentZombieCount = countZombieProcesses()
        assertEquals(
          currentZombieCount,
          initialZombieCount,
          s"Expected zombie count to return to $initialZombieCount but got $currentZombieCount"
        )
      }
    }
  }

  test(
    "CLEANUP-2: should cleanup process resources when process execution fails"
  ) {
    supervised {
      given MockLogger = MockLogger()

      val initialZombieCount = countZombieProcesses()

      // Command that will fail with exit code 1
      val testScript = "/bin/sh"
      val args =
        List("-c", "echo 'some output'; echo 'stderr content' >&2; exit 1")
      val options = QueryOptions(
        prompt = "test cleanup on failure",
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

      // Execute process - should throw ProcessExecutionError
      val exception = intercept[ProcessExecutionError] {
        ProcessManager.executeProcess(testScript, args, options)
      }

      // Verify exception details
      assertEquals(exception.exitCode, 1)
      assertEquals(exception.command.head, testScript)

      // Verify stderr was captured
      val logger = summon[MockLogger]
      assert(logger.waitForProcessCompletion())
      assert(logger.hasDebugMessage(_.contains("stderr content")))

      // Verify no zombie processes remain after exception cleanup
      eventually {
        val currentZombieCount = countZombieProcesses()
        assertEquals(
          currentZombieCount,
          initialZombieCount,
          s"Expected zombie count to return to $initialZombieCount but got $currentZombieCount"
        )
      }
    }
  }

  test("CLEANUP-3: should cleanup process resources when timeout occurs") {
    supervised {
      given MockLogger = MockLogger()

      val initialZombieCount = countZombieProcesses()

      // Command that will hang for a long time
      val testScript = "/bin/sh"
      val args = List("-c", "sleep 10") // Sleep longer than timeout
      val timeoutDuration =
        scala.concurrent.duration.FiniteDuration(200, "milliseconds")
      val options = QueryOptions(
        prompt = "test cleanup on timeout",
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
        timeout = Some(timeoutDuration),
        inheritEnvironment = None,
        environmentVariables = None
      )

      // Execute process - should throw ProcessTimeoutError
      val exception = intercept[ProcessTimeoutError] {
        ProcessManager.executeProcess(testScript, args, options)
      }

      // Verify timeout exception details
      assertEquals(exception.timeoutDuration, timeoutDuration)
      assertEquals(exception.command.head, testScript)

      // Verify timeout was logged
      val logger = summon[MockLogger]
      assert(logger.hasErrorMessage(_.contains("Process timed out")))

      // Verify no zombie processes remain after timeout cleanup
      eventually {
        val currentZombieCount = countZombieProcesses()
        assertEquals(
          currentZombieCount,
          initialZombieCount,
          s"Expected zombie count to return to $initialZombieCount but got $currentZombieCount"
        )
      }
    }
  }

  test(
    "CLEANUP-4: should cleanup resources during concurrent operations with mixed failures"
  ) {
    supervised {
      given MockLogger = MockLogger()

      val initialZombieCount = countZombieProcesses()

      val baseOptions = QueryOptions(
        prompt = "concurrent test",
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

      // Run multiple concurrent operations with different failure modes
      val operations = List(
        // Valid operation
        fork {
          Try {
            ProcessManager.executeProcess(
              "/bin/echo",
              List("""{"type":"user","content":"valid"}"""),
              baseOptions
            )
          }
        },
        // JSON parsing failure
        fork {
          Try {
            ProcessManager.executeProcess(
              "/bin/echo",
              List("invalid json content"),
              baseOptions
            )
          }
        },
        // Process execution failure
        fork {
          Try {
            ProcessManager.executeProcess(
              "/bin/sh",
              List("-c", "exit 2"),
              baseOptions
            )
          }
        },
        // Timeout failure
        fork {
          Try {
            val timeoutOptions = baseOptions.copy(timeout = Some(100.millis))
            ProcessManager.executeProcess(
              "/bin/sh",
              List("-c", "sleep 1"),
              timeoutOptions
            )
          }
        }
      )

      // Wait for all operations to complete
      val results = operations.map(_.join())

      // Verify we got expected mix of success and failures
      val successes = results.count(_.isSuccess)
      val failures = results.count(_.isFailure)

      assert(successes >= 1, s"Expected at least 1 success, got $successes")
      assert(
        failures >= 2,
        s"Expected at least 2 failures, got $failures"
      ) // Some operations might succeed due to timing
      assertEquals(results.length, 4, "Expected 4 total operations")

      // Verify all processes were cleaned up despite concurrent failures
      eventually {
        val currentZombieCount = countZombieProcesses()
        assertEquals(
          currentZombieCount,
          initialZombieCount,
          s"Expected zombie count to return to $initialZombieCount but got $currentZombieCount after concurrent operations"
        )
      }
    }
  }

  test(
    "CLEANUP-5: should cleanup resources when process failure occurs during stderr capture"
  ) {
    supervised {
      given MockLogger = MockLogger()

      val initialZombieCount = countZombieProcesses()

      // Command that outputs to both stdout and stderr then fails
      val testScript = "/bin/sh"
      val args = List(
        "-c",
        """
        echo '{"type":"user","content":"partial output"}'
        echo 'error line 1' >&2
        sleep 0.1
        echo 'error line 2' >&2  
        echo 'some more stdout'
        exit 3
      """
      )
      val options = QueryOptions(
        prompt = "test stderr cleanup",
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

      // Execute process - should throw ProcessExecutionError
      val exception = intercept[ProcessExecutionError] {
        ProcessManager.executeProcess(testScript, args, options)
      }

      // Verify exception details
      assertEquals(exception.exitCode, 3)

      // Verify stderr was captured concurrently
      val logger = summon[MockLogger]
      assert(logger.waitForStderrCapture())
      assert(logger.waitForProcessCompletion())
      assert(logger.hasDebugMessage(_.contains("error line")))

      // Verify no zombie processes remain after concurrent stderr capture cleanup
      eventually {
        val currentZombieCount = countZombieProcesses()
        assertEquals(
          currentZombieCount,
          initialZombieCount,
          s"Expected zombie count to return to $initialZombieCount but got $currentZombieCount"
        )
      }
    }
  }
