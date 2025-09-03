package works.iterative.claude.effectful.internal.cli

// PURPOSE: Unit tests for FileSystemOps real implementations
// PURPOSE: Verifies actual file system operations work correctly

import cats.effect.IO
import munit.CatsEffectSuite
import works.iterative.claude.effectful.internal.cli.RealFileSystemOps
import works.iterative.claude.direct.internal.testing.TestAssumptions.*

class FileSystemOpsTest extends CatsEffectSuite:

  test("RealFileSystemOps.which finds existing commands in PATH"):
    assumeUnixWithCommands("sh", "which")

    // Test with a command that should exist on most Unix systems
    RealFileSystemOps
      .which("sh")
      .map: result =>
        assert(result.isDefined, "sh command should be found in PATH")
        assert(result.get.nonEmpty, "path should not be empty")
        assert(result.get.endsWith("sh"), "path should end with sh")

  test("RealFileSystemOps.which returns None for non-existent commands"):
    assumeUnixWithCommands("which")

    // Test with a command that should not exist
    RealFileSystemOps
      .which("definitely-does-not-exist-command-12345")
      .map: result =>
        assertEquals(result, None, "non-existent command should return None")

  test("RealFileSystemOps.which handles commands with special characters"):
    assumeUnixWithCommands("which")

    // Test edge case with special characters (should return None safely)
    RealFileSystemOps
      .which("command-with-special-chars-!@#$%")
      .map: result =>
        assertEquals(
          result,
          None,
          "command with special chars should return None"
        )

  test("RealFileSystemOps.which handles empty command string"):
    assumeUnixWithCommands("which")

    // Test edge case with empty string
    RealFileSystemOps
      .which("")
      .map: result =>
        assertEquals(result, None, "empty command should return None")
