// PURPOSE: Validates a requested transcript page size against the accepted contract range
// PURPOSE: Rejects non-positive and absurd sizes so no read is attempted outside the bound

package works.iterative.claude.core.log

object PageSize:

  /** The largest page a caller may request. A single page holds at most this
    * many entries; a larger request is rejected rather than served, bounding
    * the work a single read can force. This is part of the read contract.
    */
  val Max: Int = 10_000

  /** Accepts a page size only when it is positive and no larger than [[Max]];
    * otherwise rejects it with [[ArchiveError.InvalidPageSize]] so no read is
    * attempted. This is the single choke point every page request passes
    * through.
    */
  def validate(limit: Int): Either[ArchiveError, Int] =
    Either.cond(
      limit >= 1 && limit <= Max,
      limit,
      ArchiveError.InvalidPageSize(limit)
    )
