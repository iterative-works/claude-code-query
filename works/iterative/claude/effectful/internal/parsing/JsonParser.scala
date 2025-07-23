package works.iterative.claude.effectful.internal.parsing

// PURPOSE: Effectful JSON parsing wrapper using core pure parsing logic
// PURPOSE: Adds IO effects, logging, and error handling to pure parsing functions

import cats.effect.IO
import works.iterative.claude.core.model.*
import works.iterative.claude.core.parsing.{JsonParser => CoreJsonParser}
import works.iterative.claude.core.JsonParsingError
import org.typelevel.log4cats.Logger

object JsonParser:
  // High-level JSON line parsing - entry point for simple parsing
  def parseJsonLine(line: String): IO[Option[Message]] =
    IO.pure(CoreJsonParser.parseJsonLine(line))

  // High-level JSON line parsing with error context and logging
  def parseJsonLineWithContext(
      line: String,
      lineNumber: Int,
      logger: Logger[IO]
  ): IO[Either[JsonParsingError, Option[Message]]] =
    if line.trim.isEmpty then IO.pure(Right(None))
    else
      io.circe.parser.parse(line) match
        case Right(json) =>
          logger.debug(
            s"Successfully parsed JSON message at line $lineNumber"
          ) *>
            IO.pure(Right(CoreJsonParser.parseMessage(json)))
        case Left(error) =>
          logger.error(
            s"JSON parsing error at line $lineNumber: ${error.getMessage}"
          ) *>
            IO.pure(Left(JsonParsingError(line, lineNumber, error)))
