// PURPOSE: Assembles a complete byte range from a read primitive that may return short reads
// PURPOSE: Loops single up-to-N reads until the requested range is filled or the file ends

package works.iterative.claude.core.log

object ByteRangeReader:

  /** Reads the bytes in `[offset, offset + length)` by repeating `readUpTo`
    * until the range is filled or the file ends before it.
    *
    * `readUpTo(o, n)` reads *up to* `n` bytes starting at absolute offset `o`,
    * returning fewer only at end of file or on an interrupted read, and an
    * empty array to signal end of file. A single underlying read is
    * contractually allowed to return fewer bytes than requested even mid-file,
    * so concatenating successive reads is what actually yields the full range —
    * satisfying the exact-range contract [[BackwardLineReader]] expects of its
    * `readBlock`.
    */
  def readFully(
      offset: Long,
      length: Int,
      readUpTo: (Long, Int) => Array[Byte]
  ): Array[Byte] =
    @annotation.tailrec
    def loop(at: Long, remaining: Int, acc: Array[Byte]): Array[Byte] =
      if remaining <= 0 then acc
      else
        val chunk = readUpTo(at, remaining)
        if chunk.isEmpty then acc
        else loop(at + chunk.length, remaining - chunk.length, acc ++ chunk)
    loop(offset, length, Array.emptyByteArray)
