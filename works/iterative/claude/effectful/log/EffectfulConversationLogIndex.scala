// PURPOSE: Effectful implementation of ConversationLogIndex using cats-effect IO and fs2.io.file
// PURPOSE: Lists and looks up .jsonl session files under a project directory wrapped in IO

package works.iterative.claude.effectful.log

import cats.effect.IO
import fs2.io.file.{Files, Path => Fs2Path}
import java.time.Instant
import works.iterative.claude.core.log.ConversationLogIndex
import works.iterative.claude.core.log.ProjectPathDecoder
import works.iterative.claude.core.log.model.LogFileMetadata

class EffectfulConversationLogIndex extends ConversationLogIndex[IO]:

  def listSessions(projectPath: os.Path): IO[Seq[LogFileMetadata]] =
    val dir = Fs2Path.fromNioPath(projectPath.toNIO)
    Files[IO]
      .list(dir)
      .filter(p => p.fileName.toString.endsWith(".jsonl"))
      .evalMap(p => metadataFor(projectPath, os.Path(p.toNioPath)))
      .compile
      .toList
      .map(_.toSeq)

  def forSession(
      projectPath: os.Path,
      sessionId: String
  ): IO[Option[LogFileMetadata]] =
    val candidate = projectPath / s"$sessionId.jsonl"
    IO(os.exists(candidate) && os.isFile(candidate)).flatMap:
      case true  => metadataFor(projectPath, candidate).map(Some(_))
      case false => IO.pure(None)

  private def metadataFor(
      projectPath: os.Path,
      path: os.Path
  ): IO[LogFileMetadata] =
    IO:
      val stat = os.stat(path)
      val sessionId = path.last.stripSuffix(".jsonl")
      val cwd = ProjectPathDecoder.decode(projectPath.last)
      LogFileMetadata(
        path = path,
        sessionId = sessionId,
        summary = None,
        lastModified = Instant.ofEpochMilli(stat.mtime.toMillis),
        fileSize = stat.size,
        cwd = if cwd.isEmpty then None else Some(cwd),
        gitBranch = None,
        createdAt = None
      )

object EffectfulConversationLogIndex:
  def apply(): EffectfulConversationLogIndex =
    new EffectfulConversationLogIndex()
