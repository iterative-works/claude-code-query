// PURPOSE: Abstract contract for reading entries from a Claude Code conversation log file
// PURPOSE: Parameterised by effect type F; the EntryStream type member lets each implementation choose its stream type

package works.iterative.claude.core.log

import works.iterative.claude.core.log.model.ConversationLogEntry

trait ConversationLogReader[F[_]]:
  type EntryStream
  def readAll(path: os.Path): F[List[ConversationLogEntry]]
  def stream(path: os.Path): EntryStream
