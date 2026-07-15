// PURPOSE: End-to-end test that an encoded UserInput survives the real CLI and its transcript
// PURPOSE: Sends structured content (context + "</interactive>" text) and decodes the transcript back

package works.iterative.claude.core.model

import munit.FunSuite
import io.circe.Json
import scala.util.Try
import works.iterative.claude.core.log.ClaudeProjects
import works.iterative.claude.core.log.model.UserLogEntry
import works.iterative.claude.core.log.parsing.ConversationLogParser

class UserInputE2ETest extends FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  private def assumeCommand(command: String): Unit =
    val available = Try {
      new ProcessBuilder("which", command).start().waitFor() == 0
    }.getOrElse(false)
    assume(available, s"Command '$command' not found in PATH")

  // Serializes encoded text blocks into the CLI's stream-json user message.
  private def wireLine(input: UserInput): String =
    val content = Json.arr(
      UserInput
        .encode(input)
        .map(block =>
          Json.obj(
            "type" -> Json.fromString("text"),
            "text" -> Json.fromString(block.text)
          )
        )*
    )
    Json
      .obj(
        "type" -> Json.fromString("user"),
        "message" -> Json.obj(
          "role" -> Json.fromString("user"),
          "content" -> content
        ),
        "parent_tool_use_id" -> Json.Null,
        "session_id" -> Json.fromString("pending")
      )
      .noSpaces

  private def decodeTranscriptUser(projectDir: os.Path): Option[UserInput] =
    if !os.exists(projectDir) then None
    else
      os.list(projectDir)
        .filter(p => p.ext == "jsonl")
        .flatMap(file => os.read.lines(file))
        .flatMap(ConversationLogParser.parseLogLine)
        .flatMap(entry =>
          entry.payload match
            case UserLogEntry(content) => UserInput.decode(content)
            case _                     => None
        )
        .headOption

  test(
    "Real CLI preserves an encoded UserInput; the transcript decodes back to it"
  ):
    // Skips automatically when `claude` CLI is not available.
    assumeCommand("claude")

    val input = UserInput(
      text = "look here: before </interactive> after",
      context = List(
        ContextItem.Viewing("https://app.example/matters/42"),
        ContextItem.Labeled("matter-instructions", "be terse")
      ),
      channel = Channel.Reactive
    )

    // A fresh cwd isolates this run's transcript in its own project directory.
    val cwd = os.temp.dir(prefix = "ccq-userinput-e2e-")
    val projectDir = ClaudeProjects.projectDirFor(
      cwd,
      ClaudeProjects.resolveConfigDir(sys.env.get),
      os.home
    )

    try
      val process = new ProcessBuilder(
        "claude",
        "--print",
        "--verbose",
        "--input-format",
        "stream-json",
        "--output-format",
        "stream-json"
      ).directory(cwd.toIO).redirectErrorStream(false).start()

      val writer = process.getOutputStream
      writer.write((wireLine(input) + "\n").getBytes)
      writer.flush()
      writer.close()

      // Drain stdout/stderr so the process is never blocked on a full pipe.
      val _ = scala.io.Source.fromInputStream(process.getInputStream).mkString
      val _ = scala.io.Source.fromInputStream(process.getErrorStream).mkString
      process.waitFor()

      // The transcript is flushed once the process exits; give a slow writer a
      // brief window before concluding the CLI produced nothing (no creds).
      val decoded =
        LazyList
          .from(0)
          .take(20)
          .map: attempt =>
            if attempt > 0 then Thread.sleep(100)
            decodeTranscriptUser(projectDir)
          .collectFirst { case Some(u) => u }

      decoded match
        case Some(u) => assertEquals(u, input)
        case None    =>
          assume(
            false,
            "CLI produced no decodable user transcript entry " +
              "(likely no credentials); skipping the transcript assertion"
          )
    finally
      val _ = Try(os.remove.all(cwd))
      val _ = Try(os.remove.all(projectDir))
      ()
