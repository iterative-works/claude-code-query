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
  FileSystemOperations
}
import works.iterative.claude.direct.Logger

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

    def debug(msg: => String): Unit = debugMessages = msg :: debugMessages
    def info(msg: => String): Unit = infoMessages = msg :: infoMessages
    def warn(msg: => String): Unit = warnMessages = msg :: warnMessages
    def error(msg: => String): Unit = errorMessages = msg :: errorMessages
    def error(msg: => String, exception: Throwable): Unit = errorMessages = s"$msg: ${exception.getMessage}" :: errorMessages

  test("should find Claude CLI when available in PATH") {
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

  test("should fall back to common paths when PATH lookup fails") {
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

  test("should return NodeJSNotFoundError when Node.js is missing") {
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

  test(
    "should return CLINotFoundError when Claude CLI is not found anywhere"
  ) {
    // Setup: Mock FileSystemOps returning no claude, but node available
    val mockFs = MockFileSystemOps()
    mockFs.whichResponses = Map(
      "claude" -> None,
      "node" -> Some("/usr/bin/node")
    ) // Node available, claude not
    // All common paths fail too
    mockFs.existsResponses = Map.empty
    mockFs.isExecutableResponses = Map.empty
    given MockLogger = MockLogger()

    // Execute: Call findClaude with mocked dependencies
    val result = CLIDiscovery.findClaude(mockFs, summon[MockLogger])

    // Verify: Should return Left(CLINotFoundError) with installation instructions
    result match
      case Left(CLINotFoundError(message)) =>
        assert(message.contains("Claude Code CLI"))
        assert(message.contains("installation"))
      case other => fail(s"Expected CLINotFoundError but got: $other")

    // Verify: Should log appropriate warnings and errors
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
      logger.warnMessages.exists(_.contains("Claude Code CLI not found"))
    )
  }

  test("should log PATH search attempts and results during CLI discovery") {
    // Setup: Mock FileSystemOps for successful PATH lookup
    val mockFs = MockFileSystemOps()
    mockFs.whichResponses = Map("claude" -> Some("/opt/claude/bin/claude"))
    val mockLogger = MockLogger()

    // Execute: Call findClaude with mocked dependencies
    val result = CLIDiscovery.findClaude(mockFs, mockLogger)

    // Verify: Should return Right with the path from PATH lookup
    assertEquals(result, Right("/opt/claude/bin/claude"))

    // Verify: Should log the PATH search attempt
    assert(
      mockLogger.debugMessages.exists(
        _.contains("Searching for claude in PATH")
      ),
      s"Expected debug message about PATH search but got: ${mockLogger.debugMessages}"
    )

    // Verify: Should log the successful PATH search result
    assert(
      mockLogger.infoMessages.exists(
        _.contains("Found claude in PATH: /opt/claude/bin/claude")
      ),
      s"Expected info message about PATH result but got: ${mockLogger.infoMessages}"
    )

    // Setup: Test case for PATH search failure scenario
    val mockFs2 = MockFileSystemOps()
    mockFs2.whichResponses = Map("claude" -> None)
    mockFs2.existsResponses = Map.empty // No common paths succeed either
    mockFs2.isExecutableResponses = Map.empty
    val mockLogger2 = MockLogger()

    // Execute: Call findClaude with PATH failure
    val result2 = CLIDiscovery.findClaude(mockFs2, mockLogger2)

    // Verify: Should log PATH search failure
    assert(
      mockLogger2.debugMessages.exists(
        _.contains("PATH lookup failed, trying common paths")
      ),
      s"Expected debug message about PATH failure but got: ${mockLogger2.debugMessages}"
    )
  }
