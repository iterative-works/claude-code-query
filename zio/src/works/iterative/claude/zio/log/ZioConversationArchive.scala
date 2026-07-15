// PURPOSE: Task-based ConversationArchive that locates, reads, tails, and mirrors a session record tree
// PURPOSE: Resolves vendor-then-mirror, keeps custody one-directional, and interprets the pure plan with IO

package works.iterative.claude.zio.log

import zio.*
import zio.stream.*
import works.iterative.claude.core.log.ArchiveConfig
import works.iterative.claude.core.log.ArchiveError
import works.iterative.claude.core.log.ArchiveError.ArchiveIOError
import works.iterative.claude.core.log.ArchiveError.PageSourceMoved
import works.iterative.claude.core.log.ArchiveError.SessionNotFound
import works.iterative.claude.core.log.ArchiveError.SubAgentNotFound
import works.iterative.claude.core.log.ArchivePaths
import works.iterative.claude.core.log.BackwardLineReader
import works.iterative.claude.core.log.ByteRangeReader
import works.iterative.claude.core.log.ConversationArchive
import works.iterative.claude.core.log.MirrorAction
import works.iterative.claude.core.log.MirrorPlanner
import works.iterative.claude.core.log.PageSize
import works.iterative.claude.core.log.TranscriptPage
import works.iterative.claude.core.log.model.ConversationLogEntry
import works.iterative.claude.core.log.model.EntryPage
import works.iterative.claude.core.log.model.MirrorReport
import works.iterative.claude.core.log.model.PageToken
import works.iterative.claude.core.log.model.RecordRoot
import works.iterative.claude.core.log.model.SessionRecord
import works.iterative.claude.core.model.SessionId

