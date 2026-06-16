// PURPOSE: Task-based ConversationLogReader that reads and streams .jsonl session log entries
// PURPOSE: Streams entries with ZStream, parsing each line with the shared pure parser

package works.iterative.claude.zio.log

import zio.*
import zio.stream.*
import works.iterative.claude.core.log.ConversationLogReader
import works.iterative.claude.core.log.model.ConversationLogEntry
import works.iterative.claude.core.log.parsing.ConversationLogParser

class ZioConversationLogReader extends ConversationLogReader[Task]:
  type EntryStream = ZStream[Any, Throwable, ConversationLogEntry]

  def readAll(path: os.Path): Task[List[ConversationLogEntry]] =
    stream(path).runCollect.map(_.toList)

  def stream(path: os.Path): ZStream[Any, Throwable, ConversationLogEntry] =
    ZStream
      .fromPath(path.toNIO)
      .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
      .map(ConversationLogParser.parseLogLine)
      .collect { case Some(entry) => entry }

object ZioConversationLogReader:
  def apply(): ZioConversationLogReader = new ZioConversationLogReader()
