// PURPOSE: Direct-style JSON parsing for Claude Code CLI stream output
// PURPOSE: Converts CLI JSON responses into typed Message objects without IO effects
package works.iterative.claude.direct.internal.parsing

import works.iterative.claude.core.{JsonParsingError}
import works.iterative.claude.core.model.{
  Message,
  UserMessage,
  AssistantMessage,
  SystemMessage,
  ResultMessage
}
import works.iterative.claude.core.parsing.{JsonParser as CoreJsonParser}
import works.iterative.claude.direct.internal.cli.Logger
import io.circe.parser

object JsonParser:

  /** Parse JSON line with context, returning Either for error handling without
    * IO effects
    */
  def parseJsonLineWithContext(
      line: String,
      lineNumber: Int
  ): Either[JsonParsingError, Option[Message]] =
    handleEmptyLine(line) match
      case Some(result) => result
      case None         => parseNonEmptyJsonLine(line, lineNumber)

  /** Parse JSON line with context and logging, returning Either for error
    * handling without IO effects
    */
  def parseJsonLineWithContextWithLogging(
      line: String,
      lineNumber: Int
  )(using logger: Logger): Either[JsonParsingError, Option[Message]] =
    handleEmptyLineWithLogging(line, lineNumber) match
      case Some(result) => result
      case None         => parseNonEmptyJsonLineWithLogging(line, lineNumber)

  /** Handle empty line case, returning None if line should be processed */
  private def handleEmptyLine(
      line: String
  ): Option[Either[JsonParsingError, Option[Message]]] =
    if line.trim.isEmpty then Some(Right(None)) else None

  /** Handle empty line case with logging, returning None if line should be
    * processed
    */
  private def handleEmptyLineWithLogging(
      line: String,
      lineNumber: Int
  )(using logger: Logger): Option[Either[JsonParsingError, Option[Message]]] =
    if line.trim.isEmpty then
      logger.debug(s"Skipping empty line $lineNumber")
      Some(Right(None))
    else None

  /** Parse non-empty JSON line without logging */
  private def parseNonEmptyJsonLine(
      line: String,
      lineNumber: Int
  ): Either[JsonParsingError, Option[Message]] =
    parseJsonString(line) match
      case Right(json)      => Right(CoreJsonParser.parseMessage(json))
      case Left(parseError) =>
        Left(JsonParsingError(line, lineNumber, parseError))

  /** Parse non-empty JSON line with logging */
  private def parseNonEmptyJsonLineWithLogging(
      line: String,
      lineNumber: Int
  )(using logger: Logger): Either[JsonParsingError, Option[Message]] =
    logger.debug(s"Parsing JSON line $lineNumber")
    parseJsonString(line) match
      case Right(json) =>
        val message = CoreJsonParser.parseMessage(json)
        logParsingResult(message, lineNumber)
        Right(message)
      case Left(parseError) =>
        logParsingError(parseError, lineNumber)
        Left(JsonParsingError(line, lineNumber, parseError))

  /** Parse JSON string using circe parser */
  private def parseJsonString(line: String) = parser.parse(line)

  /** Log the result of message parsing */
  private def logParsingResult(message: Option[Message], lineNumber: Int)(using
      logger: Logger
  ): Unit =
    message match
      case Some(msg) =>
        val messageType = extractMessageType(msg)
        logger.debug(
          s"Successfully parsed message of type $messageType at line $lineNumber"
        )
      case None =>
        logger.debug(s"Parsed JSON but no message created at line $lineNumber")

  /** Log parsing error */
  private def logParsingError(
      parseError: io.circe.ParsingFailure,
      lineNumber: Int
  )(using logger: Logger): Unit =
    logger.error(
      s"Failed to parse JSON at line $lineNumber: ${parseError.getMessage}"
    )

  /** Extract message type for logging purposes */
  private def extractMessageType(message: Message): String =
    message match
      case _: UserMessage      => "user"
      case _: AssistantMessage => "assistant"
      case _: SystemMessage    => "system"
      case _: ResultMessage    => "result"
