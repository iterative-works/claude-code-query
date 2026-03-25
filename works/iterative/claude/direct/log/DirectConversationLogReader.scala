// PURPOSE: Synchronous ConversationLogReader that reads and streams .jsonl session log entries
// PURPOSE: Returns plain values and lazy Flow; no effect type required by callers

package works.iterative.claude.direct.log

import ox.flow.Flow
import works.iterative.claude.core.log.ConversationLogReader
import works.iterative.claude.core.log.model.ConversationLogEntry
import works.iterative.claude.core.log.parsing.ConversationLogParser

class DirectConversationLogReader extends ConversationLogReader[[A] =>> A]:
  type EntryStream = Flow[ConversationLogEntry]

  def readAll(path: os.Path): List[ConversationLogEntry] =
    os.read
      .lines(path)
      .flatMap(ConversationLogParser.parseLogLine)
      .toList

  def stream(path: os.Path): Flow[ConversationLogEntry] =
    Flow
      .usingEmit: emit =>
        val source = scala.io.Source.fromFile(path.toIO)
        try
          source
            .getLines()
            .foreach: line =>
              ConversationLogParser.parseLogLine(line).foreach(emit.apply)
        finally source.close()

object DirectConversationLogReader:
  def apply(): DirectConversationLogReader = new DirectConversationLogReader()
