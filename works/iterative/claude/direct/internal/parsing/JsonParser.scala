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

  /** Parse JSON line with context and logging, returning Either for error
    * handling without IO effects
    */
  def parseJsonLineWithContextWithLogging(
      line: String,
      lineNumber: Int
  )(using logger: Logger): Either[JsonParsingError, Option[Message]] =
    if line.trim.isEmpty then
      logger.debug(s"Skipping empty line $lineNumber")
      Right(None)
    else
      logger.debug(s"Parsing JSON line $lineNumber")
      // Use circe parser directly to catch JSON parsing errors
      parser.parse(line) match
        case Right(json) =>
          // Use core parser for message parsing
          val message = CoreJsonParser.parseMessage(json)
          message match
            case Some(msg) =>
              val messageType = extractMessageType(msg)
              logger.debug(
                s"Successfully parsed message of type $messageType at line $lineNumber"
              )
            case None =>
              logger.debug(
                s"Parsed JSON but no message created at line $lineNumber"
              )
          Right(message)
        case Left(parseError) =>
          // JSON parsing failed, return JsonParsingError
          logger.error(
            s"Failed to parse JSON at line $lineNumber: ${parseError.getMessage}"
          )
          Left(JsonParsingError(line, lineNumber, parseError))

  /** Extract message type for logging purposes */
  private def extractMessageType(message: Message): String =
    message match
      case _: UserMessage      => "user"
      case _: AssistantMessage => "assistant"
      case _: SystemMessage    => "system"
      case _: ResultMessage    => "result"
