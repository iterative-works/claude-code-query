// PURPOSE: Direct-style JSON parsing for Claude Code CLI stream output
// PURPOSE: Converts CLI JSON responses into typed Message objects without IO effects
package works.iterative.claude.direct.internal.parsing

import works.iterative.claude.core.{JsonParsingError}
import works.iterative.claude.core.model.Message
import works.iterative.claude.core.parsing.{JsonParser as CoreJsonParser}

object JsonParser:

  /** Parse JSON line with context, returning Either for error handling without
    * IO effects
    */
  def parseJsonLineWithContext(
      line: String,
      lineNumber: Int
  ): Either[JsonParsingError, Option[Message]] =
    if line.trim.isEmpty then Right(None)
    else
      try
        val message = CoreJsonParser.parseJsonLine(line)
        Right(message)
      catch
        case ex: Throwable =>
          Left(JsonParsingError(line, lineNumber, ex))
