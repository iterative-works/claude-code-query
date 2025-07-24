// PURPOSE: Utility for creating realistic mock CLI scripts that simulate progressive output
// PURPOSE: Replaces echo-based mocking with realistic CLI behavior including delays and streaming
package works.iterative.claude.direct.internal.testing

import java.io.{File, FileWriter, PrintWriter}
import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.PosixFilePermissions
import scala.util.{Try, Using}
import scala.concurrent.duration.Duration

/** Utility for creating temporary executable scripts that simulate realistic
  * Claude CLI behavior.
  *
  * Unlike /bin/echo which outputs all content at once, MockCliScript generates
  * scripts that:
  *   - Output JSON messages progressively over time
  *   - Include realistic processing delays between messages
  *   - Support different scenarios (success, failure, timeout)
  *   - Handle command-line arguments for different behaviors
  *   - Simulate stderr output and exit codes
  */
object MockCliScript:

  /** Configuration for mock CLI behavior */
  case class MockBehavior(
      messages: List[String],
      delayBetweenMessages: Duration = TestConstants.MockDelays.MOCK_MESSAGE_DELAY_COMFORTABLE,
      exitCode: Int = 0,
      stderrOutput: Option[String] = None,
      simulateHang: Boolean = false,
      hangDuration: Duration = TestConstants.MockDelays.MOCK_HANG_DURATION_STANDARD
  )

  /** Pre-defined common mock behaviors */
  object CommonBehaviors:

    /** Standard successful CLI execution with system, assistant, and result
      * messages
      */
    def successfulQuery(prompt: String = "test"): MockBehavior = MockBehavior(
      messages = List(
        """{"type":"system","subtype":"user_context","context_user_id":"user_123"}""",
        s"""{"type":"assistant","message":{"content":[{"type":"text","text":"Response to: $prompt"}]}}""",
        s"""{"type":"result","subtype":"conversation_result","duration_ms":${TestConstants.MockJsonValues.MOCK_DURATION_MS_STANDARD},"duration_api_ms":${TestConstants.MockJsonValues.MOCK_DURATION_API_MS_STANDARD},"is_error":false,"num_turns":${TestConstants.MockJsonValues.MOCK_NUM_TURNS_SINGLE},"session_id":"${TestConstants.MockJsonValues.MOCK_SESSION_ID}"}"""
      ),
      delayBetweenMessages = TestConstants.MockDelays.MOCK_MESSAGE_DELAY_SLOW
    )

    /** CLI execution that fails with non-zero exit code */
    def processFailure(
        exitCode: Int = 1,
        errorMessage: String = "Mock error"
    ): MockBehavior = MockBehavior(
      messages = List(
        """{"type":"system","subtype":"user_context","context_user_id":"user_123"}"""
      ),
      exitCode = exitCode,
      stderrOutput = Some(errorMessage),
      delayBetweenMessages = TestConstants.MockDelays.MOCK_MESSAGE_DELAY_STANDARD
    )

    /** CLI that outputs malformed JSON to test error handling */
    def malformedJson(): MockBehavior = MockBehavior(
      messages = List(
        """{"type":"system","subtype":"user_context","context_user_id":"user_123"}""",
        """{"type":"user","content":"Hello" invalid_json}""", // Malformed
        """{"type":"assistant","message":{"content":[{"type":"text","text":"Hello back!"}]}}"""
      ),
      delayBetweenMessages = TestConstants.MockDelays.MOCK_MESSAGE_DELAY_MEDIUM
    )

    /** CLI that hangs to test timeout behavior */
    def hangingProcess(
        hangDuration: Duration = TestConstants.MockDelays.MOCK_HANG_DURATION_STANDARD
    ): MockBehavior = MockBehavior(
      messages = List(
        """{"type":"system","subtype":"user_context","context_user_id":"user_123"}"""
      ),
      simulateHang = true,
      hangDuration = hangDuration,
      delayBetweenMessages = Duration(50, "milliseconds")
    )

    /** Complex multi-message sequence for comprehensive testing */
    def complexConversation(): MockBehavior = MockBehavior(
      messages = List(
        """{"type":"system","subtype":"user_context","context_user_id":"user_123"}""",
        """{"type":"user","content":"Complex question"}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"Let me think about this..."}]}}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"Here's my detailed response."}]}}""",
        s"""{"type":"result","subtype":"conversation_result","duration_ms":${TestConstants.MockJsonValues.MOCK_DURATION_MS_COMPLEX},"duration_api_ms":${TestConstants.MockJsonValues.MOCK_DURATION_API_MS_COMPLEX},"is_error":false,"num_turns":${TestConstants.MockJsonValues.MOCK_NUM_TURNS_DOUBLE},"session_id":"${TestConstants.MockJsonValues.MOCK_SESSION_ID}"}"""
      ),
      delayBetweenMessages = TestConstants.MockDelays.MOCK_MESSAGE_DELAY_VERY_SLOW
    )

  /** Creates a temporary executable shell script that simulates Claude CLI
    * behavior.
    *
    * @param behavior
    *   The mock behavior configuration
    * @param scriptName
    *   Optional custom script name (defaults to auto-generated)
    * @return
    *   Path to the created executable script
    */
  def createTempScript(
      behavior: MockBehavior,
      scriptName: Option[String] = None
  ): Path =
    val tempDir = Files.createTempDirectory("mock-claude-")
    val fileName =
      scriptName.getOrElse(s"mock-claude-${System.currentTimeMillis()}")
    val scriptPath = tempDir.resolve(fileName)

    val scriptContent = generateShellScript(behavior)

    // Write script content
    Using.resource(new PrintWriter(new FileWriter(scriptPath.toFile))) {
      writer =>
        writer.write(scriptContent)
    }

    // Make executable
    Files.setPosixFilePermissions(
      scriptPath,
      PosixFilePermissions.fromString("rwxr-xr-x")
    )

    scriptPath

  /** Creates a temporary executable script from a list of JSON messages.
    * Convenience method for simple cases.
    */
  def createSimpleScript(messages: List[String], delayMs: Long = TestConstants.MockDelays.MOCK_MESSAGE_DELAY_COMFORTABLE.toMillis): Path =
    createTempScript(
      MockBehavior(
        messages = messages,
        delayBetweenMessages = Duration(delayMs, "milliseconds")
      )
    )

  /** Generates shell script content based on mock behavior configuration.
    */
  private def generateShellScript(behavior: MockBehavior): String =
    val delayMs = behavior.delayBetweenMessages.toMillis
    val hangMs = behavior.hangDuration.toMillis

    val scriptBuilder = new StringBuilder()
    scriptBuilder.append("#!/bin/bash\n")
    scriptBuilder.append("# Auto-generated mock Claude CLI script\n")
    scriptBuilder.append(
      "# Simulates realistic CLI behavior with progressive output\n\n"
    )

    // Add stderr output if specified
    behavior.stderrOutput.foreach { stderrMsg =>
      scriptBuilder.append(s"""echo "$stderrMsg" >&2\n""")
    }

    // Add progressive message output
    behavior.messages.foreach { message =>
      scriptBuilder.append(s"""echo '$message'\n""")
      if (delayMs > 0) {
        scriptBuilder.append(s"sleep ${delayMs / 1000.0}\n")
      }
    }

    // Add hanging behavior if specified
    if (behavior.simulateHang) {
      scriptBuilder.append(s"sleep ${hangMs / 1000.0}\n")
    }

    // Exit with specified code
    if (behavior.exitCode != 0) {
      scriptBuilder.append(s"exit ${behavior.exitCode}\n")
    }

    scriptBuilder.toString()

  /** Cleanup utility to remove temporary script files. Should be called in test
    * cleanup to avoid leaving temp files.
    */
  def cleanup(scriptPath: Path): Try[Unit] = Try {
    if (Files.exists(scriptPath)) {
      Files.delete(scriptPath)
      // Also try to clean up parent temp directory if it's empty
      val parentDir = scriptPath.getParent
      if (Files.exists(parentDir) && Files.list(parentDir).count() == 0) {
        Files.delete(parentDir)
      }
    }
  }

  /** Creates a script that responds to command line arguments. Useful for
    * testing different CLI argument combinations.
    */
  def createArgResponseScript(responses: Map[String, MockBehavior]): Path =
    val tempDir = Files.createTempDirectory("mock-claude-args-")
    val scriptPath =
      tempDir.resolve(s"mock-claude-args-${System.currentTimeMillis()}")

    val scriptContent = generateArgResponseScript(responses)

    Using.resource(new PrintWriter(new FileWriter(scriptPath.toFile))) {
      writer =>
        writer.write(scriptContent)
    }

    Files.setPosixFilePermissions(
      scriptPath,
      PosixFilePermissions.fromString("rwxr-xr-x")
    )
    scriptPath

  /** Generates a shell script that responds differently based on command line
    * arguments.
    */
  private def generateArgResponseScript(
      responses: Map[String, MockBehavior]
  ): String =
    val scriptBuilder = new StringBuilder()
    scriptBuilder.append("#!/bin/bash\n")
    scriptBuilder.append(
      "# Auto-generated mock Claude CLI script with argument handling\n\n"
    )

    scriptBuilder.append("ARGS=\"$*\"\n\n")

    responses.foreach { case (argPattern, behavior) =>
      scriptBuilder.append(s"""if [[ "$$ARGS" == *"$argPattern"* ]]; then\n""")

      // Add stderr output if specified
      behavior.stderrOutput.foreach { stderrMsg =>
        scriptBuilder.append(s"""  echo "$stderrMsg" >&2\n""")
      }

      // Add progressive message output
      val delayMs = behavior.delayBetweenMessages.toMillis
      behavior.messages.foreach { message =>
        scriptBuilder.append(s"""  echo '$message'\n""")
        if (delayMs > 0) {
          scriptBuilder.append(s"  sleep ${delayMs / 1000.0}\n")
        }
      }

      // Add hanging behavior if specified
      if (behavior.simulateHang) {
        val hangMs = behavior.hangDuration.toMillis
        scriptBuilder.append(s"  sleep ${hangMs / 1000.0}\n")
      }

      // Exit with specified code
      if (behavior.exitCode != 0) {
        scriptBuilder.append(s"  exit ${behavior.exitCode}\n")
      } else {
        scriptBuilder.append("  exit 0\n")
      }

      scriptBuilder.append("fi\n\n")
    }

    // Default case if no arguments match
    scriptBuilder.append("# Default response if no arguments match\n")
    scriptBuilder.append(
      """echo '{"type":"system","subtype":"user_context","context_user_id":"default_user"}'""" + "\n"
    )
    scriptBuilder.append("exit 0\n")

    scriptBuilder.toString()
