// PURPOSE: Direct-style main API for Claude Code using Ox for structured concurrency
// PURPOSE: Provides streaming and sync query methods without cats-effect IO dependencies
package works.iterative.claude.direct

import ox.*
import ox.flow.Flow
import works.iterative.claude.core.model.*
import works.iterative.claude.core.cli.CLIArgumentBuilder
import works.iterative.claude.direct.internal.cli.{ProcessManager, CLIDiscovery, Logger}
import works.iterative.claude.direct.internal.cli.FileSystemOperations

object ClaudeCode:

  // Simple logger implementation for minimal functionality
  given Logger = new Logger:
    def debug(msg: String): Unit = () // Silent for minimal implementation
    def info(msg: String): Unit = () 
    def warn(msg: String): Unit = ()
    def error(msg: String): Unit = ()

  /** Executes a query and returns a Flow of messages from the Claude CLI. Uses
    * Ox direct-style programming for structured concurrency.
    */
  def query(options: QueryOptions)(using ox: Ox): Flow[Message] =
    Flow.fromIterable(executeQuerySync(options))

  private def executeQuerySync(options: QueryOptions)(using ox: Ox): List[Message] =
    // Get executable path
    val executablePath = options.pathToClaudeCodeExecutable.getOrElse {
      throw new RuntimeException("No Claude CLI path provided") // Minimal error handling
    }

    // Build CLI arguments - use executableArgs if provided (for testing), otherwise build from options
    val args = options.executableArgs.getOrElse {
      CLIArgumentBuilder.buildArgs(options) :+ options.prompt
    }

    // Execute process and return messages
    ProcessManager.executeProcess(executablePath, args, options)
