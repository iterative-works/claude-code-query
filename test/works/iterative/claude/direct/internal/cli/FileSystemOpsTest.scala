// PURPOSE: Test direct-style file system operations for CLI discovery functionality
// PURPOSE: Verify PATH lookup and file existence checking without IO effects
package works.iterative.claude.direct.internal.cli

class FileSystemOpsTest extends munit.FunSuite:

  test("which finds existing commands in PATH"):
    // Test with 'sh' command which exists on Unix systems
    val result = FileSystemOps.which("sh")

    // Should return Some(path) where path is a valid executable
    assert(result.isDefined, "sh command should be found in PATH")
    assert(result.get.nonEmpty, "path should not be empty")
    assert(result.get.endsWith("sh"), "path should end with sh")
