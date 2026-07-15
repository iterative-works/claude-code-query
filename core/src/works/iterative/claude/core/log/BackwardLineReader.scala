// PURPOSE: Pure backward extraction of a file's trailing complete lines from block reads at EOF
// PURPOSE: Splits on 0x0A (UTF-8-safe), drops a torn final line, and yields an older-page cursor

package works.iterative.claude.core.log

import java.nio.charset.StandardCharsets.UTF_8

/** Reads the last complete lines of a byte region without scanning it from the
  * start.
  *
  * The region is a file being appended to by the CLI. Lines are delimited by
  * `0x0A`; because no byte of a multi-byte UTF-8 sequence is ever `0x0A`,
  * splitting on the raw byte never severs a character, so each line's byte
  * slice decodes as UTF-8 independently. A single trailing `\r` is stripped so
  * a CRLF-terminated line reads the same as an LF-terminated one.
  *
  * Torn-final-line rule: bytes after the last `0x0A` in the region are a line
  * whose terminating newline has not been written yet — a live append in
  * progress — and are excluded from the tail. A region that ends exactly on a
  * newline (or on a previous page's cursor, which is always a line start) has
  * no such remainder.
  *
  * Blocks are read backward from the region end, only far enough to expose the
  * requested number of complete lines, so cost is proportional to the page, not
  * the region.
  */
object BackwardLineReader:

  /** A page of trailing lines.
    *
    * @param lines
    *   the complete lines nearest the region end, in file order (oldest first),
    *   each without its terminating newline or a trailing `\r`
    * @param older
    *   the byte offset at which the oldest returned line starts, when older
    *   lines remain before it; `None` when the oldest line starts at byte 0 or
    *   no line was returned. Feed it back as the next call's `regionEnd`.
    */
  final case class Tail(lines: Vector[String], older: Option[Long])

  /** Extracts up to `limit` complete lines ending at `regionEnd`.
    *
    * @param regionEnd
    *   exclusive upper byte bound of the region to read (the file size for the
    *   newest page, or a previous [[Tail.older]] cursor for an older page)
    * @param limit
    *   maximum number of lines to return; a non-positive value yields an empty
    *   tail
    * @param blockSize
    *   how many bytes to pull per backward read; must be positive
    * @param readBlock
    *   a pure view of the underlying append-only file returning the bytes in
    *   `[offset, offset + length)`; the reader only ever asks for ranges within
    *   `[0, regionEnd)`
    */
  def lastLines(
      regionEnd: Long,
      limit: Int,
      blockSize: Int,
      readBlock: (Long, Int) => Array[Byte]
  ): Tail =
    if limit <= 0 || regionEnd <= 0 then Tail(Vector.empty, None)
    else
      val newline = '\n'.toByte
      // Grow the buffer backward until it holds one more newline than requested
      // (so the oldest line's start is known) or the region start is reached.
      val (curStart, buffer) =
        readBackTo(regionEnd, Array.emptyByteArray, limit, blockSize, readBlock)

      // Absolute file offsets of every newline now in the buffer, ascending.
      val newlineOffsets =
        buffer.indices.iterator
          .filter(i => buffer(i) == newline)
          .map(i => curStart + i)
          .toVector

      val chosen = newlineOffsets.takeRight(limit)
      val beforeOldest = newlineOffsets.size - chosen.size - 1
      val startOfOldest =
        if beforeOldest >= 0 then newlineOffsets(beforeOldest) + 1 else 0L

      // Each chosen newline ends a line; that line starts just after the
      // previous chosen newline, or at the oldest line's start for the first.
      val starts = startOfOldest +: chosen.dropRight(1).map(_ + 1)
      val lines = starts
        .zip(chosen)
        .map: (start, end) =>
          decodeLine(buffer, (start - curStart).toInt, (end - curStart).toInt)

      Tail(lines, Option.when(startOfOldest > 0)(startOfOldest))

  /** Reads earlier blocks, prepending each, until the accumulated buffer holds
    * more than `limit` newlines (enough to bound `limit` complete lines and
    * know the oldest one's start) or the region start is reached. Returns the
    * buffer and the file offset it now begins at.
    */
  @annotation.tailrec
  private def readBackTo(
      curStart: Long,
      buffer: Array[Byte],
      limit: Int,
      blockSize: Int,
      readBlock: (Long, Int) => Array[Byte]
  ): (Long, Array[Byte]) =
    if curStart <= 0 || buffer.count(_ == '\n'.toByte) > limit then
      (curStart, buffer)
    else
      val newStart = math.max(0L, curStart - blockSize)
      val block = readBlock(newStart, (curStart - newStart).toInt)
      readBackTo(newStart, block ++ buffer, limit, blockSize, readBlock)

  /** Decodes `buffer[from, until)` as a UTF-8 line, dropping one trailing `\r`.
    */
  private def decodeLine(buffer: Array[Byte], from: Int, until: Int): String =
    val end =
      if until > from && buffer(until - 1) == '\r'.toByte then until - 1
      else until
    new String(buffer, from, end - from, UTF_8)
