// PURPOSE: Exhaustive IO-free tests for backward tail-line extraction over block reads from EOF
// PURPOSE: Pins boundary, oversized-line, UTF-8-across-block, CRLF, and torn-final-line behaviour

package works.iterative.claude.core.log

import munit.FunSuite
import java.nio.charset.StandardCharsets.UTF_8

class BackwardLineReaderTest extends FunSuite:

  /** A pure block accessor over an in-memory file image; records the ranges it
    * was asked for so a test can assert the reader never touches byte 0 when it
    * did not need to.
    */
  private class Image(bytes: Array[Byte]):
    var reads: List[(Long, Int)] = Nil
    def read(offset: Long, length: Int): Array[Byte] =
      reads = (offset, length) :: reads
      bytes.slice(offset.toInt, offset.toInt + length)
    def size: Long = bytes.length.toLong

  private def image(text: String): Image =
    new Image(text.getBytes(UTF_8))

  private def tail(img: Image, limit: Int, blockSize: Int): BackwardLineReader.Tail =
    BackwardLineReader.lastLines(img.size, limit, blockSize, img.read)

  test("empty file yields no lines and no older cursor"):
    val img = image("")
    val t   = tail(img, limit = 5, blockSize = 4)
    assertEquals(t.lines, Vector.empty)
    assertEquals(t.older, None)

  test("a single newline-terminated line is the whole tail"):
    val img = image("only\n")
    val t   = tail(img, limit = 5, blockSize = 4)
    assertEquals(t.lines, Vector("only"))
    assertEquals(t.older, None)

  test("a torn final line without a terminating newline is excluded"):
    // A trailing segment with no '\n' is a live CLI append in progress.
    val img = image("done\nhalf-writ")
    val t   = tail(img, limit = 5, blockSize = 4)
    assertEquals(t.lines, Vector("done"))
    assertEquals(t.older, None)

  test("a single line with no newline at all yields nothing (all torn)"):
    val img = image("no-newline-ever")
    val t   = tail(img, limit = 5, blockSize = 4)
    assertEquals(t.lines, Vector.empty)
    assertEquals(t.older, None)

  test("last N lines are returned oldest-first with an older cursor"):
    val img = image("a\nb\nc\nd\ne\n")
    val t   = tail(img, limit = 2, blockSize = 3)
    assertEquals(t.lines, Vector("d", "e"))
    // "d" starts at byte 6 (a\nb\nc\n = 6 bytes); older bytes remain.
    assertEquals(t.older, Some(6L))

  test("limit larger than the line count returns every line, no older cursor"):
    val img = image("a\nb\nc\n")
    val t   = tail(img, limit = 10, blockSize = 2)
    assertEquals(t.lines, Vector("a", "b", "c"))
    assertEquals(t.older, None)

  test("a line exactly filling a block is reassembled intact"):
    // Block size 4; "abcd\n" makes the newline land right on a block edge.
    val img = image("abcd\nefgh\n")
    val t   = tail(img, limit = 2, blockSize = 4)
    assertEquals(t.lines, Vector("abcd", "efgh"))
    assertEquals(t.older, None)

  test("a line longer than a block is reassembled across several blocks"):
    val long = "x" * 20
    val img  = image(s"$long\nshort\n")
    val t    = tail(img, limit = 2, blockSize = 4)
    assertEquals(t.lines, Vector(long, "short"))
    assertEquals(t.older, None)

  test("multi-byte UTF-8 split across a block boundary decodes correctly"):
    // 'é' is 2 bytes, '🚀' is 4 bytes; a small block splits them mid-character.
    val img = image("héllo\n🚀 world\n")
    val t   = tail(img, limit = 2, blockSize = 3)
    assertEquals(t.lines, Vector("héllo", "🚀 world"))

  test("splitting on 0x0A never severs a multi-byte character"):
    // No Unicode code point above U+007F contains a 0x0A byte, so a raw
    // newline split is UTF-8-safe. A line whose bytes include 0x0A only as the
    // terminator round-trips byte-for-byte.
    val line = " €—🚀 mixed"
    val img  = image(s"$line\n")
    val t    = tail(img, limit = 1, blockSize = 2)
    assertEquals(t.lines, Vector(line))

  test("a trailing carriage return is stripped for CRLF tolerance"):
    val img = image("first\r\nsecond\r\n")
    val t   = tail(img, limit = 2, blockSize = 4)
    assertEquals(t.lines, Vector("first", "second"))

  test("paging backward with the older cursor walks the whole file"):
    val img = image("a\nb\nc\nd\ne\n")
    val page1 = BackwardLineReader.lastLines(img.size, 2, 3, img.read)
    assertEquals(page1.lines, Vector("d", "e"))
    val page2 =
      BackwardLineReader.lastLines(page1.older.get, 2, 3, img.read)
    assertEquals(page2.lines, Vector("b", "c"))
    val page3 =
      BackwardLineReader.lastLines(page2.older.get, 2, 3, img.read)
    assertEquals(page3.lines, Vector("a"))
    assertEquals(page3.older, None)

  test("a non-positive limit yields an empty tail"):
    val img = image("a\nb\n")
    assertEquals(tail(img, limit = 0, blockSize = 4).lines, Vector.empty)

  test("tailing a huge file reads only near the end, never byte 0"):
    val body = (1 to 10000).map(i => s"line-$i").mkString("", "\n", "\n")
    val img  = image(body)
    val t    = tail(img, limit = 3, blockSize = 16)
    assertEquals(t.lines, Vector("line-9998", "line-9999", "line-10000"))
    assert(t.older.exists(_ > 0))
    // O(page): the lowest offset it ever read stays far from the file start.
    val lowestRead = img.reads.map(_._1).min
    assert(lowestRead > 0, s"expected to avoid byte 0, read down to $lowestRead")
