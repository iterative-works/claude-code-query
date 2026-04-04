// PURPOSE: Integration test for SDKUserMessage round-trip through a mock CLI process
// PURPOSE: Validates the JSON format works end-to-end with stdin/stdout piping

package works.iterative.claude.core.model

import munit.FunSuite
import io.circe.syntax.*
import works.iterative.claude.core.parsing.JsonParser

class SDKUserMessageRoundTripTest extends FunSuite:

  private def assumeUnixSystem(): Unit =
    val osName = System.getProperty("os.name").toLowerCase
    assume(
      !osName.contains("windows"),
      s"Test requires Unix-like system, but running on: $osName"
    )

  test("Round-trip: encode SDKUserMessage, mock CLI echoes response, parse it"):
    assumeUnixSystem()

    val msg = SDKUserMessage("What is 2+2?", "session-abc", None)
    val jsonLine = msg.asJson.noSpaces

    // Mock script reads stdin line, then writes an assistant message and result
    val script =
      s"""#!/bin/bash
         |read INPUT_LINE
         |echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"The answer is 4."}]}}'
         |echo '{"type":"result","subtype":"success","duration_ms":100,"duration_api_ms":80,"is_error":false,"num_turns":1,"session_id":"session-abc","result":"The answer is 4."}'
         |""".stripMargin

    val scriptFile =
      java.nio.file.Files.createTempFile("mock-cli-", ".sh")
    java.nio.file.Files.write(scriptFile, script.getBytes)
    java.nio.file.Files.setPosixFilePermissions(
      scriptFile,
      java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
    )

    try
      val process = new ProcessBuilder(scriptFile.toString)
        .redirectErrorStream(false)
        .start()

      // Write the SDKUserMessage JSON to stdin
      val writer = process.getOutputStream
      writer.write((jsonLine + "\n").getBytes)
      writer.flush()
      writer.close()

      // Read stdout lines
      val reader = scala.io.Source.fromInputStream(process.getInputStream)
      val lines = reader.getLines().toList
      reader.close()

      process.waitFor()

      assertEquals(lines.length, 2, s"Expected 2 output lines, got: $lines")

      val parsed = lines.flatMap(JsonParser.parseJsonLine)
      assertEquals(parsed.length, 2)

      parsed(0) match
        case AssistantMessage(content) =>
          assertEquals(content.length, 1)
          content.head match
            case TextBlock(text) => assertEquals(text, "The answer is 4.")
            case other           => fail(s"Expected TextBlock, got $other")
        case other => fail(s"Expected AssistantMessage, got $other")

      parsed(1) match
        case rm: ResultMessage =>
          assertEquals(rm.subtype, "success")
          assertEquals(rm.sessionId, "session-abc")
          assertEquals(rm.result, Some("The answer is 4."))
        case other => fail(s"Expected ResultMessage, got $other")
    finally java.nio.file.Files.deleteIfExists(scriptFile): Unit

  test(
    "Mock session protocol: init message, stdin write, assistant + result response"
  ):
    assumeUnixSystem()

    val msg = SDKUserMessage("Hello", "pending", None)
    val jsonLine = msg.asJson.noSpaces

    // Mock script: outputs init, reads stdin, then responds
    val script =
      s"""#!/bin/bash
         |echo '{"type":"system","subtype":"init","session_id":"real-session-42","model":"claude-sonnet","tools":[]}'
         |read INPUT_LINE
         |echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Hi there!"}]}}'
         |echo '{"type":"result","subtype":"success","duration_ms":200,"duration_api_ms":150,"is_error":false,"num_turns":1,"session_id":"real-session-42"}'
         |""".stripMargin

    val scriptFile =
      java.nio.file.Files.createTempFile("mock-session-", ".sh")
    java.nio.file.Files.write(scriptFile, script.getBytes)
    java.nio.file.Files.setPosixFilePermissions(
      scriptFile,
      java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
    )

    try
      val process = new ProcessBuilder(scriptFile.toString)
        .redirectErrorStream(false)
        .start()

      // Read the init message first (it's written before reading stdin)
      val inputStream = process.getInputStream
      val bufferedReader =
        new java.io.BufferedReader(new java.io.InputStreamReader(inputStream))

      val initLine = bufferedReader.readLine()
      assertNotEquals(initLine, null, "Expected init message")
      val initMsg = JsonParser.parseJsonLine(initLine)
      initMsg match
        case Some(SystemMessage(subtype, data)) =>
          assertEquals(subtype, "init")
          assertEquals(data("session_id"), "real-session-42")
        case other => fail(s"Expected SystemMessage, got $other")

      // Write our SDKUserMessage
      val writer = process.getOutputStream
      writer.write((jsonLine + "\n").getBytes)
      writer.flush()
      writer.close()

      // Read the response
      val assistantLine = bufferedReader.readLine()
      val resultLine = bufferedReader.readLine()
      bufferedReader.close()

      process.waitFor()

      val assistantMsg = JsonParser.parseJsonLine(assistantLine)
      assistantMsg match
        case Some(AssistantMessage(content)) =>
          content.head match
            case TextBlock(text) => assertEquals(text, "Hi there!")
            case other           => fail(s"Expected TextBlock, got $other")
        case other => fail(s"Expected AssistantMessage, got $other")

      val resultMsg = JsonParser.parseJsonLine(resultLine)
      resultMsg match
        case Some(rm: ResultMessage) =>
          assertEquals(rm.sessionId, "real-session-42")
        case other => fail(s"Expected ResultMessage, got $other")
    finally java.nio.file.Files.deleteIfExists(scriptFile): Unit
