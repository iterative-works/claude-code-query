// PURPOSE: Tests for direct-style CLI discovery functionality
// PURPOSE: Verifies PATH lookup and error handling without IO effects
package works.iterative.claude.direct.internal.cli

import works.iterative.claude.core.{
  CLIError,
  CLINotFoundError,
  NodeJSNotFoundError
}
import works.iterative.claude.direct.internal.cli.{
  CLIDiscovery,
  Logger,
  FileSystemOperations
}

class CLIDiscoveryTest extends munit.FunSuite:

  // Mock FileSystemOps for testing
  class MockFileSystemOps extends FileSystemOperations:
    var whichResponses: Map[String, Option[String]] = Map.empty
    var existsResponses: Map[String, Boolean] = Map.empty
    var isExecutableResponses: Map[String, Boolean] = Map.empty

    def which(command: String): Option[String] =
      whichResponses.get(command).flatten
    def exists(path: String): Boolean = existsResponses.getOrElse(path, false)
    def isExecutable(path: String): Boolean =
      isExecutableResponses.getOrElse(path, false)

  // Mock Logger for testing
  class MockLogger extends Logger:
    var debugMessages: List[String] = List.empty
    var infoMessages: List[String] = List.empty
    var warnMessages: List[String] = List.empty
    var errorMessages: List[String] = List.empty

    def debug(msg: String): Unit = debugMessages = msg :: debugMessages
    def info(msg: String): Unit = infoMessages = msg :: infoMessages
    def warn(msg: String): Unit = warnMessages = msg :: warnMessages
    def error(msg: String): Unit = errorMessages = msg :: errorMessages

  test("T2.1: findClaude succeeds when claude is found in PATH") {
    // Setup: Mock FileSystemOps to return claude path from which
    val mockFs = MockFileSystemOps()
    mockFs.whichResponses = Map("claude" -> Some("/usr/local/bin/claude"))
    given MockLogger = MockLogger()

    // Execute: Call findClaude with mocked dependencies
    val result = CLIDiscovery.findClaude(mockFs, summon[MockLogger])

    // Verify: Should return Right with the path from PATH lookup
    assertEquals(result, Right("/usr/local/bin/claude"))

    // Verify: Should log the PATH search attempt and success
    val logger = summon[MockLogger]
    assert(
      logger.debugMessages.exists(_.contains("Searching for claude in PATH"))
    )
    assert(
      logger.infoMessages.exists(
        _.contains("Found claude in PATH: /usr/local/bin/claude")
      )
    )
  }

  test("T2.2: findClaude falls back to common paths when PATH lookup fails") {
    // Setup: Mock FileSystemOps with PATH failure but common path success
    val mockFs = MockFileSystemOps()
    mockFs.whichResponses = Map("claude" -> None) // PATH lookup fails
    // Simulate finding claude in a common installation path
    val commonPath = "/usr/local/bin/claude"
    mockFs.existsResponses = Map(commonPath -> true)
    mockFs.isExecutableResponses = Map(commonPath -> true)
    given MockLogger = MockLogger()

    // Execute: Call findClaude with mocked dependencies
    val result = CLIDiscovery.findClaude(mockFs, summon[MockLogger])

    // Verify: Should return Right with path from common installation paths
    assertEquals(result, Right(commonPath))

    // Verify: Should log fallback behavior
    val logger = summon[MockLogger]
    assert(
      logger.debugMessages.exists(_.contains("Searching for claude in PATH"))
    )
    assert(
      logger.debugMessages.exists(
        _.contains("PATH lookup failed, trying common paths")
      )
    )
    assert(
      logger.infoMessages.exists(
        _.contains(s"Found claude at common path: $commonPath")
      )
    )
  }

  test("T2.3: findClaude returns NodeJSNotFoundError when Node.js is missing") {
    // Setup: Mock FileSystemOps returning no claude and no node
    val mockFs = MockFileSystemOps()
    mockFs.whichResponses = Map("claude" -> None, "node" -> None) // Both fail
    // All common paths fail too
    mockFs.existsResponses = Map.empty
    mockFs.isExecutableResponses = Map.empty
    given MockLogger = MockLogger()

    // Execute: Call findClaude with mocked dependencies
    val result = CLIDiscovery.findClaude(mockFs, summon[MockLogger])

    // Verify: Should return Left(NodeJSNotFoundError) with installation guide
    result match
      case Left(NodeJSNotFoundError(message)) =>
        assert(message.contains("Node.js"))
        assert(message.contains("installation"))
      case other => fail(s"Expected NodeJSNotFoundError but got: $other")

    // Verify: Should log prerequisite checking
    val logger = summon[MockLogger]
    assert(
      logger.debugMessages.exists(_.contains("Checking for Node.js"))
    )
    assert(
      logger.errorMessages.exists(_.contains("Node.js not found"))
    )
  }
