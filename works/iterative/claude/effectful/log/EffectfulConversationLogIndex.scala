// PURPOSE: IO-based ConversationLogIndex that discovers and looks up .jsonl session files
// PURPOSE: All operations are deferred and composable within IO programs

package works.iterative.claude.effectful.log

import cats.effect.IO
import fs2.io.file.{Files, Path => Fs2Path}
import works.iterative.claude.core.log.ConversationLogIndex
import works.iterative.claude.core.log.LogFileMetadataBuilder
import works.iterative.claude.core.log.model.LogFileMetadata

class EffectfulConversationLogIndex extends ConversationLogIndex[IO]:

  def listSessions(projectPath: os.Path): IO[Seq[LogFileMetadata]] =
    IO(os.exists(projectPath)).flatMap:
      case false => IO.pure(Seq.empty)
      case true  =>
        val dir = Fs2Path.fromNioPath(projectPath.toNIO)
        Files[IO]
          .list(dir)
          .filter(p => p.fileName.toString.endsWith(".jsonl"))
          .evalMap(p =>
            IO(
              LogFileMetadataBuilder.fromStat(projectPath, os.Path(p.toNioPath))
            )
          )
          .compile
          .toList
          .map(_.toSeq)

  def forSession(
      projectPath: os.Path,
      sessionId: String
  ): IO[Option[LogFileMetadata]] =
    val candidate = projectPath / s"$sessionId.jsonl"
    IO(os.exists(candidate) && os.isFile(candidate)).flatMap:
      case true =>
        IO(LogFileMetadataBuilder.fromStat(projectPath, candidate)).map(Some(_))
      case false => IO.pure(None)

object EffectfulConversationLogIndex:
  def apply(): EffectfulConversationLogIndex =
    new EffectfulConversationLogIndex()
