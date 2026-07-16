// PURPOSE: Tests that a backward-read tail is assembled into a parsed page with a pinned older cursor
// PURPOSE: Pins parse tolerance, cursor minting from the tail offset, and absent-cursor at file start

package works.iterative.claude.core.log

import munit.FunSuite
import works.iterative.claude.core.model.SessionId

class TranscriptPageTest extends FunSuite:

  private val sessionId = SessionId("0d43043b-1111-2222-3333-444455556666")
  private val source    = os.Path("/vendor/projects/-p/s.jsonl")

  private def line(uuid: String): String =
    s"""{"type":"user","sessionId":"${sessionId.value}","uuid":"$uuid","message":{"content":"x"}}"""

  test("parses the tail lines into entries in order"):
    val tail = BackwardLineReader.Tail(Vector(line("u1"), line("u2")), None)
    val page = TranscriptPage.fromTail(sessionId, source, tail)
    assertEquals(page.entries.map(_.uuid.getOrElse("")).toList, List("u1", "u2"))

  test("an older offset mints a cursor pinned to the source"):
    val tail = BackwardLineReader.Tail(Vector(line("u2")), Some(42L))
    val page = TranscriptPage.fromTail(sessionId, source, tail)
    assert(page.older.isDefined)
    val token = page.older.get
    assertEquals(token.sessionId, sessionId)
    assertEquals(token.source, source)
    assertEquals(token.offset, 42L)

  test("no older offset means the file start was reached, so no cursor"):
    val tail = BackwardLineReader.Tail(Vector(line("u1")), None)
    val page = TranscriptPage.fromTail(sessionId, source, tail)
    assertEquals(page.older, None)

  test("blank or unparseable tail lines are dropped, unknown types survive raw"):
    val unknown =
      s"""{"type":"vendor-future","sessionId":"${sessionId.value}","uuid":"u3","x":1}"""
    val tail = BackwardLineReader.Tail(Vector("", line("u1"), unknown), None)
    val page = TranscriptPage.fromTail(sessionId, source, tail)
    assertEquals(page.entries.map(_.uuid.getOrElse("")).toList, List("u1", "u3"))
