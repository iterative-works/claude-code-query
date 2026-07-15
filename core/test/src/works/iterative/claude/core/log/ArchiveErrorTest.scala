// PURPOSE: Tests the human-readable message for every ArchiveError case, no IO or effects involved
// PURPOSE: Regression-guards InvalidSessionId sanitization (control-char stripping, 40-char bound)

package works.iterative.claude.core.log

import munit.FunSuite

class ArchiveErrorTest extends FunSuite:
  import ArchiveError.*

  // Control bytes are built from their codepoints so no literal appears in the
  // source: NUL and backspace (C0), DEL, and CSI (C1).
  private val nul = 0.toChar
  private val backspace = 8.toChar
  private val del = 127.toChar
  private val csi = 0x9b.toChar

  test("each case renders a descriptive message"):
    assert(SessionNotFound("sess-1").message.contains("sess-1"))
    val sub = SubAgentNotFound("sess-1", "toolu_9").message
    assert(sub.contains("sess-1") && sub.contains("toolu_9"))
    assertEquals(
      ArchiveIOError("disk full", new RuntimeException("x")).message,
      "disk full"
    )
    assert(PageSourceMoved("sess-1").message.contains("sess-1"))
    assert(CorruptTranscript("sess-1").message.contains("sess-1"))

  test("getMessage returns the same text as message"):
    val err = SessionNotFound("sess-1")
    assertEquals(err.getMessage, err.message)

  test("InvalidPageSize names the rejected size and the accepted maximum"):
    val msg = InvalidPageSize(-5).message
    assert(msg.contains("-5"), msg)
    assert(msg.contains(PageSize.Max.toString), msg)

  test("PageSourceMoved tells the caller to restart from the latest page"):
    assert(PageSourceMoved("sess-1").message.toLowerCase.contains("restart"))

  test("InvalidSessionId strips control characters (C0, DEL, and C1) from the echo"):
    val raw = s"a${nul}b${backspace}c${del}d${csi}e"
    val msg = InvalidSessionId(raw).message
    // No control byte from the attacker-shaped value leaks into the message.
    assert(!msg.exists(_.isControl), msg)
    // The printable characters survive in order.
    assert(msg.contains("abcde"), msg)

  test("InvalidSessionId truncates the echoed value to 40 characters"):
    val raw = "x" * 100
    val msg = InvalidSessionId(raw).message
    assert(msg.contains("x" * 40), msg)
    assert(!msg.contains("x" * 41), msg)

  test("InvalidSessionId strips control characters before it counts to 40"):
    // 50 control bytes then 5 printable: stripping happens first, so all 5
    // printable characters survive the 40-char bound.
    val raw = (backspace.toString * 50) + "abcde"
    val msg = InvalidSessionId(raw).message
    assert(msg.contains("abcde"), msg)
