// PURPOSE: Names which root a located session record was resolved under
// PURPOSE: Distinguishes the live vendor tree from the archive mirror for read fallback and custody

package works.iterative.claude.core.log.model

/** The root a [[SessionRecord]] was resolved under.
  *
  * Resolution tries the live vendor tree first and falls back to the archive
  * mirror, so a session pruned from the vendor directory still reads from the
  * mirror. The root a record won under also decides custody: only a `Vendor`
  * record is a mirror source, so mirroring an `Archive`-resolved session never
  * writes back into the archive.
  */
enum RecordRoot:
  case Vendor
  case Archive
