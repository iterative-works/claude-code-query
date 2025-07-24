// PURPOSE: Test direct-style file system operations for CLI discovery functionality
// PURPOSE: Verify PATH lookup and file existence checking without IO effects
package works.iterative.claude.direct.internal.cli

class FileSystemOpsTest extends munit.FunSuite:

  test("should find existing commands in PATH using which"):
    // Test with 'sh' command which exists on Unix systems
    val result = FileSystemOps.which("sh")

    // Should return Some(path) where path is a valid executable
    assert(result.isDefined, "sh command should be found in PATH")
    assert(result.get.nonEmpty, "path should not be empty")
    assert(result.get.endsWith("sh"), "path should end with sh")

  test("should return None for non-existent commands when using which"):
    // Test with an impossible command name that definitely doesn't exist
    val result = FileSystemOps.which(
      "this-command-absolutely-does-not-exist-anywhere-12345"
    )

    // Should return None for non-existent commands
    assert(result.isEmpty, "non-existent command should return None")

  test("should correctly identify existing files using exists"):
    // Test with a known existing file (this test file itself)
    val testFilePath =
      "test/works/iterative/claude/direct/internal/cli/FileSystemOpsTest.scala"
    val result = FileSystemOps.exists(testFilePath)

    // Should return true for existing files
    assert(result, "existing file should return true")

  test("should correctly identify executable files using isExecutable"):
    // Test with sh executable that should be found and executable
    val shPath = FileSystemOps.which("sh")
    assert(shPath.isDefined, "sh should be found in PATH for this test")

    val result = FileSystemOps.isExecutable(shPath.get)

    // Should return true for executable files
    assert(result, "sh executable should return true")
