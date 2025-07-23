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
