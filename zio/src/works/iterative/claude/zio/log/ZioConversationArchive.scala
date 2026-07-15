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
import works.iterative.claude.core.log.ConversationArchive
import works.iterative.claude.core.log.MirrorAction
import works.iterative.claude.core.log.MirrorPlanner
import works.iterative.claude.core.log.model.ConversationLogEntry
import works.iterative.claude.core.log.model.EntryPage
import works.iterative.claude.core.log.model.MirrorReport
import works.iterative.claude.core.log.model.PageToken
import works.iterative.claude.core.log.model.RecordRoot
import works.iterative.claude.core.log.model.SessionRecord
import works.iterative.claude.core.log.parsing.ConversationLogParser
import works.iterative.claude.core.model.SessionId

class ZioConversationArchive private (config: ArchiveConfig)
    extends ConversationArchive[[A] =>> IO[ArchiveError, A]]:

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
    ZStream.unwrap:
      forSession(sessionId).map:
        case Some(record) => transcriptStream(record.mainTranscript)
        case None         => ZStream.fail(SessionNotFound(sessionId.value))

  def lastEntries(
      sessionId: SessionId,
      limit: Int
  ): IO[ArchiveError, EntryPage] =
    locate(sessionId).flatMap:
      case Some(record) =>
        readPage(sessionId, record.mainTranscript, None, limit)
      case None => ZIO.fail(SessionNotFound(sessionId.value))

  def entriesBefore(
      token: PageToken,
      limit: Int
  ): IO[ArchiveError, EntryPage] =
    for
      current <- resolveMainTranscript(token.sessionId)
      page <- current match
        case Some(path) if path == token.source =>
          readPage(token.sessionId, token.source, Some(token.offset), limit)
        case Some(_) => ZIO.fail(PageSourceMoved(token.sessionId.value))
        case None    => ZIO.fail(SessionNotFound(token.sessionId.value))
    yield page

  def subagentEntries(
      sessionId: SessionId,
      parentToolUseId: String
  ): EntryStream =
    ZStream.unwrap:
      locateSubAgentTranscript(sessionId, parentToolUseId).map:
        case Some(transcript) => transcriptStream(transcript)
        case None             =>
          ZStream.fail(SubAgentNotFound(sessionId.value, parentToolUseId))

  def mirror(sessionId: SessionId): IO[ArchiveError, MirrorReport] =
    locate(sessionId).flatMap:
      case Some(record) if record.root == RecordRoot.Vendor =>
        ZIO.attemptBlocking(runMirror(record)).mapError(toArchiveError)
      // Vendor tree absent, session served from the archive: custody is
      // one-directional, so the mirror is never written back from nothing.
      case Some(_) => ZIO.succeed(MirrorReport.empty)
      case None    => ZIO.fail(SessionNotFound(sessionId.value))

  /** Resolves a session's record vendor-first, then from the archive mirror,
    * keeping the first whose main transcript exists.
    */
  private def locate(
      sessionId: SessionId
  ): IO[ArchiveError, Option[SessionRecord]] =
    for
      candidates <- ZIO.fromEither(ArchivePaths.candidates(config, sessionId))
      found <- ZIO
        .attemptBlocking(candidates.find(c => isTranscript(c.mainTranscript)))
        .mapError(toArchiveError)
    yield found

  /** The path of a session's currently-resolved main transcript, if any. */
  private def resolveMainTranscript(
      sessionId: SessionId
  ): IO[ArchiveError, Option[os.Path]] =
    locate(sessionId).map(_.map(_.mainTranscript))

  private def isTranscript(path: os.Path): Boolean =
    os.exists(path) && os.isFile(path)

  private def readPage(
      sessionId: SessionId,
      path: os.Path,
      endOffset: Option[Long],
      limit: Int
  ): IO[ArchiveError, EntryPage] =
    ZIO
      .attemptBlocking:
        val regionEnd = endOffset.getOrElse(os.size(path))
        val tail = BackwardLineReader.lastLines(
          regionEnd,
          limit,
          tailBlockSize,
          (offset, length) => os.read.bytes(path, offset, length)
        )
        val entries = tail.lines.flatMap(ConversationLogParser.parseLogLine)
        val older = tail.older.map(offset => PageToken(sessionId, path, offset))
        EntryPage(entries, older)
      .mapError(toArchiveError)

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
