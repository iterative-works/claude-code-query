// PURPOSE: Test configuration constants for timeouts, delays, and test data sizes
// PURPOSE: Centralizes magic numbers used across test files for consistency and maintainability

package works.iterative.claude.direct.internal.testing

import scala.concurrent.duration.*

/** Centralized constants for test configuration to eliminate magic numbers and
  * improve test maintainability. All timeout values, delays, and test data
  * sizes are defined here with descriptive names.
  */
object TestConstants:

  /** Timeout durations for different test scenarios */
  object Timeouts:
    // Very short timeouts for quick failure tests
    val TEST_TIMEOUT_VERY_SHORT = Duration(100, "milliseconds")
    val TEST_TIMEOUT_SHORT = Duration(200, "milliseconds")
    val TEST_TIMEOUT_MEDIUM_SHORT = Duration(500, "milliseconds")

    // Standard timeouts for normal test operations
    val TEST_TIMEOUT_STANDARD = Duration(1000, "milliseconds")
    val TEST_TIMEOUT_MEDIUM = Duration(1500, "milliseconds")
    val TEST_TIMEOUT_LONG = Duration(2000, "milliseconds")

    // Extended timeouts for integration tests
    val TEST_TIMEOUT_VERY_LONG = Duration(5, "seconds")
    val TEST_TIMEOUT_EXTENDED = Duration(10, "seconds")
    val TEST_TIMEOUT_MAX = Duration(30, "seconds")

    // ScalaConcurrent FiniteDuration versions for ProcessManager
    val FINITE_TIMEOUT_VERY_SHORT = FiniteDuration(100, MILLISECONDS)
    val FINITE_TIMEOUT_SHORT = FiniteDuration(200, MILLISECONDS)
    val FINITE_TIMEOUT_MEDIUM_SHORT = FiniteDuration(500, MILLISECONDS)
    val FINITE_TIMEOUT_STANDARD = FiniteDuration(1000, MILLISECONDS)
    val FINITE_TIMEOUT_MEDIUM = FiniteDuration(1500, MILLISECONDS)

    // Eventually timeout for resource cleanup verification
    val EVENTUALLY_TIMEOUT = 5.seconds
    val EVENTUALLY_INTERVAL = 100.millis

  /** Mock CLI delay configurations for realistic timing simulation */
  object MockDelays:
    // Very fast delays for performance tests
    val MOCK_MESSAGE_DELAY_MINIMAL = Duration(1, "milliseconds")
    val MOCK_MESSAGE_DELAY_TINY = Duration(2, "milliseconds")
    val MOCK_MESSAGE_DELAY_VERY_FAST = Duration(5, "milliseconds")
    val MOCK_MESSAGE_DELAY_FAST = Duration(10, "milliseconds")

    // Standard delays for realistic CLI simulation
    val MOCK_MESSAGE_DELAY_STANDARD = Duration(25, "milliseconds")
    val MOCK_MESSAGE_DELAY_MEDIUM = Duration(30, "milliseconds")
    val MOCK_MESSAGE_DELAY_COMFORTABLE = Duration(50, "milliseconds")
    val MOCK_MESSAGE_DELAY_SLOW = Duration(75, "milliseconds")
    val MOCK_MESSAGE_DELAY_VERY_SLOW = Duration(100, "milliseconds")

    // Mock CLI hang durations for timeout testing
    val MOCK_HANG_DURATION_SHORT = Duration(5, "seconds")
    val MOCK_HANG_DURATION_STANDARD = Duration(10, "seconds")
    val MOCK_HANG_DURATION_LONG = Duration(30, "seconds")

  /** Test data sizes for large content and stress testing */
  object TestDataSizes:
    val SMALL_DATA_SIZE = 100
    val MEDIUM_DATA_SIZE = 1000
    val LARGE_DATA_SIZE = 10000

    // Message counts for concurrent processing tests
    val MANY_MESSAGES_COUNT = 100
    val MIXED_MESSAGE_TYPES_COUNT = 10

  /** Common JSON mock values used in test responses */
  object MockJsonValues:
    // Duration values commonly used in ResultMessage mocks
    val MOCK_DURATION_MS_FAST = 500
    val MOCK_DURATION_MS_STANDARD = 1000
    val MOCK_DURATION_MS_SLOW = 1500
    val MOCK_DURATION_MS_VERY_SLOW = 2000
    val MOCK_DURATION_MS_COMPLEX = 2500

    val MOCK_DURATION_API_MS_FAST = 300
    val MOCK_DURATION_API_MS_STANDARD = 500
    val MOCK_DURATION_API_MS_SLOW = 800
    val MOCK_DURATION_API_MS_VERY_SLOW = 1200
    val MOCK_DURATION_API_MS_COMPLEX = 1800

    // Common session and user identifiers
    val MOCK_SESSION_ID = "session_123"
    val MOCK_USER_ID = "user_123"
    val MOCK_WORKSPACE_ID = "workspace_456"
    val MOCK_SESSION_ID_SECONDARY = "session_789"

    // Turn counts for conversation flow testing
    val MOCK_NUM_TURNS_SINGLE = 1
    val MOCK_NUM_TURNS_DOUBLE = 2

  /** Wait and polling intervals for asynchronous operations */
  object WaitIntervals:
    val POLLING_INTERVAL_FAST = 100.millis
    val WAIT_TIMEOUT_SHORT = 5.seconds

    // Sleep durations for system command testing
    val SLEEP_DURATION_SHORT = "5" // 5 seconds
    val SLEEP_DURATION_MEDIUM = "10" // 10 seconds
    val SLEEP_DURATION_LONG = "30" // 30 seconds

  /** Process exit codes for error testing */
  object ExitCodes:
    val SUCCESS = 0
    val GENERAL_ERROR = 1
    val INVALID_USAGE = 2
    val TIMEOUT_ERROR = 3

  /** Tolerance values for timing precision tests */
  object ToleranceValues:
    val TIMING_TOLERANCE_MS_STRICT = 200.0 // 200ms for strict timing tests
    val TIMING_TOLERANCE_MS_LENIENT = 250.0 // 250ms for lenient timing tests
    val TIMING_TOLERANCE_MS_VERY_LENIENT =
      300.0 // 300ms for very lenient timing tests

    // Relative tolerance percentages
    val TIMING_TOLERANCE_PERCENT_SHORT = 0.4 // 40% for short timeouts
    val TIMING_TOLERANCE_PERCENT_LONG = 0.3 // 30% for long timeouts
    val TIMING_TOLERANCE_PERCENT_LENIENT = 0.8 // 80% for very short timeouts

    // Maximum deviation for consistency tests
    val MAX_DEVIATION_MS_CONSISTENCY = 300 // 300ms max deviation between runs

  /** Common test parameter values */
  object TestParameters:
    val MAX_TURNS_TEST = 5
    val MAX_THINKING_TOKENS_SMALL = 1000
    val MAX_THINKING_TOKENS_LARGE = 5000
    val MAX_THINKING_TOKENS_MAX = 10000

    // Test iteration counts
    val CONSISTENCY_TEST_ITERATIONS = 5
    val CONCURRENT_OPERATIONS_COUNT = 4

    // Sequential message generation
    val SEQUENTIAL_MESSAGE_COUNT = 100
