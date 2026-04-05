// PURPOSE: Shared mock Logger for testing that captures log messages by level
// PURPOSE: Used across unit, integration, and E2E test suites
package works.iterative.claude.direct.internal.testing

import works.iterative.claude.direct.Logger

class MockLogger extends Logger:
  var debugMessages: List[String] = List.empty
  var infoMessages: List[String] = List.empty
  var warnMessages: List[String] = List.empty
  var errorMessages: List[String] = List.empty

  def debug(msg: => String): Unit = debugMessages = msg :: debugMessages
  def info(msg: => String): Unit = infoMessages = msg :: infoMessages
  def warn(msg: => String): Unit = warnMessages = msg :: warnMessages
  def error(msg: => String): Unit = errorMessages = msg :: errorMessages
  def error(msg: => String, exception: Throwable): Unit = errorMessages =
    s"$msg: ${exception.getMessage}" :: errorMessages
