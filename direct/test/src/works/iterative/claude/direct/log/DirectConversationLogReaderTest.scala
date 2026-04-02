// PURPOSE: Integration tests for DirectConversationLogReader using real temp .jsonl files
// PURPOSE: Verifies readAll and stream parse log entries correctly with actual file I/O

package works.iterative.claude.direct.log

import munit.FunSuite
import ox.*
import works.iterative.claude.core.log.model.*

class DirectConversationLogReaderTest extends FunSuite:

  private val reader = DirectConversationLogReader()

  private val humanLine =
    """{"type":"human","uuid":"u1","sessionId":"s1","message":{"content":"hello"}}"""
  private val assistantLine =
    """{"type":"assistant","uuid":"u2","sessionId":"s1","message":{"content":[{"type":"text","text":"hi there"}]}}"""
  private val systemLine =
    """{"type":"system","uuid":"u3","sessionId":"s1","subtype":"init","apiKeySource":"env"}"""
  private val emptyLine = ""
  private val blankLine = "   "

  private def withLogFile(lines: List[String])(body: os.Path => Unit): Unit =
    val tmpDir = os.temp.dir()
    try
      val logFile = tmpDir / "test-session.jsonl"
      os.write(logFile, lines.mkString("\n"))
      body(logFile)
    finally os.remove.all(tmpDir)

  test("readAll returns empty list for empty file"):
    withLogFile(List.empty): path =>
      val result = reader.readAll(path)
      assertEquals(result, List.empty[ConversationLogEntry])

  test("readAll skips blank lines"):
    withLogFile(List(emptyLine, blankLine)): path =>
      val result = reader.readAll(path)
      assertEquals(result, List.empty[ConversationLogEntry])

  test("readAll parses a single human entry"):
    withLogFile(List(humanLine)): path =>
      val result = reader.readAll(path)
      assertEquals(result.length, 1)
      assertEquals(result.head.sessionId, "s1")
      assert(
        result.head.payload.isInstanceOf[UserLogEntry],
        s"Expected UserLogEntry but got ${result.head.payload}"
      )

  test("readAll parses multiple entries of different types"):
    withLogFile(List(humanLine, assistantLine, systemLine)): path =>
      val result = reader.readAll(path)
      assertEquals(result.length, 3)

  test("readAll preserves order of entries"):
    withLogFile(List(humanLine, assistantLine)): path =>
      val result = reader.readAll(path)
      assertEquals(result.length, 2)
      assert(result(0).payload.isInstanceOf[UserLogEntry])
      assert(result(1).payload.isInstanceOf[AssistantLogEntry])

  test("readAll skips malformed JSON lines silently"):
    withLogFile(List(humanLine, "{bad json}", assistantLine)): path =>
      val result = reader.readAll(path)
      assertEquals(result.length, 2)

  test("readAll handles mixed blank and valid lines"):
    withLogFile(List(emptyLine, humanLine, blankLine, assistantLine)): path =>
      val result = reader.readAll(path)
      assertEquals(result.length, 2)

  test("stream produces same entries as readAll"):
    withLogFile(List(humanLine, assistantLine, systemLine)): path =>
      val fromReadAll = reader.readAll(path)
      val fromStream = supervised:
        reader.stream(path).runToList()
      assertEquals(fromStream, fromReadAll)

  test("stream returns empty flow for empty file"):
    withLogFile(List.empty): path =>
      val result = supervised:
        reader.stream(path).runToList()
      assertEquals(result, List.empty[ConversationLogEntry])

  test("stream skips blank and malformed lines"):
    withLogFile(
      List(emptyLine, humanLine, "{not json}", blankLine, assistantLine)
    ): path =>
      val result = supervised:
        reader.stream(path).runToList()
      assertEquals(result.length, 2)