class ZioConversationArchive private (config: ArchiveConfig)
    extends ConversationArchive[[A] =>> IO[ArchiveError, A]]:

  import ZioConversationArchive.TranscriptPresence

  type EntryStream = ZStream[Any, ArchiveError, ConversationLogEntry]

  private val reader = ZioConversationLogReader()

  // Sub-agent discovery reuses the index's tested join. Only `listSubAgents`
  // is called, and it takes the project path explicitly, so the override and
  // home passed here are irrelevant.
  private val index = ZioConversationLogIndex.make(None, os.home)

  // Backward reads pull this many bytes per step; tailing a page touches only a
  // few blocks near the end, never the whole transcript.
  private val tailBlockSize = 64 * 1024

  def forSession(
      sessionId: SessionId
  ): IO[ArchiveError, Option[SessionRecord]] =
    locate(sessionId)

  def entries(sessionId: SessionId): EntryStream =
    VanishedFileRetry.stream(
      transcriptStream,
      resolveMainTranscript(sessionId),
      SessionNotFound(sessionId.value)
    )

  def lastEntries(
      sessionId: SessionId,
      limit: Int
  ): IO[ArchiveError, EntryPage] =
    ZIO.fromEither(PageSize.validate(limit)) *>
      locate(sessionId).flatMap:
        case Some(record) =>
          VanishedFileRetry.read(
            record.mainTranscript,
            p => readPage(sessionId, p, None, limit),
            requireResolved(sessionId)
          )
        case None => ZIO.fail(SessionNotFound(sessionId.value))

  def entriesBefore(
      token: PageToken,
      limit: Int
  ): IO[ArchiveError, EntryPage] =
    for
      _ <- ZIO.fromEither(PageSize.validate(limit))
      current <- resolveMainTranscript(token.sessionId)
      page <- current match
        case Some(path) if path == token.source =>
          VanishedFileRetry.read(
            token.source,
            p => readPage(token.sessionId, p, Some(token.offset), limit),
            repinSource(token)
          )
        case Some(_) => ZIO.fail(PageSourceMoved(token.sessionId.value))
        case None    => ZIO.fail(SessionNotFound(token.sessionId.value))
    yield page

  def subagentEntries(
      sessionId: SessionId,
      parentToolUseId: String
  ): EntryStream =
    VanishedFileRetry.stream(
      transcriptStream,
      locateSubAgentTranscript(sessionId, parentToolUseId),
      SubAgentNotFound(sessionId.value, parentToolUseId)
    )

  def mirror(sessionId: SessionId): IO[ArchiveError, MirrorReport] =
    locate(sessionId).flatMap:
      case Some(record) if record.root == RecordRoot.Vendor =>
        ZIO.attemptBlocking(runMirror(record)).mapError(toArchiveError)
      // Vendor tree absent, session served from the archive: custody is
      // one-directional, so the mirror is never written back from nothing.
      case Some(_) => ZIO.succeed(MirrorReport.empty)
      case None    => ZIO.fail(SessionNotFound(sessionId.value))

  /** Resolves a session's record vendor-first, then from the archive mirror,
    * keeping the first whose main transcript is confirmed present. A candidate
    * whose presence cannot be determined — its project directory exists but is
    * unreadable, so "absent" would be a lie — fails typed rather than silently
    * falling through to the next root.
    */
  private def locate(
      sessionId: SessionId
  ): IO[ArchiveError, Option[SessionRecord]] =
    for
      candidates <- ZIO.fromEither(ArchivePaths.candidates(config, sessionId))
      found <- ZIO
        .attemptBlocking(resolveFirst(candidates))
        .mapError(toArchiveError)
        .flatMap(ZIO.fromEither)
    yield found

  /** Keeps the first candidate whose main transcript is confirmed present.
    * Skips confirmed-absent candidates; fails typed on the first candidate
    * whose presence is indeterminate, so an unreadable vendor tree is never
    * mistaken for a pruned one.
    */
  private def resolveFirst(
      candidates: Seq[SessionRecord]
  ): Either[ArchiveError, Option[SessionRecord]] =
    candidates match
      case Seq()        => Right(None)
      case head +: tail =>
        transcriptPresence(head.mainTranscript) match
          case TranscriptPresence.Present       => Right(Some(head))
          case TranscriptPresence.Absent        => resolveFirst(tail)
          case TranscriptPresence.Indeterminate =>
            Left(
              ArchiveIOError(
                s"Cannot determine whether ${head.mainTranscript} exists; " +
                  "its project directory is present but unreadable",
                new java.io.IOException("indeterminate transcript existence")
              )
            )

  /** The path of a session's currently-resolved main transcript, if any. */
  private def resolveMainTranscript(
      sessionId: SessionId
  ): IO[ArchiveError, Option[os.Path]] =
    locate(sessionId).map(_.map(_.mainTranscript))

  /** Re-resolves a session to a readable main transcript for a retry, failing
    * with `SessionNotFound` when it no longer resolves under either root.
    */
  private def requireResolved(
      sessionId: SessionId
  ): IO[ArchiveError, os.Path] =
    resolveMainTranscript(sessionId).flatMap:
      case Some(path) => ZIO.succeed(path)
      case None       => ZIO.fail(SessionNotFound(sessionId.value))

  /** Re-resolves a paged token's source for a retry, preserving the pin: a
    * resolution that moved off `token.source` (e.g. the vendor tree was pruned)
    * fails with `PageSourceMoved` rather than reading the wrong file.
    */
  private def repinSource(token: PageToken): IO[ArchiveError, os.Path] =
    resolveMainTranscript(token.sessionId).flatMap:
      case Some(path) if path == token.source => ZIO.succeed(path)
      case Some(_) => ZIO.fail(PageSourceMoved(token.sessionId.value))
      case None    => ZIO.fail(SessionNotFound(token.sessionId.value))

  /** Classifies whether `path` is a readable transcript, distinguishing a
    * confirmed-absent file from one whose existence cannot be determined. The
    * `java.nio.file.Files` predicates all report `false` on an access failure,
    * so a lone `exists` check cannot tell "absent" from "unreadable"; the pure
    * [[ZioConversationArchive.classifyPresence]] recovers the indeterminate
    * outcome by combining them.
    */
  private def transcriptPresence(path: os.Path): TranscriptPresence =
    val nio = path.toNIO
    ZioConversationArchive.classifyPresence(
      java.nio.file.Files.isRegularFile(nio),
      java.nio.file.Files.notExists(nio),
      java.nio.file.Files.exists(nio)
    )

  private def readPage(
      sessionId: SessionId,
      path: os.Path,
      endOffset: Option[Long],
      limit: Int
  ): IO[ArchiveError, EntryPage] =
    ZIO
      .attemptBlocking:
        val regionEnd = endOffset.getOrElse(os.size(path))
        BackwardLineReader.lastLines(
          regionEnd,
          limit,
          tailBlockSize,
          BackwardLineReader.DefaultScanBudgetBytes,
          (offset, length) =>
            ByteRangeReader.readFully(
              offset,
              length,
              (at, count) => os.read.bytes(path, at, count)
            )
        )
      .mapError(toArchiveError)
      .flatMap:
        case Right(tail) =>
          ZIO.succeed(TranscriptPage.fromTail(sessionId, path, tail))
        case Left(_) =>
          ZIO.fail(ArchiveError.CorruptTranscript(sessionId.value))

  private def transcriptStream(path: os.Path): EntryStream =
    reader.stream(path).mapError(toArchiveError)

  private def locateSubAgentTranscript(
      sessionId: SessionId,
      parentToolUseId: String
  ): IO[ArchiveError, Option[os.Path]] =
    for
      _ <- ZIO.fromEither(ArchivePaths.validateId(sessionId.value))
      _ <- ZIO.fromEither(ArchivePaths.validateId(parentToolUseId))
      record <- locate(sessionId)
      located <- record match
        case None      => ZIO.none
        case Some(rec) =>
          index
            .listSubAgents(projectDirOf(rec.root), sessionId.value)
            .mapError(toArchiveError)
            .map(
              _.find(_.toolUseId.contains(parentToolUseId))
                .map(_.transcriptPath)
            )
    yield located

  /** The project directory of a resolved record's root — the vendor tree or the
    * archive mirror, both projects-dir-shaped.
    */
  private def projectDirOf(root: RecordRoot): os.Path =
    root match
      case RecordRoot.Vendor  => ArchivePaths.projectDir(config)
      case RecordRoot.Archive => ArchivePaths.archiveProjectDir(config)

  private def runMirror(record: SessionRecord): MirrorReport =
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
  private def regularFile(path: os.Path): Boolean =
    !os.isLink(path) && os.isFile(path)

  private def sourceFiles(record: SessionRecord): Seq[os.Path] =
    val main = Option.when(regularFile(record.mainTranscript))(
      record.mainTranscript
    )
    main.toSeq ++ walkRegularFiles(record.treeDir)

  private def mirrorFiles(
      record: SessionRecord,
      mirrorDir: os.Path
  ): Seq[os.Path] =
    val main = mirrorDir / s"${record.sessionId.value}.jsonl"
    val tree = mirrorDir / record.sessionId.value
    val mainFiles = if regularFile(main) then Seq(main) else Seq.empty
    mainFiles ++ walkRegularFiles(tree)

  /** Regular files under `root`, excluding symlinks at every level — including
    * `root` itself: `os.walk` dereferences a symlinked root regardless of
    * `followLinks`, and entries behind it then report as regular files.
    */
  private def walkRegularFiles(root: os.Path): Seq[os.Path] =
    if os.exists(root) && !os.isLink(root) then
      os.walk(root).filter(regularFile)
    else Seq.empty

  private def listing(
      files: Seq[os.Path],
      root: os.Path
  ): Map[os.SubPath, Long] =
    files.map(path => path.subRelativeTo(root) -> os.size(path)).toMap

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

  private def toArchiveError(t: Throwable): ArchiveError =
    t match
      case archive: ArchiveError => archive
      case other                 =>
        ArchiveIOError(failureMessage(other), other)

object ZioConversationArchive:

  /** Creates an archive over the given configuration. */
  def apply(config: ArchiveConfig): ZioConversationArchive =
    new ZioConversationArchive(config)

  /** Whether a candidate transcript is confirmed present, confirmed absent, or
    * of undeterminable existence (e.g. its directory is present but unreadable,
    * so every `Files` predicate answered `false`).
    */
  private[log] enum TranscriptPresence:
    case Present, Absent, Indeterminate

  /** The tri-state presence decision from the three `java.nio.file.Files`
    * predicates for one path. Because each predicate reports `false` when it
    * cannot access the path, only their combination separates a confirmed
    * absence (`notExists` or a non-file that `exists`) from the indeterminate
    * case where none is confirmed — the signature of an unreadable parent.
    */
  private[log] def classifyPresence(
      isRegularFile: Boolean,
      notExists: Boolean,
      exists: Boolean
  ): TranscriptPresence =
    if isRegularFile then TranscriptPresence.Present
    else if notExists then TranscriptPresence.Absent
    else if exists then TranscriptPresence.Absent
    else TranscriptPresence.Indeterminate
