package works.iterative.claude.core.parsing

// PURPOSE: Pure JSON parsing utilities for Claude Code CLI stream output
// PURPOSE: Converts CLI JSON responses into typed Message objects without effects

import io.circe.{Json, parser}
import works.iterative.claude.core.model.*

object JsonParser:
  // Pure JSON line parsing - simple parsing without effects
  // None only for blank or non-JSON lines; every parsed JSON becomes a Message
  def parseJsonLine(line: String): Option[Message] =
    if line.trim.isEmpty then None
    else parser.parse(line).toOption.map(parseMessage)

  // Core message parsing - total: dispatches to specific message type parsers,
  // and anything they cannot handle survives verbatim as UnknownMessage
  def parseMessage(json: Json): Message =
    val cursor = json.hcursor
    val messageType = cursor.get[String]("type").getOrElse("")
    val parsed: Option[Message] = messageType match
      case "user"             => parseUserMessage(cursor)
      case "assistant"        => parseAssistantMessage(cursor)
      case "system"           => parseSystemMessage(json, cursor)
      case "result"           => parseResultMessage(cursor)
      case "keep_alive"       => Some(KeepAliveMessage)
      case "stream_event"     => parseStreamEventMessage(json)
      case "control_response" => parseControlResponse(cursor)
      case _                  => None
    parsed.getOrElse(UnknownMessage(messageType, json))

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
      content = contentArray.flatMap(ContentBlockParser.parseContentBlock)
    yield AssistantMessage(
      content = content,
      id = parseMessageId(cursor),
      parentToolUseId = cursor.get[String]("parent_tool_use_id").toOption,
      model = messageJson.hcursor.get[String]("model").toOption
    )

  private def parseMessageId(cursor: io.circe.HCursor): Option[MessageId] =
    cursor.get[String]("uuid").toOption.map(MessageId.apply)

  private def parseSystemMessage(
      json: Json,
      cursor: io.circe.HCursor
  ): Option[SystemMessage] =
    for
      subtype <- cursor.get[String]("subtype").toOption
      jsonObj <- json.asObject
      data = extractSystemMessageData(jsonObj)
    yield SystemMessage(subtype, data)

  private def parseControlResponse(
      cursor: io.circe.HCursor
  ): Option[ControlResponse] =
    val response = cursor.downField("response")
    for
      requestId <- response.get[String]("request_id").toOption
      subtype <- response.get[String]("subtype").toOption
    yield ControlResponse(
      requestId = RequestId(requestId),
      subtype = subtype,
      payload = response.downField("response").focus.getOrElse(Json.Null)
    )

  private def parseStreamEventMessage(json: Json): Option[StreamEventMessage] =
    json.asObject.map { jsonObj =>
      val data = jsonObj.toMap
        .filter { case (key, _) => key != "type" }
        .map { case (key, value) => key -> extractJsonValue(value) }
      StreamEventMessage(data)
    }

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
      usage = parseTokenUsage(cursor)
      result = cursor.get[String]("result").toOption
    yield ResultMessage(
      subtype = subtype,
      durationMs = durationMs,
      durationApiMs = durationApiMs,
      isError = isError,
      numTurns = numTurns,
      sessionId = SessionId(sessionId),
      totalCostUsd = totalCostUsd,
      usage = usage,
      result = result,
      id = parseMessageId(cursor),
      origin = parseResultOrigin(cursor),
      stopReason = cursor.get[String]("stop_reason").toOption,
      terminalReason = cursor.get[String]("terminal_reason").toOption,
      permissionDenials = parsePermissionDenials(cursor),
      apiErrorStatus = cursor.get[String]("api_error_status").toOption,
      timings = ResultTimings(
        ttftMs = cursor.get[Int]("ttft_ms").toOption,
        ttftStreamMs = cursor.get[Int]("ttft_stream_ms").toOption,
        timeToRequestMs = cursor.get[Int]("time_to_request_ms").toOption
      )
    )

  // The origin KEY being absent means the result ends an interactive turn, so
  // absence maps to None; any present origin — even null or an unrecognized
  // shape — stays present, surviving as Other with the raw JSON text as kind
  private def parseResultOrigin(
      cursor: io.circe.HCursor
  ): Option[ResultOrigin] =
    cursor.downField("origin").focus.map { originJson =>
      originJson.hcursor.get[String]("kind") match
        case Right("task-notification") => ResultOrigin.TaskNotification
        case Right(kind)                => ResultOrigin.Other(kind)
        case Left(_) => ResultOrigin.Other(originJson.noSpaces)
    }

  private def parsePermissionDenials(
      cursor: io.circe.HCursor
  ): List[PermissionDenial] =
    cursor
      .get[List[Json]]("permission_denials")
      .toOption
      .getOrElse(Nil)
      .map(PermissionDenial.apply)

  private def parseTokenUsage(
      cursor: io.circe.HCursor
  ): Option[TokenUsage] =
    cursor.get[Json]("usage").toOption.flatMap(TokenUsage.fromJson)

  private def extractJsonValue(json: Json): Any =
    json.fold(
      jsonNull = None,
      jsonBoolean = identity,
      jsonNumber = num => num.toInt.orElse(num.toLong).getOrElse(num.toDouble),
      jsonString = identity,
      jsonArray = _.map(extractJsonValue).toList,
      jsonObject = _.toMap.map { case (k, v) => k -> extractJsonValue(v) }
    )
