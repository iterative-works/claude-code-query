// PURPOSE: End-to-end test validating SDKUserMessage format against the real Claude Code CLI
// PURPOSE: Gated on CLI availability to skip gracefully in environments without the CLI

package works.iterative.claude.core.model

import munit.FunSuite
import io.circe.syntax.*
import works.iterative.claude.direct.internal.testing.TestAssumptions

class SDKUserMessageE2ETest extends FunSuite:

  test(
    "Real CLI accepts SDKUserMessage JSON format via stream-json stdin".ignore
  ):
    // This test is ignored by default because it requires the real Claude CLI
    // and API credentials. To run it manually, remove .ignore and ensure
    // `claude` is in PATH with valid credentials.
    TestAssumptions.assumeCommand("claude")

    val msg = SDKUserMessage("What is 1+1?", "pending", None)
    val jsonLine = msg.asJson.noSpaces

    val process = new ProcessBuilder(
      "claude",
      "--print",
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
      exitCode == 0 || stdout.nonEmpty,
      s"CLI should accept the message format. Exit: $exitCode, stderr: $stderr"
    )
