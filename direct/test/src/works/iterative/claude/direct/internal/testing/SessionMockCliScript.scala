// PURPOSE: Mock CLI scripts that simulate the stream-json session protocol
// PURPOSE: Scripts read SDKUserMessage JSON from stdin and write response JSON to stdout
package works.iterative.claude.direct.internal.testing

import java.io.{FileWriter, PrintWriter}
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import scala.util.{Try, Using}

/** Utilities for creating mock CLI scripts that simulate the Claude Code
  * stream-json session protocol.
  *
  * Unlike query-mode mocks (which output all content and exit), session-mode
  * mocks keep running and read prompts from stdin as newline-delimited JSON
  * (SDKUserMessage). For each prompt received, the script writes a sequence of
  * response JSON lines to stdout, ending with a ResultMessage. The script
  * remains alive until stdin is closed, at which point it exits normally.
  */
object SessionMockCliScript:

  /** Configuration for a single turn's response in a session mock. */
  case class TurnResponse(
      /** Messages to write to stdout when this turn's prompt is received. */
      messages: List[String]
  )

  /** Common pre-built responses for typical session scenarios. */
  object CommonResponses:

    val initSessionId = "test-session-id-init-001"

    /** The system init message that the CLI emits when first started. */
    def initMessage(sessionId: String = initSessionId): String =
      s"""{"type":"system","subtype":"init","session_id":"$sessionId","tools":[],"mcp_servers":[]}"""

    /** A simple assistant response with text content. */
    def assistantMessage(text: String): String =
      s"""{"type":"assistant","message":{"content":[{"type":"text","text":"$text"}]}}"""

    /** A ResultMessage signalling end-of-turn. */
    def resultMessage(
        sessionId: String = initSessionId,
        durationMs: Int = 100,
        numTurns: Int = 1
    ): String =
      s"""{"type":"result","subtype":"conversation_result","duration_ms":$durationMs,"duration_api_ms":50,"is_error":false,"num_turns":$numTurns,"session_id":"$sessionId"}"""

    /** A keep_alive message. */
    val keepAliveMessage: String =
      """{"type":"keep_alive"}"""

    /** A stream_event message. */
    def streamEventMessage(event: String = "start"): String =
      s"""{"type":"stream_event","data":{"event":"$event"}}"""

    /** Standard single-turn session: init + one assistant response + result. */
    def singleTurnSession(
        prompt: String = "test",
        sessionId: String = initSessionId
    ): (String, TurnResponse) =
      val init = initMessage(sessionId)
      val response = TurnResponse(
        List(
          assistantMessage(s"Response to: $prompt"),
          resultMessage(sessionId)
        )
      )
      (init, response)

  /** Creates a temporary executable shell script that simulates a session
    * process. The script:
    *   1. Writes the given initMessage to stdout (if provided)
    *   2. Enters a read loop: for each line read from stdin, writes one turn
    *      response to stdout
    *   3. Exits when stdin closes (EOF)
    *
    * The turn responses are cycled: the nth prompt receives responses[n %
    * responses.length]. If responses is empty, the script just echoes a default
    * result for every prompt.
    *
    * @param initMessages
    *   Lines to emit to stdout before reading any stdin (e.g., init message)
    * @param turnResponses
    *   Ordered list of response sequences, one per stdin line received
    * @param captureStdinFile
    *   If provided, each stdin line is appended to this file for verification
    */
  def createSessionScript(
      initMessages: List[String] = Nil,
      turnResponses: List[TurnResponse] = Nil,
      captureStdinFile: Option[Path] = None
  ): Path =
    val tempDir = Files.createTempDirectory("mock-session-claude-")
    val scriptPath = tempDir.resolve(
      s"mock-session-${System.currentTimeMillis()}"
    )
    val content = generateSessionScript(
      initMessages,
      turnResponses,
      captureStdinFile
    )
    Using.resource(new PrintWriter(new FileWriter(scriptPath.toFile))) {
      _.write(content)
    }
    Files.setPosixFilePermissions(
      scriptPath,
      PosixFilePermissions.fromString("rwxr-xr-x")
    )
    scriptPath

  def cleanup(scriptPath: Path): Try[Unit] = Try {
    if Files.exists(scriptPath) then
      Files.delete(scriptPath)
      val parent = scriptPath.getParent
      if Files.exists(parent) && Files.list(parent).count() == 0 then
        Files.delete(parent)
  }

  private def generateSessionScript(
      initMessages: List[String],
      turnResponses: List[TurnResponse],
      captureStdinFile: Option[Path]
  ): String =
    val sb = new StringBuilder
    sb.append("#!/bin/bash\n")
    sb.append("# Auto-generated mock session CLI script\n\n")

    // Emit init messages immediately at startup
    initMessages.foreach { msg =>
      sb.append(s"echo '${escapeSingleQuote(msg)}'\n")
    }

    if turnResponses.nonEmpty then
      val turnCount = turnResponses.length
      sb.append(s"TURN_COUNT=$turnCount\n")
      sb.append("TURN_INDEX=0\n\n")

      // Read loop: for each line from stdin, cycle through the configured responses
      sb.append("while IFS= read -r line; do\n")
      captureStdinFile.foreach { f =>
        sb.append(s"""  printf '%s\\n' "$$line" >> '${f.toAbsolutePath}'\n""")
      }
      sb.append("  IDX=$((TURN_INDEX % TURN_COUNT))\n")
      sb.append("  case \"$IDX\" in\n")
      turnResponses.zipWithIndex.foreach { case (turn, idx) =>
        sb.append(s"    $idx)\n")
        turn.messages.foreach { msg =>
          sb.append(s"      echo '${escapeSingleQuote(msg)}'\n")
        }
        sb.append("      ;;\n")
      }
      sb.append("  esac\n")
      sb.append("  TURN_INDEX=$((TURN_INDEX + 1))\n")
      sb.append("done\n")
    else
      // No responses configured: just read (and optionally capture) stdin until EOF
      sb.append("while IFS= read -r line; do\n")
      captureStdinFile.foreach { f =>
        sb.append(s"""  printf '%s\\n' "$$line" >> '${f.toAbsolutePath}'\n""")
      }
      sb.append("  true\n")
      sb.append("done\n")

    sb.toString()

  /** Creates a script that exits immediately without reading any stdin. Used to
    * test send() to a dead process.
    */
  def createImmediateExitScript(exitCode: Int = 1): Path =
    val content = s"#!/bin/bash\nexit $exitCode\n"
    writeExecutableScript("mock-immediate-exit-", content)

  /** Creates a script that emits one assistant message then crashes with the
    * given exit code before emitting a ResultMessage.
    */
  def createCrashMidTurnScript(exitCode: Int = 1): Path =
    val initMessages = List(CommonResponses.initMessage())
    val partialResponse =
      CommonResponses.assistantMessage("Partial response before crash")
    val content =
      generateCrashMidTurnScript(initMessages, partialResponse, exitCode)
    writeExecutableScript("mock-crash-mid-turn-", content)

  /** Creates a script that completes one full turn (with ResultMessage) then
    * exits before reading the second prompt.
    */
  def createCrashBetweenTurnsScript(exitCode: Int = 1): Path =
    val sessionId = CommonResponses.initSessionId
    val turn1 = TurnResponse(
      List(
        CommonResponses.assistantMessage("Turn 1 complete"),
        CommonResponses.resultMessage(sessionId)
      )
    )
    val content = generateCrashBetweenTurnsScript(
      List(CommonResponses.initMessage()),
      turn1,
      exitCode
    )
    writeExecutableScript("mock-crash-between-turns-", content)

  /** Creates a script that emits a valid assistant message, then an invalid
    * JSON line, then a valid ResultMessage.
    */
  def createMalformedJsonMidTurnScript(): Path =
    val sessionId = CommonResponses.initSessionId
    val messages = List(
      CommonResponses.assistantMessage("Before bad line"),
      "{bad json: not valid}",
      CommonResponses.resultMessage(sessionId)
    )
    val turnResponse = TurnResponse(messages)
    createSessionScript(
      initMessages = List(CommonResponses.initMessage()),
      turnResponses = List(turnResponse)
    )

  private def generateCrashMidTurnScript(
      initMessages: List[String],
      partialMessage: String,
      exitCode: Int
  ): String =
    val sb = new StringBuilder
    sb.append("#!/bin/bash\n")
    sb.append("# Mock script: emits partial output then crashes\n\n")
    initMessages.foreach { msg =>
      sb.append(s"echo '${escapeSingleQuote(msg)}'\n")
    }
    // Wait for one line of stdin, then emit partial response and crash
    sb.append("read -r _line\n")
    sb.append(s"echo '${escapeSingleQuote(partialMessage)}'\n")
    sb.append(s"exit $exitCode\n")
    sb.toString()

  private def generateCrashBetweenTurnsScript(
      initMessages: List[String],
      turn1: TurnResponse,
      exitCode: Int
  ): String =
    val sb = new StringBuilder
    sb.append("#!/bin/bash\n")
    sb.append("# Mock script: completes one turn then crashes\n\n")
    initMessages.foreach { msg =>
      sb.append(s"echo '${escapeSingleQuote(msg)}'\n")
    }
    // Read first prompt, respond fully
    sb.append("read -r _line\n")
    turn1.messages.foreach { msg =>
      sb.append(s"echo '${escapeSingleQuote(msg)}'\n")
    }
    // Exit before reading second prompt
    sb.append(s"exit $exitCode\n")
    sb.toString()

  private def writeExecutableScript(prefix: String, content: String): Path =
    val tempDir = Files.createTempDirectory(prefix)
    val scriptPath = tempDir.resolve(s"mock-${System.currentTimeMillis()}")
    Using.resource(new PrintWriter(new FileWriter(scriptPath.toFile))) {
      _.write(content)
    }
    Files.setPosixFilePermissions(
      scriptPath,
      PosixFilePermissions.fromString("rwxr-xr-x")
    )
    scriptPath

  private def escapeSingleQuote(s: String): String =
    s.replace("'", "'\\''")
