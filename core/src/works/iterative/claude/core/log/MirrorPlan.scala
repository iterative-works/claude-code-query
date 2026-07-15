// PURPOSE: Pure plan describing how a session tree mirror is brought up to date
// PURPOSE: Actions computed from source and mirror file sizes; byte-level checks stay at the edge

package works.iterative.claude.core.log

/** One planned mirror operation for a single file, keyed by its path relative
  * to the tree root.
  */
enum MirrorAction:
  /** File exists in the source tree but not the mirror: copy it whole. */
  case Copy(rel: os.SubPath)

  /** Source is larger than the mirror: a candidate append from `mirroredSize`.
    * The interpreter verifies the mirrored bytes are a prefix of the source
    * before appending, and recopies on any mismatch.
    */
  case Extend(rel: os.SubPath, mirroredSize: Long)

  /** Source is smaller than the mirror (the file shrank): recopy it whole. */
  case Recopy(rel: os.SubPath)

  /** Source and mirror sizes match: assume identical and leave it untouched. */
  case Skip(rel: os.SubPath)

case class MirrorPlan(actions: Seq[MirrorAction])
