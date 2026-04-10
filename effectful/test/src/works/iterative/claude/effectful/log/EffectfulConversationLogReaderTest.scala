// PURPOSE: Integration tests for EffectfulConversationLogReader using real temp .jsonl files
// PURPOSE: Verifies readAll and stream parse log entries correctly via cats-effect IO

package works.iterative.claude.effectful.log

import munit.CatsEffectSuite
import cats.effect.IO
import works.iterative.claude.core.log.model.*

class EffectfulConversationLogReaderTest extends CatsEffectSuite:

  private val reader = EffectfulConversationLogReader()

  private val userLine =
    """{"type":"user","uuid":"u1","sessionId":"s1","message":{"content":"hello"}}"""
  private val assistantLine =
    """{"type":"assistant","uuid":"u2","sessionId":"s1","message":{"content":[{"type":"text","text":"hi there"}]}}"""
  private val systemLine =
    """{"type":"system","uuid":"u3","sessionId":"s1","subtype":"init","apiKeySource":"env"}"""

  private def withLogFile(lines: List[String])(
      body: os.Path => IO[Unit]
  ): IO[Unit] =
    IO(os.temp.dir()).flatMap: tmpDir =>
      val logFile = tmpDir / "test-session.jsonl"
      IO(os.write(logFile, lines.mkString("\n"))) >>
        body(logFile).guarantee(IO(os.remove.all(tmpDir)))

  test("readAll returns empty list for empty file"):
    withLogFile(List.empty): path =>
      reader
        .readAll(path)
        .map: result =>
          assertEquals(result, List.empty[ConversationLogEntry])

  test("readAll skips blank lines"):
    withLogFile(List("", "   ")): path =>
      reader
        .readAll(path)
        .map: result =>
          assertEquals(result, List.empty[ConversationLogEntry])

  test("readAll parses a single user entry"):
    withLogFile(List(userLine)): path =>
      reader
        .readAll(path)
        .map: result =>
          assertEquals(result.length, 1)
          assertEquals(result.head.sessionId, "s1")
          assert(
            result.head.payload.isInstanceOf[UserLogEntry],
            s"Expected UserLogEntry but got ${result.head.payload}"
          )

  test("readAll parses multiple entries of different types"):
    withLogFile(List(userLine, assistantLine, systemLine)): path =>
      reader
        .readAll(path)
        .map: result =>
          assertEquals(result.length, 3)

  test("readAll preserves order of entries"):
    withLogFile(List(userLine, assistantLine)): path =>
      reader
        .readAll(path)
        .map: result =>
          assertEquals(result.length, 2)
          assert(result(0).payload.isInstanceOf[UserLogEntry])
          assert(result(1).payload.isInstanceOf[AssistantLogEntry])

  test("readAll skips malformed JSON lines silently"):
    withLogFile(List(userLine, "{bad json}", assistantLine)): path =>
      reader
        .readAll(path)
        .map: result =>
          assertEquals(result.length, 2)

  test("stream produces same entries as readAll"):
    withLogFile(List(userLine, assistantLine, systemLine)): path =>
      for
        fromReadAll <- reader.readAll(path)
        fromStream <- reader.stream(path).compile.toList
      yield assertEquals(fromStream, fromReadAll)

  test("stream returns empty for empty file"):
    withLogFile(List.empty): path =>
      reader
        .stream(path)
        .compile
        .toList
        .map: result =>
          assertEquals(result, List.empty[ConversationLogEntry])

  test("stream skips blank and malformed lines"):
    withLogFile(List("", userLine, "{not json}", "   ", assistantLine)):
      path =>
        reader
          .stream(path)
          .compile
          .toList
          .map: result =>
            assertEquals(result.length, 2)
