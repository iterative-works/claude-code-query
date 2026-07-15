// PURPOSE: Unit test for the mirror's file listing tolerating a file that vanished after the walk
// PURPOSE: Exercises the listing seam directly, since injecting the walk-then-size race is unreliable

package works.iterative.claude.zio.log

import zio.*
import zio.test.*
import works.iterative.claude.core.log.ArchiveConfig
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object ArchiveMirrorTest extends ClaudeZioSpec:

  def spec = suite("ArchiveMirror")(
    test("listing skips a file that vanished after it was listed"):
      for
        result <- ZIO.attempt:
          val root = os.temp.dir()
          val kept = root / "kept.jsonl"
          val gone = root / "gone.jsonl"
          os.write(kept, "ab\n")
          os.write(gone, "cdef\n")
          // The file is enumerated by the walk, then pruned before it is sized.
          val _ = os.remove(gone)
          val mirror =
            ArchiveMirror(ArchiveConfig(root, os.temp.dir(), os.Path("/x")))
          mirror.listing(Seq(kept, gone), root)
      yield assertTrue(
        result == Map(os.sub / "kept.jsonl" -> 3L),
        !result.contains(os.sub / "gone.jsonl")
      )
  )
