// PURPOSE: Port for locating, reading, and mirroring a session's vendor record tree (custody)
// PURPOSE: Tagless over an effect F with an abstract entry-stream type, per ConversationLogReader

package works.iterative.claude.core.log

import works.iterative.claude.core.log.model.MirrorReport
import works.iterative.claude.core.log.model.SessionRecord

/** Custody of a session's vendor record: locate it, read its conversation
  * entries (main thread and sub-agent sidechains), and mirror the whole tree
  * into an archive directory.
  *
  * Locates by encoding a known cwd into the vendor projects directory — never
  * by decoding a directory name, which is ambiguous. The record is a tree (the
  * main `.jsonl` plus the sub-agent `.jsonl` and `.meta.json` sidechains), so
  * the mirror copies the tree, not a single file. Entry reads tolerate vendor
  * drift by degrading unrecognized lines to raw entries rather than losing
  * them.
  *
  * @tparam F
  *   the effect type; the abstract `EntryStream` lets each backend pick its own
  *   streaming type.
  */
trait ConversationArchive[F[_]]:
  type EntryStream

  /** Locates a session by encoding the configured cwd; `None` when no main
    * transcript exists for the id.
    */
  def forSession(sessionId: String): F[Option[SessionRecord]]

  /** Streams the main-thread entries of a session. */
  def entries(sessionId: String): EntryStream

  /** Streams a sub-agent's own sidechain entries, joined to its parent by the
    * `tool_use` id recorded in the sub-agent's `.meta.json`.
    */
  def subagentEntries(sessionId: String, parentToolUseId: String): EntryStream

  /** Idempotently mirrors the whole session tree into the archive directory,
    * reporting what was copied, extended, refreshed, and skipped.
    */
  def mirror(sessionId: String): F[MirrorReport]
