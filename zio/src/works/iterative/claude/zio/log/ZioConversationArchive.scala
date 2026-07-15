// PURPOSE: Task-based ConversationArchive that locates, reads, and mirrors a session's record tree
// PURPOSE: Interprets the pure mirror plan with file IO, surfacing failures on a typed ArchiveError channel

package works.iterative.claude.zio.log

import zio.*
import zio.stream.*
import works.iterative.claude.core.log.ArchiveConfig
import works.iterative.claude.core.log.ArchiveError
import works.iterative.claude.core.log.ArchiveError.ArchiveIOError
import works.iterative.claude.core.log.ArchiveError.SessionNotFound
import works.iterative.claude.core.log.ArchiveError.SubAgentNotFound
import works.iterative.claude.core.log.ArchivePaths
import works.iterative.claude.core.log.ConversationArchive
import works.iterative.claude.core.log.MirrorAction
import works.iterative.claude.core.log.MirrorPlanner
import works.iterative.claude.core.log.model.ConversationLogEntry
import works.iterative.claude.core.log.model.MirrorReport
import works.iterative.claude.core.log.model.SessionRecord
import works.iterative.claude.core.model.SessionId

class ZioConversationArchive private (config: ArchiveConfig)
    extends ConversationArchive[[A] =>> IO[ArchiveError, A]]:

  type EntryStream = ZStream[Any, ArchiveError, ConversationLogEntry]

  private val reader = ZioConversationLogReader()

  // Sub-agent discovery reuses the index's tested join. Only `listSubAgents`
  // is called, and it takes the project path explicitly, so the override and
  // home passed here are irrelevant.
  private val index = ZioConversationLogIndex.make(None, os.home)

  def forSession(
      sessionId: SessionId
  ): IO[ArchiveError, Option[SessionRecord]] =
    for
      main <- ZIO.fromEither(ArchivePaths.mainTranscript(config, sessionId))
      tree <- ZIO.fromEither(ArchivePaths.treeDir(config, sessionId))
      record <- ZIO
        .attemptBlocking:
          Option.when(os.exists(main) && os.isFile(main)):
            SessionRecord(sessionId, main, tree)
        .mapError(toArchiveError)
    yield record

  def entries(sessionId: SessionId): EntryStream =
    ZStream.unwrap:
      forSession(sessionId).map:
        case Some(record) => transcriptStream(record.mainTranscript)
        case None         => ZStream.fail(SessionNotFound(sessionId.value))

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
    for
      located <- forSession(sessionId)
      record <- ZIO
        .fromOption(located)
        .orElseFail(SessionNotFound(sessionId.value))
      report <- ZIO.attemptBlocking(runMirror(record)).mapError(toArchiveError)
    yield report

  private def transcriptStream(path: os.Path): EntryStream =
    reader.stream(path).mapError(toArchiveError)

  private def locateSubAgentTranscript(
      sessionId: SessionId,
      parentToolUseId: String
  ): IO[ArchiveError, Option[os.Path]] =
    for
      _ <- ZIO.fromEither(ArchivePaths.validateId(sessionId.value))
      _ <- ZIO.fromEither(ArchivePaths.validateId(parentToolUseId))
      located <- index
        .listSubAgents(ArchivePaths.projectDir(config), sessionId.value)
        .mapError(toArchiveError)
        .map(
          _.find(_.toolUseId.contains(parentToolUseId)).map(_.transcriptPath)
        )
    yield located

  private def runMirror(record: SessionRecord): MirrorReport =
    val projectDir = ArchivePaths.projectDir(config)
    val source = listing(sourceFiles(record), projectDir)
    val mirror = listing(mirrorFiles(record), config.archiveDir)
    val plan = MirrorPlanner.plan(source, mirror)
    plan.actions.foldLeft(MirrorReport.empty): (report, action) =>
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
            copyWhole(projectDir / rel, config.archiveDir / rel)
            report.copy(copied = report.copied :+ rel)
        case MirrorAction.Recopy(rel) =>
          guarded(rel):
            copyWhole(projectDir / rel, config.archiveDir / rel)
            report.copy(refreshed = report.refreshed :+ rel)
        case MirrorAction.Extend(rel, _) =>
          guarded(rel):
            if extendInPlace(projectDir / rel, config.archiveDir / rel) then
              report.copy(extended = report.extended :+ rel)
            else
              copyWhole(projectDir / rel, config.archiveDir / rel)
              report.copy(refreshed = report.refreshed :+ rel)

  private def sourceFiles(record: SessionRecord): Seq[os.Path] =
    val tree =
      if os.exists(record.treeDir) then
        os.walk(record.treeDir).filter(os.isFile)
      else Seq.empty
    record.mainTranscript +: tree

  private def mirrorFiles(record: SessionRecord): Seq[os.Path] =
    val main = config.archiveDir / s"${record.sessionId.value}.jsonl"
    val tree = config.archiveDir / record.sessionId.value
    val mainFiles = if os.exists(main) then Seq(main) else Seq.empty
    val treeFiles =
      if os.exists(tree) then os.walk(tree).filter(os.isFile) else Seq.empty
    mainFiles ++ treeFiles

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
