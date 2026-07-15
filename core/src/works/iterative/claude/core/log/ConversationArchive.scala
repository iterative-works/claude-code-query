// PURPOSE: Port for locating, reading, and mirroring a session's vendor record tree (custody)
// PURPOSE: Tagless over an effect F with an abstract entry-stream type, per ConversationLogReader

package works.iterative.claude.core.log

import works.iterative.claude.core.log.model.EntryPage
import works.iterative.claude.core.log.model.MirrorReport
import works.iterative.claude.core.log.model.PageToken
import works.iterative.claude.core.log.model.SessionRecord
import works.iterative.claude.core.model.SessionId

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

  /** Locates a session by encoding the configured cwd, resolving the live
    * vendor tree first and falling back to the archive mirror; `None` when no
    * main transcript exists under either root. The returned record's `root`
    * names which one won.
    */
  def forSession(sessionId: SessionId): F[Option[SessionRecord]]

  /** Streams the main-thread entries of a session. */
  def entries(sessionId: SessionId): EntryStream

  /** Reads the last `limit` main-thread entries of a session without parsing
    * the transcript from the start, newest page first (entries within the page
    * are oldest-first). The page's `older` cursor pages backward through the
    * rest. Resolves vendor-then-mirror like [[forSession]], so a pruned session
    * still tails from the mirror.
    */
  def lastEntries(sessionId: SessionId, limit: Int): F[EntryPage]

  /** Reads the page of up to `limit` entries immediately older than `token`.
    *
    * The token is pinned to the transcript it was minted against; if the
    * session now resolves to a different file (the vendor tree was pruned
    * between pages), this fails with [[ArchiveError.PageSourceMoved]] rather
    * than reading a stale offset against the wrong file.
    */
  def entriesBefore(token: PageToken, limit: Int): F[EntryPage]

  /** Streams a sub-agent's own sidechain entries, joined to its parent by the
    * `tool_use` id recorded in the sub-agent's `.meta.json`.
    */
  def subagentEntries(
      sessionId: SessionId,
      parentToolUseId: String
  ): EntryStream

  /** Idempotently mirrors the whole session tree into the archive directory,
    * reporting what was copied, extended, refreshed, and skipped.
    */
  def mirror(sessionId: SessionId): F[MirrorReport]
