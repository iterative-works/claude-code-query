// PURPOSE: Logger trait for the direct package with slf4j-based default implementation
// PURPOSE: Provides flexible logging with automatic given resolution
package works.iterative.claude.direct

import org.slf4j.LoggerFactory

trait Logger:
  def debug(msg: => String): Unit
  def info(msg: => String): Unit
  def warn(msg: => String): Unit
  def error(msg: => String): Unit
  def error(msg: => String, exception: Throwable): Unit

object Logger:
  // Slf4j-based logger implementation for default logging
  class Slf4jLogger(name: String = "ClaudeCode") extends Logger:
    private val slf4jLogger = LoggerFactory.getLogger(name)

    def debug(msg: => String): Unit =
      if slf4jLogger.isDebugEnabled then slf4jLogger.debug(msg)
    def info(msg: => String): Unit =
      if slf4jLogger.isInfoEnabled then slf4jLogger.info(msg)
    def warn(msg: => String): Unit =
      if slf4jLogger.isWarnEnabled then slf4jLogger.warn(msg)
    def error(msg: => String): Unit =
      if slf4jLogger.isErrorEnabled then slf4jLogger.error(msg)
    def error(msg: => String, exception: Throwable): Unit =
      if slf4jLogger.isErrorEnabled then slf4jLogger.error(msg, exception)

  // Default slf4j-based logger - automatically used via given resolution
  given default: Logger = new Slf4jLogger()
