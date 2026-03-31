package works.iterative.claude.core.parsing

// PURPOSE: Pure JSON parsing utilities for Claude Code CLI stream output
// PURPOSE: Converts CLI JSON responses into typed Message objects without effects

import io.circe.{Json, parser}
import works.iterative.claude.core.model.*

object JsonParser:
  // Pure JSON line parsing - simple parsing without effects
  def parseJsonLine(line: String): Option[Message] =
    if line.trim.isEmpty then None
    else
      parser.parse(line) match
        case Right(json) => parseMessage(json)
        case Left(_)     => None

  // Core message parsing - dispatches to specific message type parsers
  def parseMessage(json: Json): Option[Message] =
    val cursor = json.hcursor
    cursor
      .get[String]("type")
      .toOption
      .flatMap:
        case "user"      => parseUserMessage(cursor)
        case "assistant" => parseAssistantMessage(cursor)
        case "system"    => parseSystemMessage(json, cursor)
        case "result"    => parseResultMessage(cursor)
        case _           => None

  // Message type parsers - handle specific message formats
  private def parseUserMessage(cursor: io.circe.HCursor): Option[UserMessage] =
    cursor.get[String]("content").toOption.map(UserMessage.apply)

  private def parseAssistantMessage(
      cursor: io.circe.HCursor
  ): Option[AssistantMessage] =
    for
      messageJson <- cursor.get[Json]("message").toOption
      contentArray <- messageJson.hcursor
        .get[List[Json]]("content")
        .toOption
      content = contentArray.flatMap(parseContentBlock)
    yield AssistantMessage(content)

  // Content block parsing - handles different content types within messages
  private def parseContentBlock(json: Json): Option[ContentBlock] =
    val cursor = json.hcursor
    cursor
      .get[String]("type")
      .toOption
      .flatMap:
        case "text" =>
          cursor.get[String]("text").toOption.map(TextBlock.apply)
        case "tool_use" =>
          for
            id <- cursor.get[String]("id").toOption
            name <- cursor.get[String]("name").toOption
            input = Map.empty[String, Any] // Simplified for now
          yield ToolUseBlock(id, name, input)
        case "tool_result" =>
          for
            toolUseId <- cursor.get[String]("tool_use_id").toOption
            content = cursor.get[String]("content").toOption
            isError = cursor.get[Boolean]("is_error").toOption
          yield ToolResultBlock(toolUseId, content, isError)
        case _ => None

  private def parseSystemMessage(
      json: Json,
      cursor: io.circe.HCursor
  ): Option[SystemMessage] =
    for
      subtype <- cursor.get[String]("subtype").toOption
      jsonObj <- json.asObject
      data = extractSystemMessageData(jsonObj)
    yield SystemMessage(subtype, data)

  // Data extraction utilities - low-level JSON value extraction
  private def extractSystemMessageData(
      jsonObj: io.circe.JsonObject
  ): Map[String, Any] =
    jsonObj.toMap
      .filter { case (key, _) =>
        key != "type" && key != "subtype"
      }
      .map { case (key, value) =>
        key -> extractJsonValue(value)
      }

  private def parseResultMessage(
      cursor: io.circe.HCursor
  ): Option[ResultMessage] =
    for
      subtype <- cursor.get[String]("subtype").toOption
      durationMs <- cursor.get[Int]("duration_ms").toOption
      durationApiMs <- cursor.get[Int]("duration_api_ms").toOption
      isError <- cursor.get[Boolean]("is_error").toOption
      numTurns <- cursor.get[Int]("num_turns").toOption
      sessionId <- cursor.get[String]("session_id").toOption
      totalCostUsd = cursor.get[Double]("total_cost_usd").toOption
      usage = extractUsageData(cursor)
      result = cursor.get[String]("result").toOption
    yield ResultMessage(
      subtype,
      durationMs,
      durationApiMs,
      isError,
      numTurns,
      sessionId,
      totalCostUsd,
      usage,
      result
    )

  private def extractUsageData(
      cursor: io.circe.HCursor
  ): Option[Map[String, Any]] =
    cursor
      .get[Json]("usage")
      .toOption
      .map(_ => Map.empty[String, Any]) // Simplified for now

  private def extractJsonValue(json: Json): Any =
    json.fold(
      jsonNull = null,
      jsonBoolean = identity,
      jsonNumber = num => num.toInt.orElse(num.toLong).getOrElse(num.toDouble),
      jsonString = identity,
      jsonArray = _.map(extractJsonValue).toList,
      jsonObject = _.toMap.map { case (k, v) => k -> extractJsonValue(v) }
    )
