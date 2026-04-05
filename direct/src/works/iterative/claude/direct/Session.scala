// PURPOSE: Trait representing an active conversational session with the Claude Code CLI
// PURPOSE: Provides send/stream/close interface for multi-turn conversations over a persistent process
package works.iterative.claude.direct

import ox.flow.Flow
import works.iterative.claude.core.model.Message

/** An active Claude Code session backed by a long-lived CLI process.
  *
  * Each session maintains a single CLI process whose stdin is kept open for
  * multi-turn conversation. Each turn is initiated with `send` (which writes
  * the prompt to stdin) and then consumed via `stream` (which reads stdout
  * until the ResultMessage signals end-of-turn). Call `close` to shut down the
  * process cleanly when the session is no longer needed.
  */
trait Session:
  /** Writes a prompt to the session's stdin as an SDKUserMessage JSON line. */
  def send(prompt: String): Unit

  /** Reads stdout messages for the current turn until a ResultMessage.
    *
    * Returns a Flow of messages that completes after emitting the ResultMessage
    * that terminates the current turn. Must be called after `send`.
    */
  def stream(): Flow[Message]

  /** Shuts down the underlying CLI process.
    *
    * Closes stdin, waits briefly for the process to exit, and forcibly destroys
    * it if it does not exit in time.
    */
  def close(): Unit

  /** The session ID assigned by the CLI.
    *
    * Returns "pending" until the CLI emits an init SystemMessage or until the
    * first turn completes (at which point the ResultMessage session ID is
    * used).
    */
  def sessionId: String
