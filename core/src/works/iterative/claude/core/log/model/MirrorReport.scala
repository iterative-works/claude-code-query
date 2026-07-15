// PURPOSE: Outcome of a mirror run, listing which tree files were copied, extended, refreshed, skipped
// PURPOSE: Reflects what actually happened, including extends that degraded to a full recopy

package works.iterative.claude.core.log.model

/** The result of mirroring a session tree.
  *
  * @param copied
  *   files that were absent from the mirror and copied whole
  * @param extended
  *   files appended in place after the mirrored bytes were confirmed a prefix
  * @param refreshed
  *   files recopied whole because they shrank or their mirrored prefix diverged
  * @param skipped
  *   files left untouched because source and mirror sizes matched
  */
case class MirrorReport(
    copied: Seq[os.SubPath],
    extended: Seq[os.SubPath],
    refreshed: Seq[os.SubPath],
    skipped: Seq[os.SubPath]
):
  def isEmpty: Boolean =
    copied.isEmpty && extended.isEmpty && refreshed.isEmpty && skipped.isEmpty

  /** Total number of files the run considered. */
  def total: Int = copied.size + extended.size + refreshed.size + skipped.size
