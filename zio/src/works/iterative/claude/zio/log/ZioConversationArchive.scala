// PURPOSE: Task-based ConversationArchive that locates, reads, and mirrors a session's record tree
// PURPOSE: Interprets the pure mirror plan with file IO, surfacing failures on a typed ArchiveError channel

package works.iterative.claude.zio.log

import zio.*
import zio.stream.*
import works.iterative.claude.core.log.ArchiveConfig
import works.iterative.claude.core.log.ArchiveError
import works.iterative.claude.core.log.ArchiveIOError
import works.iterative.claude.core.log.ArchivePaths
import works.iterative.claude.core.log.ConversationArchive
import works.iterative.claude.core.log.MirrorAction
import works.iterative.claude.core.log.MirrorPlanner
import works.iterative.claude.core.log.SessionNotFound
import works.iterative.claude.core.log.SubAgentNotFound
import works.iterative.claude.core.log.model.ConversationLogEntry
import works.iterative.claude.core.log.model.MirrorReport
import works.iterative.claude.core.log.model.SessionRecord
import works.iterative.claude.core.log.parsing.SubAgentMetadataParser

class ZioConversationArchive private (config: ArchiveConfig)
    extends ConversationArchive[[A] =>> IO[ArchiveError, A]]:

  type EntryStream = ZStream[Any, ArchiveError, ConversationLogEntry]

  private val reader = ZioConversationLogReader()

  def forSession(sessionId: String): IO[ArchiveError, Option[SessionRecord]] =
    ZIO
      .attemptBlocking:
        val main = ArchivePaths.mainTranscript(config, sessionId)
        Option.when(os.exists(main) && os.isFile(main)):
          SessionRecord(
            sessionId,
            main,
            ArchivePaths.treeDir(config, sessionId)
          )
      .mapError(toArchiveError)

  def entries(sessionId: String): EntryStream =
    ZStream.unwrap:
      forSession(sessionId).map:
        case Some(record) => transcriptStream(record.mainTranscript)
        case None         => ZStream.fail(SessionNotFound(sessionId))

  def subagentEntries(
      sessionId: String,
      parentToolUseId: String
  ): EntryStream =
    ZStream.unwrap:
      locateSubAgentTranscript(sessionId, parentToolUseId).map:
        case Some(transcript) => transcriptStream(transcript)
        case None => ZStream.fail(SubAgentNotFound(sessionId, parentToolUseId))

  def mirror(sessionId: String): IO[ArchiveError, MirrorReport] =
    for
      located <- forSession(sessionId)
      record <- ZIO
        .fromOption(located)
        .orElseFail(SessionNotFound(sessionId))
      report <- ZIO.attemptBlocking(runMirror(record)).mapError(toArchiveError)
    yield report

  private def transcriptStream(path: os.Path): EntryStream =
    reader.stream(path).mapError(toArchiveError)

  private def locateSubAgentTranscript(
      sessionId: String,
      parentToolUseId: String
  ): IO[ArchiveError, Option[os.Path]] =
    ZIO
      .attemptBlocking:
        val subagentsDir = ArchivePaths.treeDir(config, sessionId) / "subagents"
        if !(os.exists(subagentsDir) && os.isDir(subagentsDir)) then None
        else
          os.list(subagentsDir)
            .filter: path =>
              val name = path.last
              os.isFile(path) && name.startsWith("agent-") && name.endsWith(
                ".meta.json"
              )
            .flatMap: metaPath =>
              val transcript =
                metaPath / os.up / s"${metaPath.last.stripSuffix(".meta.json")}.jsonl"
              io.circe.parser
                .parse(os.read(metaPath))
                .toOption
                .flatMap(SubAgentMetadataParser.parse(_, transcript))
            .find(_.toolUseId.contains(parentToolUseId))
            .map(_.transcriptPath)
      .mapError(toArchiveError)

  private def runMirror(record: SessionRecord): MirrorReport =
    val projectDir = ArchivePaths.projectDir(config)
    val source = listing(sourceFiles(record), projectDir)
    val mirror = listing(mirrorFiles(record), config.archiveDir)
    val plan = MirrorPlanner.plan(source, mirror)
    plan.actions.foldLeft(MirrorReport(Nil, Nil, Nil, Nil)): (report, action) =>
      action match
        case MirrorAction.Skip(rel) =>
          report.copy(skipped = report.skipped :+ rel)
        case MirrorAction.Copy(rel) =>
          copyWhole(projectDir / rel, config.archiveDir / rel)
          report.copy(copied = report.copied :+ rel)
        case MirrorAction.Recopy(rel) =>
          copyWhole(projectDir / rel, config.archiveDir / rel)
          report.copy(refreshed = report.refreshed :+ rel)
        case MirrorAction.Extend(rel, _) =>
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
    val main = config.archiveDir / s"${record.sessionId}.jsonl"
    val tree = config.archiveDir / record.sessionId
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

  private def toArchiveError(t: Throwable): ArchiveError =
    t match
      case archive: ArchiveError => archive
      case other                 =>
        ArchiveIOError(Option(other.getMessage).getOrElse(""), other)

object ZioConversationArchive:

  /** Creates an archive over the given configuration. */
  def apply(config: ArchiveConfig): ZioConversationArchive =
    new ZioConversationArchive(config)

  /** Test seam matching [[ZioConversationLogIndex.make]]. */
  def make(config: ArchiveConfig): ZioConversationArchive =
    new ZioConversationArchive(config)
