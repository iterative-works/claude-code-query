// PURPOSE: Main API for Claude Code SDK enabling conversational AI interactions
// PURPOSE: Backwards-compatible facade that delegates to effectful implementation

package works.iterative.claude

import cats.effect.IO
import fs2.Stream
import org.typelevel.log4cats.Logger
import works.iterative.claude.core.model.{Message, QueryOptions}

/** Backwards-compatible facade for Claude Code SDK.
  *
  * Delegates to the effectful implementation while maintaining the same API.
  * This allows existing code to continue working without changes.
  */
object ClaudeCode:
  // Public API - delegates to effectful implementation
  def queryResult(options: QueryOptions)(using logger: Logger[IO]): IO[String] =
    works.iterative.claude.effectful.ClaudeCode
      .queryResult(options)(using logger)

  def querySync(options: QueryOptions)(using
      logger: Logger[IO]
  ): IO[List[Message]] =
    works.iterative.claude.effectful.ClaudeCode.querySync(options)(using logger)

  def query(
      options: QueryOptions
  )(using logger: Logger[IO]): Stream[IO, Message] =
    works.iterative.claude.effectful.ClaudeCode.query(options)(using logger)

  // Error context logging methods - delegate to effectful implementation
  def logConfigurationError(
      logger: Logger[IO],
      options: QueryOptions
  ): IO[Unit] =
    works.iterative.claude.effectful.ClaudeCode
      .logConfigurationError(logger, options)

  def logProcessExecutionError(
      logger: Logger[IO],
      error: works.iterative.claude.core.ProcessExecutionError
  ): IO[Unit] =
    works.iterative.claude.effectful.ClaudeCode
      .logProcessExecutionError(logger, error)

  def logJsonParsingError(
      logger: Logger[IO],
      error: works.iterative.claude.core.JsonParsingError
  ): IO[Unit] =
    works.iterative.claude.effectful.ClaudeCode
      .logJsonParsingError(logger, error)
