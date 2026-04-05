// PURPOSE: Trait representing an active conversational session with the Claude Code CLI
// PURPOSE: Provides send/close interface for multi-turn conversations over a persistent process
package works.iterative.claude.direct

import ox.flow.Flow
import works.iterative.claude.core.model.Message

/** An active Claude Code session backed by a long-lived CLI process.
  *
  * Each session maintains a single CLI process whose stdin is kept open for
  * multi-turn conversation. Prompts are submitted via `send`, which writes an
  * SDKUserMessage to stdin and streams stdout messages back as a Flow until a
  * ResultMessage signals end-of-turn. Call `close` to shut down the process
  * cleanly when the session is no longer needed.
  */
trait Session:
  /** Sends a prompt to the session and streams the response.
    *
    * Writes an SDKUserMessage JSON line to the process stdin, then returns a
    * Flow of messages read from stdout. The Flow completes after emitting the
    * ResultMessage that terminates the current turn.
    */
  def send(prompt: String): Flow[Message]

  /** Shuts down the underlying CLI process.
    *
    * Closes stdin, waits briefly for the process to exit, and forcibly destroys
    * it if it does not exit in time.
    */
  def close(): Unit

  /** The session ID assigned by the CLI.
    *
    * Returns "pending" until the CLI emits an init SystemMessage or until the
    * first send completes (at which point the ResultMessage session ID is
    * used).
    */
  def sessionId: String
