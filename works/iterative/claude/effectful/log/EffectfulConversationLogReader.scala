// PURPOSE: IO-based ConversationLogReader that reads and streams .jsonl session log entries
// PURPOSE: All operations are deferred and composable within IO programs

package works.iterative.claude.effectful.log

import cats.effect.IO
import fs2.Stream
import fs2.io.file.{Files, Path => Fs2Path}
import works.iterative.claude.core.log.ConversationLogReader
import works.iterative.claude.core.log.model.ConversationLogEntry
import works.iterative.claude.core.log.parsing.ConversationLogParser

class EffectfulConversationLogReader extends ConversationLogReader[IO]:
  type EntryStream = Stream[IO, ConversationLogEntry]

  def readAll(path: os.Path): IO[List[ConversationLogEntry]] =
    stream(path).compile.toList

  def stream(path: os.Path): Stream[IO, ConversationLogEntry] =
    Files[IO]
      .readAll(Fs2Path.fromNioPath(path.toNIO))
      .through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
      .map(ConversationLogParser.parseLogLine)
      .collect { case Some(entry) => entry }

object EffectfulConversationLogReader:
  def apply(): EffectfulConversationLogReader =
    new EffectfulConversationLogReader()
