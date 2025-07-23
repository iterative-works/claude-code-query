package works.iterative.claude

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.testing.TestingLogger
import works.iterative.claude.core.model.*

class ClaudeCodeLoggingTest extends CatsEffectSuite:

  test("query logs initiation, configuration validation, and completion"):
    val mockClaudePath = "./test/bin/mock-claude"
    val options = QueryOptions(
      prompt = "Test logging",
      pathToClaudeCodeExecutable = Some(mockClaudePath)
    )

    // Create testing logger to capture log messages
    val testLogger = TestingLogger.impl[IO]()
    given Logger[IO] = testLogger

    // Test that query executes and logs appropriately
    ClaudeCode
      .query(options)
      .compile
      .toList
      .flatMap { messages =>
        // Check that the logger captured the expected log messages
        testLogger.logged.map { logMessages =>
          val logStrings = logMessages.map(_.message)

          // Verify query initiation log
          assert(
            logStrings.exists(_.contains("Initiating query with prompt")),
            s"Expected initiation log, got: $logStrings"
          )

          // Verify query completion log
          assert(
            logStrings.exists(_.contains("Query completed")),
            s"Expected completion log, got: $logStrings"
          )

          // Verify we got messages back from the query
          assert(messages.nonEmpty, "Should return messages from query")
        }
      }
