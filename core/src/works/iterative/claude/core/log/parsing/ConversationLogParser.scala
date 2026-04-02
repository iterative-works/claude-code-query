package works.iterative.claude.core.log.parsing

// PURPOSE: Pure parsing of JSONL conversation log lines into typed ConversationLogEntry values
// PURPOSE: Dispatches to per-type payload parsers and reuses ContentBlockParser for content blocks

import io.circe.{HCursor, Json, parser}
import java.time.Instant
import works.iterative.claude.core.log.model.*
import works.iterative.claude.core.model.*
import works.iterative.claude.core.parsing.ContentBlockParser

object ConversationLogParser:

  private val EnvelopeKeys: Set[String] =
    Set(
      "type",
      "uuid",
      "parentUuid",
      "timestamp",
      "sessionId",
      "isSidechain",
      "cwd",
      "version"
    )

  def parseLogLine(line: String): Option[ConversationLogEntry] =
    if line.trim.isEmpty then None
    else
      parser.parse(line) match
        case Right(json) => parseLogEntry(json)
        case Left(_)     => None

  def parseLogEntry(json: Json): Option[ConversationLogEntry] =
    val cursor = json.hcursor
    for
      uuid <- cursor.get[String]("uuid").toOption
      sessionId <- cursor.get[String]("sessionId").toOption
      entryType <- cursor.get[String]("type").toOption
      parentUuid = cursor.get[String]("parentUuid").toOption
      timestamp = cursor.get[String]("timestamp").toOption.flatMap(parseInstant)
      isSidechain = cursor.get[Boolean]("isSidechain").toOption.getOrElse(false)
      cwd = cursor.get[String]("cwd").toOption
      version = cursor.get[String]("version").toOption
      payload <- parsePayload(entryType, cursor, json)
    yield ConversationLogEntry(
      uuid,
      parentUuid,
      timestamp,
      sessionId,
      isSidechain,
      cwd,
      version,
      payload
    )

  private def parseInstant(s: String): Option[Instant] =
    try Some(Instant.parse(s))
    catch case _: java.time.format.DateTimeParseException => None

  private def parsePayload(
      entryType: String,
      cursor: HCursor,
      json: Json
  ): Option[LogEntryPayload] =
    entryType match
      case "human"                 => parseUserPayload(cursor)
      case "assistant"             => parseAssistantPayload(cursor)
      case "system"                => parseSystemPayload(cursor, json)
      case "progress"              => Some(parseProgressPayload(cursor, json))
      case "queue_operation"       => parseQueueOperationPayload(cursor)
      case "file_history_snapshot" =>
        Some(parseDataOnlyPayload(json, FileHistorySnapshotLogEntry.apply))
      case "last_prompt" =>
        Some(parseDataOnlyPayload(json, LastPromptLogEntry.apply))
      case _ => Some(RawLogEntry(entryType, json))

  private def parseContentBlocks(cursor: HCursor): List[ContentBlock] =
    cursor
      .get[List[Json]]("content")
      .toOption
      .map(_.flatMap(ContentBlockParser.parseContentBlock))
      .orElse(
        cursor.get[String]("content").toOption.map(s => List(TextBlock(s)))
      )
      .getOrElse(List.empty)

  private def parseUserPayload(cursor: HCursor): Option[LogEntryPayload] =
    cursor
      .get[Json]("message")
      .toOption
      .map: messageJson =>
        UserLogEntry(parseContentBlocks(messageJson.hcursor))

  private def parseAssistantPayload(cursor: HCursor): Option[LogEntryPayload] =
    cursor
      .get[Json]("message")
      .toOption
      .map: messageJson =>
        val messageCursor = messageJson.hcursor
        val content = parseContentBlocks(messageCursor)
        val model = messageCursor.get[String]("model").toOption
        val usage =
          messageCursor.get[Json]("usage").toOption.flatMap(parseTokenUsage)
        val requestId = cursor.get[String]("requestId").toOption
        AssistantLogEntry(content, model, usage, requestId)

  private def parseTokenUsage(json: Json): Option[TokenUsage] =
    val cursor = json.hcursor
    for
      inputTokens <- cursor.get[Int]("input_tokens").toOption
      outputTokens <- cursor.get[Int]("output_tokens").toOption
    yield TokenUsage(
      inputTokens = inputTokens,
      outputTokens = outputTokens,
      cacheCreationInputTokens =
        cursor.get[Int]("cache_creation_input_tokens").toOption,
      cacheReadInputTokens =
        cursor.get[Int]("cache_read_input_tokens").toOption,
      serviceTier = cursor.get[String]("service_tier").toOption
    )

  private def parseSystemPayload(
      cursor: HCursor,
      json: Json
  ): Option[LogEntryPayload] =
    cursor
      .get[String]("subtype")
      .toOption
      .map: subtype =>
        val data = extractDataMap(json, EnvelopeKeys + "subtype")
        SystemLogEntry(subtype, data)

  private def parseProgressPayload(
      cursor: HCursor,
      json: Json
  ): LogEntryPayload =
    val parentToolUseId = cursor.get[String]("parentToolUseId").toOption
    val data = extractDataMap(json, EnvelopeKeys + "parentToolUseId")
    ProgressLogEntry(data, parentToolUseId)

  private def parseQueueOperationPayload(
      cursor: HCursor
  ): Option[LogEntryPayload] =
    cursor
      .get[String]("operation")
      .toOption
      .map: operation =>
        val content = cursor.get[String]("content").toOption
        QueueOperationLogEntry(operation, content)

  // Generic parser for entry types that just need a data map (excluding envelope keys)
  private def parseDataOnlyPayload(
      json: Json,
      wrap: Map[String, Any] => LogEntryPayload
  ): LogEntryPayload =
    val data = extractDataMap(json, EnvelopeKeys)
    wrap(data)

  private def extractDataMap(
      json: Json,
      excludeKeys: Set[String]
  ): Map[String, Any] =
    json.asObject
      .map(_.toMap.filter { case (key, _) => !excludeKeys.contains(key) }.map {
        case (key, value) => key -> extractJsonValue(value)
      })
      .getOrElse(Map.empty)

  private def extractJsonValue(json: Json): Any =
    json.fold(
      jsonNull = null,
      jsonBoolean = identity,
      jsonNumber = num => num.toInt.orElse(num.toLong).getOrElse(num.toDouble),
      jsonString = identity,
      jsonArray = _.map(extractJsonValue).toList,
      jsonObject = _.toMap.map { case (k, v) => k -> extractJsonValue(v) }
    )
