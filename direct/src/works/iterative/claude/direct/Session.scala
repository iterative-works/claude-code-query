// PURPOSE: Trait representing an active conversational session with the Claude Code CLI
// PURPOSE: Provides send/stream/close interface for multi-turn conversations over a persistent process
package works.iterative.claude.direct

import ox.flow.Flow
import works.iterative.claude.core.model.Message

/** An active Claude Code session backed by a long-lived CLI process.
  *
  * Each session maintains a single CLI process whose stdin is kept open for
  * multi-turn conversation. `send` writes a prompt to stdin; `stream` reads
  * stdout until the next ResultMessage — see the hazard note on `stream` before
  * correlating the two. Call `close` to shut down the process cleanly when the
  * session is no longer needed.
  */
trait Session:
  /** Writes a prompt to the session's stdin as an SDKUserMessage JSON line. */
  def send(prompt: String): Unit

  /** Reads stdout messages until the next ResultMessage.
    *
    * HAZARD — the messages read are NOT "the reply to your last send". The
    * underlying message queue is shared and long-lived across the whole
    * session, and the CLI owns turn boundaries:
    *   - it emits output with no send in flight (a background task finishing
    *     resumes the main loop and produces a full extra ResultMessage), and
    *   - it merges a send issued while a turn is running into that turn, so the
    *     merged send never gets a ResultMessage of its own.
    * Correlating `send` and `stream` positionally therefore attributes replies
    * to the wrong message. At most one consumer may read at a time; concurrent
    * readers silently split the messages between them. See
    * docs/adr/0001-session-is-a-stream-not-turns.md.
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
