// PURPOSE: Trait representing an active conversational session with the Claude Code CLI
// PURPOSE: Provides send/stream/sessionId interface for multi-turn conversations using cats-effect
package works.iterative.claude.effectful

import cats.effect.IO
import fs2.Stream
import works.iterative.claude.core.model.Message

/** An active Claude Code session backed by a long-lived CLI process.
  *
  * Each session maintains a single CLI process whose stdin is kept open for
  * multi-turn conversation. Each turn is initiated with `send` (which writes
  * the prompt to stdin as an IO effect) and then consumed via `stream` (which
  * reads stdout until the ResultMessage signals end-of-turn). The session is
  * acquired as a Resource, which handles process cleanup on both normal exit
  * and error.
  */
trait Session:
  /** Writes a prompt to the session's stdin as an SDKUserMessage JSON line. */
  def send(prompt: String): IO[Unit]

  /** Reads stdout messages for the current turn until a ResultMessage.
    *
    * Returns a Stream of messages that completes after emitting the
    * ResultMessage that terminates the current turn. Must be called after
    * `send`.
    */
  def stream: Stream[IO, Message]

  /** The session ID assigned by the CLI, wrapped in IO since it reads a Ref.
    *
    * Returns IO("pending") until the CLI emits an init SystemMessage or until
    * the first turn completes (at which point the ResultMessage session ID is
    * used).
    */
  def sessionId: IO[String]
