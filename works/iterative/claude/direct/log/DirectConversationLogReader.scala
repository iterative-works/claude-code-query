// PURPOSE: Direct-style implementation of ConversationLogReader using os-lib and Ox Flow
// PURPOSE: Reads and streams ConversationLogEntry values from .jsonl files without effect wrappers

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
      .fromIterable(os.read.lines(path))
      .map(ConversationLogParser.parseLogLine)
      .collect { case Some(entry) => entry }

object DirectConversationLogReader:
  def apply(): DirectConversationLogReader = new DirectConversationLogReader()
