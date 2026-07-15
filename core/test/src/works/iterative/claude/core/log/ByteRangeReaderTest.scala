// PURPOSE: Tests that a possibly-short read primitive is looped into a complete byte range
// PURPOSE: Pins full-fill, short-read reassembly, and end-of-file truncation behaviour

package works.iterative.claude.core.log

import munit.FunSuite

class ByteRangeReaderTest extends FunSuite:

  /** An in-memory file image whose single-read primitive returns at most
    * `maxChunk` bytes per call, modelling a channel that may short-read.
    */
  private class ShortReader(bytes: Array[Byte], maxChunk: Int):
    var calls: List[(Long, Int)] = Nil
    def readUpTo(offset: Long, length: Int): Array[Byte] =
      calls = (offset, length) :: calls
      val start = offset.toInt
      val count = math.min(math.min(length, maxChunk), bytes.length - start)
      bytes.slice(start, start + math.max(count, 0))

  test("a primitive that fills the whole range in one call returns it intact"):
    val bytes  = "0123456789".getBytes
    val reader = new ShortReader(bytes, maxChunk = 100)
    val out    = ByteRangeReader.readFully(2L, 5, reader.readUpTo)
    assertEquals(out.toSeq, bytes.slice(2, 7).toSeq)

  test("short reads are looped until the requested range is filled"):
    val bytes  = (0 until 50).map(_.toByte).toArray
    // Each call returns at most 7 bytes, so a 20-byte range needs several calls.
    val reader = new ShortReader(bytes, maxChunk = 7)
    val out    = ByteRangeReader.readFully(10L, 20, reader.readUpTo)
    assertEquals(out.toSeq, bytes.slice(10, 30).toSeq)
    assert(reader.calls.size > 1, "expected multiple reads to fill the range")
    // The loop advances the offset by the bytes already read, never re-reading.
    val offsets = reader.calls.reverse.map(_._1)
    assertEquals(offsets.head, 10L)
    assert(
      offsets.zip(offsets.tail).forall((a, b) => b > a),
      s"offsets should strictly advance, got $offsets"
    )

  test("reading stops when the primitive signals end of file"):
    val bytes  = "abc".getBytes
    val reader = new ShortReader(bytes, maxChunk = 100)
    // Ask for more than exists starting at offset 1: only "bc" remains.
    val out    = ByteRangeReader.readFully(1L, 10, reader.readUpTo)
    assertEquals(out.toSeq, "bc".getBytes.toSeq)
