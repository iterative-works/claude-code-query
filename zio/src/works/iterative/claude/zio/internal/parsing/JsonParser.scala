// PURPOSE: ZIO JSON parsing wrapper using core pure parsing logic
// PURPOSE: Adds ZIO logging and a typed JsonParsingError channel to pure parsing functions

package works.iterative.claude.zio.internal.parsing

import zio.*
import works.iterative.claude.core.model.*
import works.iterative.claude.core.parsing.{JsonParser => CoreJsonParser}
import works.iterative.claude.core.JsonParsingError

object JsonParser:
  // High-level JSON line parsing - entry point for simple parsing
  def parseJsonLine(line: String): UIO[Option[Message]] =
    ZIO.succeed(CoreJsonParser.parseJsonLine(line))

  // High-level JSON line parsing with line context, logging, and a typed error
  def parseJsonLineWithContext(
      line: String,
      lineNumber: Int
  ): IO[JsonParsingError, Option[Message]] =
    if line.trim.isEmpty then ZIO.none
    else
      io.circe.parser.parse(line) match
        case Right(json) =>
          ZIO.logDebug(
            s"Successfully parsed JSON message at line $lineNumber"
          ) *>
            ZIO.succeed(CoreJsonParser.parseMessage(json))
        case Left(error) =>
          ZIO.logError(
            s"JSON parsing error at line $lineNumber: ${error.getMessage}"
          ) *> ZIO.fail(JsonParsingError(line, lineNumber, error))
