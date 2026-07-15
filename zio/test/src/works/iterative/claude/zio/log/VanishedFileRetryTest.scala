// PURPOSE: Unit tests for the vendor-prune retry: vanished-file classification and one-retry reads
// PURPOSE: Stubs the read/resolve seams directly, so no filesystem race has to be injected

package works.iterative.claude.zio.log

import zio.*
import zio.stream.*
import zio.test.*
import works.iterative.claude.core.log.ArchiveError
import works.iterative.claude.core.log.ArchiveError.ArchiveIOError
import works.iterative.claude.core.log.ArchiveError.SessionNotFound
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object VanishedFileRetryTest extends ClaudeZioSpec:

  private val pathA = os.Path("/vendor/a.jsonl")
  private val pathB = os.Path("/archive/a.jsonl")

  private val vanished: ArchiveError =
    ArchiveIOError("gone", new java.nio.file.NoSuchFileException("/vendor/a"))

  private val otherIO: ArchiveError =
    ArchiveIOError("disk", new java.io.IOException("boom"))

  def spec = suite("VanishedFileRetry")(
    suite("isVanished")(
      test("a bare NoSuchFileException is vanished"):
        assertTrue(
          VanishedFileRetry.isVanished(
            new java.nio.file.NoSuchFileException("x")
          )
        ),
      test("a FileNotFoundException is vanished"):
        assertTrue(
          VanishedFileRetry.isVanished(new java.io.FileNotFoundException("x"))
        ),
      test("an ArchiveIOError wrapping a missing file is vanished"):
        assertTrue(VanishedFileRetry.isVanished(vanished)),
      test("an ArchiveIOError wrapping an unrelated fault is not vanished"):
        assertTrue(!VanishedFileRetry.isVanished(otherIO)),
      test("a deeper cause chain is walked"):
        val wrapped = new java.io.IOException(
          "outer",
          new java.nio.file.NoSuchFileException("inner")
        )
        assertTrue(VanishedFileRetry.isVanished(wrapped))
    ),
    suite("read")(
      test("a vanished first read re-resolves once and retries the new path"):
        for
          calls <- Ref.make(List.empty[os.Path])
          read = (p: os.Path) =>
            calls.update(p :: _) *>
              (if p == pathA then ZIO.fail(vanished) else ZIO.succeed("ok"))
          result <- VanishedFileRetry
            .read(pathA, read, ZIO.succeed(pathB))
          seen <- calls.get
        yield assertTrue(result == "ok", seen == List(pathB, pathA))
      ,
      test("a non-vanished error is surfaced without re-resolving"):
        for
          resolved <- Ref.make(false)
          read = (_: os.Path) => ZIO.fail(otherIO)
          reResolve = resolved.set(true).as(pathB)
          result <- VanishedFileRetry.read(pathA, read, reResolve).either
          didResolve <- resolved.get
        yield assertTrue(result == Left(otherIO), !didResolve)
      ,
      test("a second vanish after retry is surfaced, not retried again"):
        for
          calls <- Ref.make(0)
          read = (_: os.Path) => calls.update(_ + 1) *> ZIO.fail(vanished)
          result <- VanishedFileRetry.read(pathA, read, ZIO.succeed(pathB)).either
          count <- calls.get
        yield assertTrue(result == Left(vanished), count == 2)
      ,
      test("re-resolution that finds nothing fails with the chosen error"):
        val gone = SessionNotFound("s")
        val read = (_: os.Path) => ZIO.fail(vanished)
        for result <- VanishedFileRetry
            .read(pathA, read, ZIO.fail(gone))
            .either
        yield assertTrue(result == Left(gone))
    ),
    suite("stream")(
      test("a vanished open re-resolves once and streams the new path"):
        val open = (p: os.Path) =>
          if p == pathA then ZStream.fail(vanished)
          else ZStream.fromIterable(List(1, 2, 3))
        for
          next <- Ref.make(List(Some(pathA), Some(pathB)))
          resolve = next.modify:
            case head :: tail => (head, tail)
            case Nil          => (Some(pathB), Nil)
          out <- VanishedFileRetry
            .stream(open, resolve, SessionNotFound("s"))
            .runCollect
        yield assertTrue(out.toList == List(1, 2, 3))
      ,
      test("a resolution that finds nothing fails the stream with the chosen error"):
        val open = (_: os.Path) => ZStream.fromIterable(List(1))
        val gone = SessionNotFound("s")
        for result <- VanishedFileRetry
            .stream(open, ZIO.none, gone)
            .runCollect
            .either
        yield assertTrue(result == Left(gone))
    )
  )
