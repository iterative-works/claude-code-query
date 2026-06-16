// PURPOSE: Tests for the ZIO conversation log reader over temporary .jsonl files
// PURPOSE: Verifies parsing of all lines and skipping of blank/unparseable lines

package works.iterative.claude.zio.log

import zio.*
import zio.test.*
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object ZioConversationLogReaderTest extends ClaudeZioSpec:
  private val reader = ZioConversationLogReader()

  def spec = suite("ZioConversationLogReader")(
    test("readAll parses every JSONL line into an entry"):
      val lines = List(
        """{"type":"user","uuid":"u1","sessionId":"s1","message":{"content":"hello"}}""",
        """{"type":"user","uuid":"u2","sessionId":"s1","message":{"content":"world"}}"""
      )
      for
        dir     <- ZIO.attempt(os.temp.dir())
        file     = dir / "s1.jsonl"
        _       <- ZIO.attempt(os.write(file, lines.mkString("\n")))
        entries <- reader.readAll(file)
      yield assertTrue(entries.flatMap(_.uuid) == List("u1", "u2")),
    test("readAll skips blank and unparseable lines"):
      val content = List(
        """{"type":"user","uuid":"u1","sessionId":"s1","message":{"content":"hi"}}""",
        "",
        "{ not json }",
        """{"type":"user","uuid":"u2","sessionId":"s1","message":{"content":"yo"}}"""
      ).mkString("\n")
      for
        dir     <- ZIO.attempt(os.temp.dir())
        file     = dir / "s.jsonl"
        _       <- ZIO.attempt(os.write(file, content))
        entries <- reader.readAll(file)
      yield assertTrue(entries.flatMap(_.uuid) == List("u1", "u2")),
    test("stream yields entries lazily"):
      val line =
        """{"type":"user","uuid":"u1","sessionId":"s1","message":{"content":"hi"}}"""
      for
        dir     <- ZIO.attempt(os.temp.dir())
        file     = dir / "s.jsonl"
        _       <- ZIO.attempt(os.write(file, line))
        entries <- reader.stream(file).runCollect
      yield assertTrue(entries.size == 1)
  )
