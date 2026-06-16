// PURPOSE: Base ZIO test spec that silences the default console logger
// PURPOSE: Keeps test output pristine when exercised code emits expected debug/error logs

package works.iterative.claude.zio.internal.testing

import zio.*
import zio.test.*

/** Base spec for the ZIO module's unit tests.
  *
  * Removes ZIO's default console logger so that expected debug/error log output
  * produced by the code under test (e.g. a parse error that is also surfaced as
  * a typed error) does not pollute test output. Specs that assert on logging
  * behavior itself should capture logs explicitly via `ZTestLogger`.
  */
trait ClaudeZioSpec extends ZIOSpecDefault:
  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    testEnvironment ++ Runtime.removeDefaultLoggers
