// PURPOSE: End-to-end test validating SDKUserMessage format against the real Claude Code CLI
// PURPOSE: Gated on CLI availability to skip gracefully in environments without the CLI

package works.iterative.claude.core.model

import munit.FunSuite
import io.circe.syntax.*
import scala.util.Try

class SDKUserMessageE2ETest extends FunSuite:

  private def assumeCommand(command: String): Unit =
    val available = Try {
      new ProcessBuilder("which", command).start().waitFor() == 0
    }.getOrElse(false)
    assume(available, s"Command '$command' not found in PATH")

  test(
    "Real CLI accepts SDKUserMessage JSON format via stream-json stdin"
  ):
    // Skips automatically when `claude` CLI is not available
    assumeCommand("claude")

    val msg = SDKUserMessage("What is 1+1?", "pending", None)
    val jsonLine = msg.asJson.noSpaces

    val process = new ProcessBuilder(
      "claude",
      "--print",
      "--verbose",
      "--input-format",
      "stream-json",
      "--output-format",
      "stream-json"
    ).redirectErrorStream(false).start()

    val writer = process.getOutputStream
    writer.write((jsonLine + "\n").getBytes)
    writer.flush()
    writer.close()

    val exitCode = process.waitFor()
    val stderr =
      scala.io.Source.fromInputStream(process.getErrorStream).mkString
    val stdout =
      scala.io.Source.fromInputStream(process.getInputStream).mkString

    // The CLI should not crash with a format error
    assert(
      exitCode == 0,
      s"CLI should accept the message format. Exit: $exitCode, stderr: $stderr"
    )
