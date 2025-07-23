// PURPOSE: Direct-style JSON parsing for Claude Code CLI stream output
// PURPOSE: Converts CLI JSON responses into typed Message objects without IO effects
package works.iterative.claude.direct.internal.parsing

import works.iterative.claude.core.{JsonParsingError}
import works.iterative.claude.core.model.Message
import works.iterative.claude.core.parsing.{JsonParser as CoreJsonParser}
import io.circe.parser

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
      // Use circe parser directly to catch JSON parsing errors
      parser.parse(line) match
        case Right(json) =>
          // Use core parser for message parsing
          val message = CoreJsonParser.parseMessage(json)
          Right(message)
        case Left(parseError) =>
          // JSON parsing failed, return JsonParsingError
          Left(JsonParsingError(line, lineNumber, parseError))
