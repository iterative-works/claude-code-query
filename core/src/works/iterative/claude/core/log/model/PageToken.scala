// PURPOSE: Opaque continuation for paging older transcript entries, pinned to one resolved file
// PURPOSE: Carries the source path identity so a page is never read against a different file

package works.iterative.claude.core.log.model

import works.iterative.claude.core.model.SessionId

/** A continuation cursor for reading older entries of a session's transcript.
  *
  * Treat it as opaque: obtain one from a page and hand it back to fetch the
  * previous page. Its fields pin the cursor to a single resolved file so it can
  * never be applied to a different one:
  *
  *   - `offset` is a byte position into an append-only file, so it stays valid
  *     as the file grows.
  *   - `source` is the exact transcript path the cursor was minted against. The
  *     vendor-then-mirror fallback can change which file a session resolves to
  *     between calls (e.g. the vendor tree is pruned mid-paging); when the
  *     current resolution no longer matches `source`, paging fails rather than
  *     reading a stale offset against the wrong file.
  *
  * @param sessionId
  *   the session the cursor pages, re-resolved on each older-page call
  * @param source
  *   the transcript file the cursor's `offset` indexes into
  * @param offset
  *   the exclusive byte bound below which older entries are read
  */
final case class PageToken(
    sessionId: SessionId,
    source: os.Path,
    offset: Long
)
