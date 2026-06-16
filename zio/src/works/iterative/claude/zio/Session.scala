// PURPOSE: Trait representing an active conversational session with the Claude Code CLI
// PURPOSE: Provides send/stream/sessionId interface for multi-turn conversations using ZIO

package works.iterative.claude.zio

import zio.*
import zio.stream.ZStream
import works.iterative.claude.core.CLIError
import works.iterative.claude.core.model.Message

/** An active Claude Code session backed by a long-lived CLI process.
  *
  * Each session maintains a single CLI process whose stdin is kept open for
  * multi-turn conversation. Each turn is initiated with `send` (which writes
  * the prompt to stdin) and then consumed via `stream` (which reads stdout
  * until the ResultMessage signals end-of-turn). The session is acquired within
  * a `Scope`, whose finalizers shut down the process on both normal exit and
  * error.
  */
trait Session:
  /** Writes a prompt to the session's stdin as an SDKUserMessage JSON line. */
  def send(prompt: String): IO[CLIError, Unit]

  /** Reads stdout messages for the current turn until a ResultMessage.
    *
    * Returns a stream of messages that completes after emitting the
    * ResultMessage that terminates the current turn. Must be called after
    * `send`. If the process dies mid-turn the stream fails with the recorded
    * [[CLIError]].
    */
  def stream: ZStream[Any, CLIError, Message]

  /** The session ID assigned by the CLI.
    *
    * Returns "pending" until the CLI emits an init SystemMessage or until the
    * first turn completes (at which point the ResultMessage session ID is
    * used).
    */
  def sessionId: UIO[String]
