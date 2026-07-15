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
  * multi-turn conversation. `send` writes a prompt to stdin; `stream` reads
  * stdout until the next ResultMessage — see the hazard note on `stream` before
  * correlating the two. The session is acquired within a `Scope`, whose
  * finalizers shut down the process on both normal exit and error.
  */
trait Session:
  /** Writes a prompt to the session's stdin as an SDKUserMessage JSON line. */
  def send(prompt: String): IO[CLIError, Unit]

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
    *
    * If the process dies mid-turn the stream fails with the recorded
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
