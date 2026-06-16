// PURPOSE: Unit tests for the ZIO ClaudeCode API that do not spawn a process
// PURPOSE: Covers configuration validation, CLI argument assembly, and text extraction

package works.iterative.claude.zio

import zio.*
import zio.test.*
import works.iterative.claude.core.ConfigurationError
import works.iterative.claude.core.model.*
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object ClaudeCodeTest extends ClaudeZioSpec:
  def spec = suite("ClaudeCode")(
    test("query fails with ConfigurationError when cwd does not exist"):
      val options =
        QueryOptions.simple("hi").withCwd("/nonexistent/path/zzz-claude-sdk")
      for error <- ClaudeCode.query(options).runCollect.flip
      yield assertTrue(error.isInstanceOf[ConfigurationError]),
    test("query fails with ConfigurationError when cwd is a file, not a directory"):
      for
        file   <- ZIO.attempt(java.io.File.createTempFile("claude", ".txt"))
        options = QueryOptions.simple("hi").withCwd(file.getAbsolutePath)
        error  <- ClaudeCode.query(options).runCollect.flip
        _      <- ZIO.attempt(file.delete()).ignore
      yield assertTrue(error.isInstanceOf[ConfigurationError]),
    test("buildCLIArguments wraps args with streaming flags and a trailing prompt"):
      val args = ClaudeCode.buildCLIArguments(QueryOptions.simple("hello"))
      assertTrue(
        args.startsWith(
          List("--print", "--verbose", "--output-format", "stream-json")
        ),
        args.endsWith(List("--", "hello"))
      ),
    test("extractTextFromMessages returns the first assistant text block"):
      val messages = List(
        SystemMessage("init", Map.empty),
        AssistantMessage(List(TextBlock("answer")))
      )
      assertTrue(ClaudeCode.extractTextFromMessages(messages) == "answer"),
    test("extractTextFromMessages returns empty string when no assistant text"):
      assertTrue(
        ClaudeCode.extractTextFromMessages(
          List(SystemMessage("init", Map.empty))
        ) == ""
      )
  )
