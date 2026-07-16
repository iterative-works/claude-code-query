// PURPOSE: One page of main-thread transcript entries with a cursor to the next older page
// PURPOSE: Entries are oldest-first within the page; an absent cursor means the file start was reached

package works.iterative.claude.core.log.model

/** A bounded page of a session's main-thread entries.
  *
  * @param entries
  *   the page's entries in file order (oldest first), already parsed with the
  *   tolerant line parser, so unrecognized vendor lines survive as raw entries
  *   and blank or unparseable lines are omitted
  * @param older
  *   a cursor to the page immediately before this one, or `None` when this page
  *   reaches the start of the transcript
  */
final case class EntryPage(
    entries: Seq[ConversationLogEntry],
    older: Option[PageToken]
)
