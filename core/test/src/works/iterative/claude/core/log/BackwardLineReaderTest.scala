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

  // A budget far larger than any fixture here, so the scan never gives up.
  private val ampleBudget = 1L << 20

  private def tail(img: Image, limit: Int, blockSize: Int): BackwardLineReader.Tail =
    BackwardLineReader.lastLines(img.size, limit, blockSize, ampleBudget, img.read) match
      case Right(t) => t
      case Left(e)  => fail(s"unexpected scan-budget overflow at ${e.scannedBytes}")

  private def page(
      regionEnd: Long,
      limit: Int,
      blockSize: Int,
      img: Image
  ): BackwardLineReader.Tail =
    BackwardLineReader.lastLines(regionEnd, limit, blockSize, ampleBudget, img.read) match
      case Right(t) => t
      case Left(e)  => fail(s"unexpected scan-budget overflow at ${e.scannedBytes}")

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
    val page1 = page(img.size, 2, 3, img)
    assertEquals(page1.lines, Vector("d", "e"))
    val page2 = page(page1.older.get, 2, 3, img)
    assertEquals(page2.lines, Vector("b", "c"))
    val page3 = page(page2.older.get, 2, 3, img)
    assertEquals(page3.lines, Vector("a"))
    assertEquals(page3.older, None)

  test("a non-positive limit yields an empty tail"):
    val img = image("a\nb\n")
    assertEquals(tail(img, limit = 0, blockSize = 4).lines, Vector.empty)

  test("a torn trailing segment is excluded, then older pages walk without dup"):
    // A live append in progress ("half-writ") plus multi-page backward walking:
    // the torn tail is never a page line, and no earlier line is skipped or
    // duplicated across the seams.
    val img   = image("a\nb\nc\nhalf-writ")
    val page1 = page(img.size, 1, 3, img)
    assertEquals(page1.lines, Vector("c"))
    val page2 = page(page1.older.get, 1, 3, img)
    assertEquals(page2.lines, Vector("b"))
    val page3 = page(page2.older.get, 1, 3, img)
    assertEquals(page3.lines, Vector("a"))
    assertEquals(page3.older, None)

  test("an empty line between two newlines round-trips as an empty string"):
    val img = image("a\n\nb\n")
    val t   = tail(img, limit = 3, blockSize = 2)
    assertEquals(t.lines, Vector("a", "", "b"))

  test("a line that is only a carriage return round-trips as an empty string"):
    // The lone "\r" is a line terminated by "\n"; the trailing CR is stripped,
    // leaving an empty line rather than a one-character one.
    val img = image("a\n\r\nb\n")
    val t   = tail(img, limit = 3, blockSize = 2)
    assertEquals(t.lines, Vector("a", "", "b"))

  test("a page whose lines exceed the scan budget fails rather than buffering all"):
    // No newline within the budget from the region end: the region is corrupt
    // or carries an oversized line, so the scan gives up instead of reading on.
    val img = image("x" * 200 + "\n")
    val result =
      BackwardLineReader.lastLines(img.size, 1, 8, maxScanBytes = 16, img.read)
    assert(result.isLeft, s"expected a budget overflow, got $result")
    result.left.foreach(e => assert(e.scannedBytes >= 16))
    // It stopped early: it never scanned back to byte 0.
    assert(img.reads.map(_._1).forall(_ > 0))

  test("a page within the scan budget still succeeds"):
    val img = image("a\nb\nc\n")
    val result =
      BackwardLineReader.lastLines(img.size, 2, 2, maxScanBytes = 1024, img.read)
    assertEquals(result.map(_.lines), Right(Vector("b", "c")))

  test("tailing a huge file reads only near the end, never byte 0"):
    val body = (1 to 10000).map(i => s"line-$i").mkString("", "\n", "\n")
    val img  = image(body)
    val t    = tail(img, limit = 3, blockSize = 16)
    assertEquals(t.lines, Vector("line-9998", "line-9999", "line-10000"))
    assert(t.older.exists(_ > 0))
    // O(page): the lowest offset it ever read stays far from the file start.
    val lowestRead = img.reads.map(_._1).min
    assert(lowestRead > 0, s"expected to avoid byte 0, read down to $lowestRead")
