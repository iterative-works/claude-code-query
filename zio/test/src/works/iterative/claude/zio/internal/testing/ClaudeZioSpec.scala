// PURPOSE: Base ZIO test spec that silences the default console logger
// PURPOSE: Keeps test output pristine when exercised code emits expected debug/error logs

package works.iterative.claude.zio.internal.testing

import zio.*
import zio.test.*
import works.iterative.claude.core.CLIError
import works.iterative.claude.core.log.ArchiveConfig
import works.iterative.claude.core.model.SessionOptions
import works.iterative.claude.zio.{ClaudeCode, Session}

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

  /** Opens a session against a just-written mock CLI script, retrying the
    * Linux fork/exec "Text file busy" race (JDK-8068370): a concurrently
    * forked test process can inherit the script's write fd for the instant
    * between its fork and exec, so executing the script can fail with ETXTBSY
    * even though the writer already closed it. The race is confined to test
    * infrastructure — production never executes a file it has just written —
    * so the retry lives here rather than in SessionProcess. Requires a live
    * clock (all mock-CLI suites run with `TestAspect.withLiveClock`).
    */
  protected def mockCliSession(
      options: SessionOptions,
      archive: Option[ArchiveConfig] = None
  ): ZIO[Scope, CLIError, Session] =
    ClaudeCode
      .session(options, archive)
      .retry(
        Schedule.recurWhile[CLIError](textFileBusy) &&
          Schedule.spaced(100.millis) &&
          Schedule.recurs(5)
      )

  private def textFileBusy(e: CLIError): Boolean =
    Option(e.getMessage).exists(_.contains("Text file busy"))
