// PURPOSE: Idempotently mirrors a session's record tree into the archive, guarding against symlinks
// PURPOSE: Diffs source vs mirror by size, copies/extends/refreshes per file, and hardens archive dirs

package works.iterative.claude.zio.log

import works.iterative.claude.core.log.ArchiveConfig
import works.iterative.claude.core.log.ArchivePaths
import works.iterative.claude.core.log.MirrorAction
import works.iterative.claude.core.log.MirrorPlanner
import works.iterative.claude.core.log.model.MirrorReport
import works.iterative.claude.core.log.model.SessionRecord

/** Copies a resolved vendor session tree into the archive directory. Each file
  * is planned by size, then copied, extended in place, refreshed, or skipped; a
  * per-file failure is recorded rather than aborting the run. Symlinks are
  * never followed, and the archive's directories are restricted to the owner.
  */
class ArchiveMirror(config: ArchiveConfig):

  /** Mirrors the record's tree into the archive, returning what happened per
    * file. Synchronous filesystem work; the caller wraps it in an effect.
    */
  def mirror(record: SessionRecord): MirrorReport =
    val vendorDir = ArchivePaths.projectDir(config)
    val mirrorDir = ArchivePaths.archiveProjectDir(config)
    val source = listing(sourceFiles(record), vendorDir)
    val mirror = listing(mirrorFiles(record, mirrorDir), mirrorDir)
    val plan = MirrorPlanner.plan(source, mirror)
    val report = plan.actions.foldLeft(MirrorReport.empty): (report, action) =>
      def guarded(rel: os.SubPath)(onSuccess: => MirrorReport): MirrorReport =
        scala.util.Try(onSuccess) match
          case scala.util.Success(updated) => updated
          case scala.util.Failure(cause)   =>
            report.copy(failed =
              report.failed :+ (rel -> failureMessage(cause))
            )
      action match
        case MirrorAction.Skip(rel) =>
          report.copy(skipped = report.skipped :+ rel)
        case MirrorAction.Copy(rel) =>
          guarded(rel):
            copyWhole(vendorDir / rel, mirrorDir / rel)
            report.copy(copied = report.copied :+ rel)
        case MirrorAction.Recopy(rel) =>
          guarded(rel):
            copyWhole(vendorDir / rel, mirrorDir / rel)
            report.copy(refreshed = report.refreshed :+ rel)
        case MirrorAction.Extend(rel, _) =>
          guarded(rel):
            if extendInPlace(vendorDir / rel, mirrorDir / rel) then
              report.copy(extended = report.extended :+ rel)
            else
              copyWhole(vendorDir / rel, mirrorDir / rel)
              report.copy(refreshed = report.refreshed :+ rel)
    restrictArchiveDirs()
    report

  private val isPosix: Boolean =
    java.nio.file.FileSystems.getDefault.supportedFileAttributeViews
      .contains("posix")

  // Vendor transcripts are copied 0600, but os.copy.over creates any missing
  // parent directories with umask perms, leaking the archive's structure to
  // other local users. Restrict the archive directory tree to the owner.
  private def restrictArchiveDirs(): Unit =
    if isPosix && os.exists(config.archiveDir) then
      val ownerOnly = os.PermSet.fromString("rwx------")
      val dirs = config.archiveDir +:
        os.walk(config.archiveDir).filter(os.isDir(_, followLinks = false))
      dirs.foreach(dir => os.perms.set(dir, ownerOnly))

  // Symlinks are excluded from both listings: the archive is a faithful copy of
  // the session's own regular files only, never the content a link points at.
  private def isRegularFile(path: os.Path): Boolean =
    !os.isLink(path) && os.isFile(path)

  private def sourceFiles(record: SessionRecord): Seq[os.Path] =
    val main = Option.when(isRegularFile(record.mainTranscript))(
      record.mainTranscript
    )
    main.toSeq ++ walkRegularFiles(record.treeDir)

  private def mirrorFiles(
      record: SessionRecord,
      mirrorDir: os.Path
  ): Seq[os.Path] =
    val main = mirrorDir / s"${record.sessionId.value}.jsonl"
    val tree = mirrorDir / record.sessionId.value
    val mainFiles = if isRegularFile(main) then Seq(main) else Seq.empty
    mainFiles ++ walkRegularFiles(tree)

  /** Regular files under `root`, excluding symlinks at every level — including
    * `root` itself: `os.walk` dereferences a symlinked root regardless of
    * `followLinks`, and entries behind it then report as regular files.
    */
  private def walkRegularFiles(root: os.Path): Seq[os.Path] =
    if os.exists(root) && !os.isLink(root) then
      os.walk(root).filter(isRegularFile)
    else Seq.empty

  /** Sizes each file, skipping any that vanished after being listed (e.g. a
    * concurrent vendor prune). A skipped file is simply absent from the diff,
    * so it is mirrored or reported on a later run rather than aborting this
    * one.
    */
  private[log] def listing(
      files: Seq[os.Path],
      root: os.Path
  ): Map[os.SubPath, Long] =
    files.flatMap { path =>
      scala.util
        .Try(os.size(path))
        .toOption
        .map(size => path.subRelativeTo(root) -> size)
    }.toMap

  private def copyWhole(source: os.Path, dest: os.Path): Unit =
    os.copy.over(source, dest, createFolders = true)

  /** Appends the source's tail to the mirror only when the mirrored bytes are a
    * true prefix of the source. Returns `false` (leaving the mirror untouched)
    * when the prefix diverges, so the caller falls back to a full recopy.
    */
  private def extendInPlace(source: os.Path, dest: os.Path): Boolean =
    val sourceBytes = os.read.bytes(source)
    val mirrorBytes = os.read.bytes(dest)
    val isPrefix =
      sourceBytes.length >= mirrorBytes.length &&
        java.util.Arrays.equals(
          mirrorBytes,
          sourceBytes.slice(0, mirrorBytes.length)
        )
    if isPrefix then
      os.write.append(dest, sourceBytes.drop(mirrorBytes.length))
      true
    else false

  private def failureMessage(cause: Throwable): String =
    Option(cause.getMessage).getOrElse(cause.toString)
