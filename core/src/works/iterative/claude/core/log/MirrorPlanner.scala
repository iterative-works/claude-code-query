// PURPOSE: Pure planner deciding per-file copy/extend/recopy/skip from source and mirror listings
// PURPOSE: Considers only source files, so files the vendor pruned are kept in the archive (custody)

package works.iterative.claude.core.log

object MirrorPlanner:

  /** Computes the mirror plan from source and mirror file listings, each
    * mapping a tree-relative path to its byte size.
    *
    * Only source files appear in the plan: a file present in the mirror but
    * absent from the source is left in place, so custody survives vendor
    * pruning. Size is the only signal — an `Extend` is a candidate the
    * interpreter must confirm is a true prefix extension before appending.
    */
  def plan(
      source: Map[os.SubPath, Long],
      mirror: Map[os.SubPath, Long]
  ): MirrorPlan =
    val actions = source.toSeq
      .sortBy((rel, _) => rel.toString)
      .map: (rel, srcSize) =>
        mirror.get(rel) match
          case None                           => MirrorAction.Copy(rel)
          case Some(mSize) if srcSize > mSize => MirrorAction.Extend(rel, mSize)
          case Some(mSize) if srcSize < mSize => MirrorAction.Recopy(rel)
          case Some(_)                        => MirrorAction.Skip(rel)
    MirrorPlan(actions)
