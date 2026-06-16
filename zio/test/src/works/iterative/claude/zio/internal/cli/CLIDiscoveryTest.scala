// PURPOSE: Tests for ZIO CLI discovery using a stubbed file system
// PURPOSE: Verifies PATH lookup, common-path fallback, and typed not-found errors

package works.iterative.claude.zio.internal.cli

import zio.*
import zio.test.*
import works.iterative.claude.core.{CLINotFoundError, NodeJSNotFoundError}
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

/** In-memory FileSystemOps stub driven by explicit data for deterministic tests.
  */
final case class StubFileSystemOps(
    whichResults: Map[String, String] = Map.empty,
    existing: Set[String] = Set.empty,
    executables: Set[String] = Set.empty
) extends FileSystemOps:
  def which(command: String): UIO[Option[String]] =
    ZIO.succeed(whichResults.get(command))
  def exists(path: String): UIO[Boolean] = ZIO.succeed(existing.contains(path))
  def isExecutable(path: String): UIO[Boolean] =
    ZIO.succeed(executables.contains(path))

object CLIDiscoveryTest extends ClaudeZioSpec:
  def spec = suite("CLIDiscovery")(
    test("returns the PATH location when `which claude` succeeds"):
      val fs = StubFileSystemOps(whichResults = Map("claude" -> "/path/claude"))
      for path <- CLIDiscovery.findClaude(fs)
      yield assertTrue(path == "/path/claude"),
    test("falls back to a common executable path when not on PATH"):
      val fs = StubFileSystemOps(
        existing = Set("/usr/local/bin/claude"),
        executables = Set("/usr/local/bin/claude")
      )
      for path <- CLIDiscovery.findClaude(fs)
      yield assertTrue(path == "/usr/local/bin/claude"),
    test("skips a common path that exists but is not executable"):
      val fs = StubFileSystemOps(existing = Set("/usr/local/bin/claude"))
      for error <- CLIDiscovery.findClaude(fs).flip
      yield assertTrue(error.isInstanceOf[NodeJSNotFoundError]),
    test("fails with CLINotFoundError when node is present but claude is not"):
      val fs = StubFileSystemOps(whichResults = Map("node" -> "/usr/bin/node"))
      for error <- CLIDiscovery.findClaude(fs).flip
      yield assertTrue(error.isInstanceOf[CLINotFoundError]),
    test("fails with NodeJSNotFoundError when neither node nor claude exist"):
      val fs = StubFileSystemOps()
      for error <- CLIDiscovery.findClaude(fs).flip
      yield assertTrue(error.isInstanceOf[NodeJSNotFoundError])
  )
