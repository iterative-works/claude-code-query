package works.iterative.claude.core

// PURPOSE: Error types for CLI discovery and execution failures
// PURPOSE: Provides structured error handling with actionable messages

// CLI Discovery error types
sealed trait CLIError extends Throwable:
  def message: String
  override def getMessage: String = message

case class CLINotFoundError(message: String) extends CLIError
case class NodeJSNotFoundError(message: String) extends CLIError
case class ProcessError(message: String, exitCode: Int) extends CLIError
case class ProcessExecutionError(
    exitCode: Int,
    stderr: String,
    command: List[String]
) extends CLIError:
  val message =
    s"Process failed with exit code $exitCode. Command: ${command.mkString(" ")}. Error: $stderr"

case class JsonParsingError(
    line: String,
    lineNumber: Int,
    cause: Throwable
) extends CLIError:
  val message =
    s"Failed to parse JSON at line $lineNumber: ${cause.getMessage}. Content: ${line
        .take(100)}${if (line.length > 100) "..." else ""}"

case class ProcessTimeoutError(
    timeoutDuration: scala.concurrent.duration.FiniteDuration,
    command: List[String]
) extends CLIError:
  val message =
    s"Process timed out after ${timeoutDuration.toSeconds} seconds. Command: ${command.mkString(" ")}"

case class ConfigurationError(
    parameter: String,
    value: String,
    reason: String
) extends CLIError:
  val message =
    s"Invalid configuration for parameter '$parameter' with value '$value': $reason"

case class EnvironmentValidationError(
    invalidVariables: List[String],
    reason: String
) extends CLIError:
  val message =
    s"Invalid environment variable names: ${invalidVariables.mkString(", ")}. $reason"
