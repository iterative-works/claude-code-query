// PURPOSE: Assembles a parsed page of transcript entries from a backward-read tail of raw lines
// PURPOSE: The single place a PageToken is minted, pinning the older-page cursor to one source file

package works.iterative.claude.core.log

import works.iterative.claude.core.log.model.EntryPage
import works.iterative.claude.core.log.model.PageToken
import works.iterative.claude.core.log.parsing.ConversationLogParser
import works.iterative.claude.core.model.SessionId

object TranscriptPage:

  /** Assembles the page of parsed entries for the trailing `tail` of a
    * transcript, minting the older-page cursor pinned to `source` so it can
    * only ever be read back against the same file. Blank or unparseable lines
    * are dropped by the tolerant parser; unknown vendor types survive as raw
    * entries.
    */
  def fromTail(
      sessionId: SessionId,
      source: os.Path,
      tail: BackwardLineReader.Tail
  ): EntryPage =
    val entries = tail.lines.flatMap(ConversationLogParser.parseLogLine)
    val older = tail.older.map(offset => PageToken(sessionId, source, offset))
    EntryPage(entries, older)
