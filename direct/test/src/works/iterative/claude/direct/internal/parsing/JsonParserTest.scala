// PURPOSE: Tests for direct-style JSON parsing functionality
// PURPOSE: Verifies JSON parsing without IO effects, returning Either results
package works.iterative.claude.direct.internal.parsing

import works.iterative.claude.core.{JsonParsingError}
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.parsing.JsonParser
import works.iterative.claude.direct.Logger
import works.iterative.claude.direct.internal.testing.TestConstants
import io.circe.Json
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

class JsonParserTest extends munit.FunSuite with munit.ScalaCheckSuite:

  // Mock Logger for testing
  class MockLogger extends Logger:
    var debugMessages: List[String] = List.empty
    var infoMessages: List[String] = List.empty
    var warnMessages: List[String] = List.empty
    var errorMessages: List[String] = List.empty

    def debug(msg: => String): Unit = debugMessages = msg :: debugMessages
    def info(msg: => String): Unit = infoMessages = msg :: infoMessages
    def warn(msg: => String): Unit = warnMessages = msg :: warnMessages
    def error(msg: => String): Unit = errorMessages = msg :: errorMessages
    def error(msg: => String, exception: Throwable): Unit = errorMessages =
      s"$msg: ${exception.getMessage}" :: errorMessages

  // JSON serialization utilities for property testing
  object JsonSerializationUtils:

    def serializeMessage(message: Message): String = message match
      case UserMessage(content) =>
        s"""{"type":"user","content":${escapeJsonString(content)}}"""

      case assistant: AssistantMessage =>
        val contentJson =
          assistant.content.map(serializeContentBlock).mkString("[", ",", "]")
        val messageFields = List(
          Some(s""""content":$contentJson"""),
          assistant.model.map(m => s""""model":${escapeJsonString(m)}""")
        ).flatten
        val topLevelFields = List(
          Some(s""""type":"assistant""""),
          Some(s""""message":{${messageFields.mkString(",")}}"""),
          assistant.id.map(id =>
            s""""uuid":${escapeJsonString(id.value)}"""
          ),
          assistant.parentToolUseId.map(p =>
            s""""parent_tool_use_id":${escapeJsonString(p)}"""
          )
        ).flatten
        s"""{${topLevelFields.mkString(",")}}"""

      case SystemMessage(subtype, data) =>
        val dataJson = data
          .map { case (key, value) =>
            s""""$key":${serializeJsonValue(value)}"""
          }
          .mkString(",")
        s"""{"type":"system","subtype":${escapeJsonString(subtype)}${
            if dataJson.nonEmpty then "," + dataJson else ""
          }}"""

      case rm: ResultMessage =>
        val optionalFields = List(
          rm.totalCostUsd.map(cost => s""""total_cost_usd":$cost"""),
          rm.usage.map(u => s""""usage":${serializeTokenUsage(u)}"""),
          rm.result.map(r => s""""result":${escapeJsonString(r)}"""),
          rm.id.map(id => s""""uuid":${escapeJsonString(id.value)}"""),
          rm.stopReason.map(s => s""""stop_reason":${escapeJsonString(s)}"""),
          rm.terminalReason.map(t =>
            s""""terminal_reason":${escapeJsonString(t)}"""
          ),
          rm.apiErrorStatus.map(a =>
            s""""api_error_status":${escapeJsonString(a)}"""
          ),
          rm.timings.ttftMs.map(t => s""""ttft_ms":$t"""),
          rm.timings.ttftStreamMs.map(t => s""""ttft_stream_ms":$t"""),
          rm.timings.timeToRequestMs.map(t => s""""time_to_request_ms":$t"""),
          rm.origin.map(o => s""""origin":${serializeOrigin(o)}"""),
          Option.when(rm.permissionDenials.nonEmpty)(
            s""""permission_denials":${rm.permissionDenials
                .map(_.json.noSpaces)
                .mkString("[", ",", "]")}"""
          )
        ).flatten
        val allFields = List(
          s""""type":"result"""",
          s""""subtype":${escapeJsonString(rm.subtype)}""",
          s""""duration_ms":${rm.durationMs}""",
          s""""duration_api_ms":${rm.durationApiMs}""",
          s""""is_error":${rm.isError}""",
          s""""num_turns":${rm.numTurns}""",
          s""""session_id":${escapeJsonString(rm.sessionId.value)}"""
        ) ++ optionalFields
        s"""{${allFields.mkString(",")}}"""

      case KeepAliveMessage =>
        s"""{"type":"keep_alive"}"""

      case StreamEventMessage(data) =>
        val dataJson = data
          .map { case (key, value) =>
            s""""$key":${serializeJsonValue(value)}"""
          }
          .mkString(",")
        s"""{"type":"stream_event"${
            if dataJson.nonEmpty then "," + dataJson else ""
          }}"""

      case UnknownMessage(_, json) =>
        json.noSpaces

      case ControlResponse(requestId, subtype, payload) =>
        val payloadField =
          if payload.isNull then ""
          else s""","response":${payload.noSpaces}"""
        s"""{"type":"control_response","response":{"subtype":${escapeJsonString(
            subtype
          )},"request_id":${escapeJsonString(requestId.value)}$payloadField}}"""

    private def serializeOrigin(origin: ResultOrigin): String = origin match
      case ResultOrigin.TaskNotification => """{"kind":"task-notification"}"""
      case ResultOrigin.Other(kind) => s"""{"kind":${escapeJsonString(kind)}}"""

    private def serializeTokenUsage(usage: TokenUsage): String =
      val fields = List(
        Some(s""""input_tokens":${usage.inputTokens}"""),
        Some(s""""output_tokens":${usage.outputTokens}"""),
        usage.cacheCreationInputTokens.map(c =>
          s""""cache_creation_input_tokens":$c"""
        ),
        usage.cacheReadInputTokens.map(c =>
          s""""cache_read_input_tokens":$c"""
        ),
        usage.serviceTier.map(t => s""""service_tier":${escapeJsonString(t)}""")
      ).flatten
      s"""{${fields.mkString(",")}}"""

    private def serializeContentBlock(block: ContentBlock): String = block match
      case TextBlock(text) =>
        s"""{"type":"text","text":${escapeJsonString(text)}}"""

      case ToolUseBlock(id, name, input) =>
        s"""{"type":"tool_use","id":${escapeJsonString(
            id
          )},"name":${escapeJsonString(name)},"input":{}}"""

      case ToolResultBlock(toolUseId, content, isError) =>
        val contentField = content
          .map(c => s""""content":${escapeJsonString(c)}""")
          .getOrElse("")
        val errorField = isError.map(e => s""""is_error":$e""").getOrElse("")
        val fields = List(
          s""""tool_use_id":${escapeJsonString(toolUseId)}""",
          contentField,
          errorField
        ).filter(_.nonEmpty)
        s"""{"type":"tool_result",${fields.mkString(",")}}"""

      case ThinkingBlock(thinking, signature) =>
        s"""{"type":"thinking","thinking":${escapeJsonString(
            thinking
          )},"signature":${escapeJsonString(signature)}}"""

      case RedactedThinkingBlock(data) =>
        s"""{"type":"redacted_thinking","data":${escapeJsonString(data)}}"""

    private def escapeJsonString(str: String): String =
      "\"" + str
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""

    private def serializeJsonValue(value: Any): String = value match
      case null       => "null"
      case b: Boolean => b.toString
      case n: Number  => n.toString
      case s: String  => escapeJsonString(s)
      case _          => escapeJsonString(value.toString)

  // ScalaCheck generators for property testing
  object MessageGenerators:

    // Generator for safe text content (avoiding control characters that might break JSON)
    val safeTextGen: Gen[String] = Gen.oneOf(
      Gen.const(""), // Empty string
      Gen.alphaNumStr, // Simple alphanumeric
      Gen.const("Hello, World!"), // Basic text
      Gen.const("Special chars: @#$%^&*()"), // Special characters
      Gen.const("Unicode: émojis 🚀 中文"), // Unicode content
      Gen.const("Multi\nline\ntext"), // Multiline content
      Gen.const("Quotes and \"escapes\" test"), // Quote escaping
      Gen
        .choose(1, TestConstants.TestDataSizes.MEDIUM_DATA_SIZE)
        .map("x" * _) // Variable length content
    )

    // Generator for system message data
    val systemDataGen: Gen[Map[String, Any]] = Gen.oneOf(
      Gen.const(Map.empty[String, Any]),
      Gen.const(Map("context_user_id" -> "user_123")),
      Gen.const(Map("key1" -> "value1", "key2" -> 42, "key3" -> true)),
      Gen.const(
        Map(
          "nested" -> "data",
          "count" -> TestConstants.TestDataSizes.SMALL_DATA_SIZE
        )
      )
    )

    // Generator for content blocks
    val contentBlockGen: Gen[ContentBlock] = Gen.oneOf(
      safeTextGen.map(TextBlock.apply),
      for {
        id <- Gen.alphaNumStr.suchThat(_.nonEmpty)
        name <- Gen.alphaNumStr.suchThat(_.nonEmpty)
      } yield ToolUseBlock(id, name, Map.empty),
      for {
        toolUseId <- Gen.alphaNumStr.suchThat(_.nonEmpty)
        content <- Gen.option(safeTextGen)
        isError <- Gen.option(Gen.oneOf(true, false))
      } yield ToolResultBlock(toolUseId, content, isError)
    )

    // Generator for UserMessage
    val userMessageGen: Gen[UserMessage] = safeTextGen.map(UserMessage.apply)

    // Generator for AssistantMessage
    val assistantMessageGen: Gen[AssistantMessage] = for {
      content <- Gen.listOfN(
        Gen.choose(1, 3).sample.getOrElse(1),
        contentBlockGen
      )
      id <- Gen.option(Gen.uuid.map(u => MessageId(u.toString)))
      parentToolUseId <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))
      model <- Gen.option(Gen.const("claude-sonnet-4-5-20250929"))
    } yield AssistantMessage(content, id, parentToolUseId, model)

    // Generator for SystemMessage
    val systemMessageGen: Gen[SystemMessage] = for {
      subtype <- Gen.oneOf("user_context", "session_start", "config_update")
      data <- systemDataGen
    } yield SystemMessage(subtype, data)

    // Generator for TokenUsage
    val tokenUsageGen: Gen[TokenUsage] = for {
      inputTokens <- Gen.choose(0L, 5000000000L)
      outputTokens <- Gen.choose(0L, 5000000000L)
      cacheCreation <- Gen.option(Gen.choose(0L, 5000000000L))
      cacheRead <- Gen.option(Gen.choose(0L, 5000000000L))
      serviceTier <- Gen.option(Gen.oneOf("standard", "priority"))
    } yield TokenUsage(
      inputTokens,
      outputTokens,
      cacheCreation,
      cacheRead,
      serviceTier
    )

    // Generator for PermissionDenial wire entries (raw denial JSON objects)
    val permissionDenialGen: Gen[PermissionDenial] = for {
      toolName <- Gen.alphaNumStr.suchThat(_.nonEmpty)
      toolInput <- Gen.oneOf(
        Json.fromString("deny"),
        Json.fromInt(3),
        Json.obj("command" -> Json.fromString("rm -rf /")),
        Json.obj("nested" -> Json.obj("path" -> Json.fromString("/etc")))
      )
    } yield PermissionDenial(
      Json.obj(
        "tool_name" -> Json.fromString(toolName),
        "tool_input" -> toolInput
      )
    )

    // Generator for ResultTimings
    val resultTimingsGen: Gen[ResultTimings] = for {
      ttftMs <- Gen.option(Gen.choose(0, 60000))
      ttftStreamMs <- Gen.option(Gen.choose(0, 60000))
      timeToRequestMs <- Gen.option(Gen.choose(0, 60000))
    } yield ResultTimings(ttftMs, ttftStreamMs, timeToRequestMs)

    // Generator for ResultMessage
    val resultMessageGen: Gen[ResultMessage] = for {
      subtype <- Gen.oneOf(
        "conversation_result",
        "error_result",
        "timeout_result"
      )
      durationMs <- Gen.choose(
        TestConstants.TestDataSizes.SMALL_DATA_SIZE,
        TestConstants.TestDataSizes.LARGE_DATA_SIZE
      )
      durationApiMs <- Gen.choose(
        50,
        TestConstants.TestParameters.MAX_THINKING_TOKENS_LARGE
      )
      isError <- Gen.oneOf(true, false)
      numTurns <- Gen.choose(1, 10)
      sessionId <- Gen.alphaNumStr.suchThat(_.nonEmpty)
      totalCostUsd <- Gen.option(Gen.choose(0.001, 1.0))
      usage <- Gen.option(tokenUsageGen)
      result <- Gen.option(safeTextGen)
      id <- Gen.option(Gen.uuid.map(u => MessageId(u.toString)))
      origin <- Gen.option(
        Gen.oneOf(
          Gen.const(ResultOrigin.TaskNotification),
          Gen.alphaNumStr.suchThat(_.nonEmpty).map(ResultOrigin.Other.apply)
        )
      )
      stopReason <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))
      terminalReason <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))
      permissionDenials <- Gen
        .choose(0, 3)
        .flatMap(Gen.listOfN(_, permissionDenialGen))
      apiErrorStatus <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))
      timings <- resultTimingsGen
    } yield ResultMessage(
      subtype,
      durationMs,
      durationApiMs,
      isError,
      numTurns,
      SessionId(sessionId),
      totalCostUsd,
      usage,
      result,
      id,
      origin,
      stopReason,
      terminalReason,
      permissionDenials,
      apiErrorStatus,
      timings
    )

    // Generator for KeepAliveMessage
    val keepAliveGen: Gen[KeepAliveMessage.type] =
      Gen.const(KeepAliveMessage)

    // Generator for StreamEventMessage
    val streamEventGen: Gen[StreamEventMessage] = systemDataGen.map(
      StreamEventMessage.apply
    )

    // Generator for UnknownMessage: a wire type the parser has no case for,
    // carrying raw json whose "type" key matches messageType
    val unknownMessageGen: Gen[UnknownMessage] = for {
      messageType <- Gen.oneOf(
        "rate_limit_event",
        "hook_event",
        "never_seen_before"
      )
      payload <- Gen.oneOf(
        Gen.const(Json.Null),
        safeTextGen.map(Json.fromString),
        Gen.choose(0, 100000).map(Json.fromInt),
        safeTextGen.map(s => Json.obj("nested" -> Json.fromString(s)))
      )
    } yield UnknownMessage(
      messageType,
      Json.obj(
        "type" -> Json.fromString(messageType),
        "payload" -> payload
      )
    )

    // Generator for ControlResponse
    val controlResponseGen: Gen[ControlResponse] = for {
      requestId <- Gen.alphaNumStr.suchThat(_.nonEmpty)
      subtype <- Gen.oneOf("success", "error")
      payload <- Gen.oneOf(
        Json.Null,
        Json.obj("still_queued" -> Json.arr()),
        Json.obj("message" -> Json.fromString("ok"))
      )
    } yield ControlResponse(RequestId(requestId), subtype, payload)

    // Generator for any Message type
    val messageGen: Gen[Message] = Gen.oneOf(
      userMessageGen,
      assistantMessageGen,
      systemMessageGen,
      resultMessageGen,
      keepAliveGen,
      streamEventGen,
      unknownMessageGen,
      controlResponseGen
    )

    // Implicit Arbitrary instances
    given Arbitrary[Message] = Arbitrary(messageGen)
    given Arbitrary[UserMessage] = Arbitrary(userMessageGen)
    given Arbitrary[AssistantMessage] = Arbitrary(assistantMessageGen)
    given Arbitrary[SystemMessage] = Arbitrary(systemMessageGen)
    given Arbitrary[ResultMessage] = Arbitrary(resultMessageGen)
    given Arbitrary[KeepAliveMessage.type] = Arbitrary(keepAliveGen)
    given Arbitrary[StreamEventMessage] = Arbitrary(streamEventGen)
    given Arbitrary[UnknownMessage] = Arbitrary(unknownMessageGen)
    given Arbitrary[ControlResponse] = Arbitrary(controlResponseGen)

  test("should parse valid JSON messages with line context") {
    // Setup: Valid JSON message strings from CLI output
    val validSystemMessage =
      """{"type":"system","subtype":"user_context","context_user_id":"user_01JHD7Y82DBTRS66XHKZ1CKZH4"}"""
    val validUserMessage = """{"type":"user","content":"Hello Claude!"}"""
    val validAssistantMessage =
      """{"type":"assistant","message":{"content":[{"type":"text","text":"Hello! How can I help you today?"}]}}"""
    val validResultMessage =
      """{"type":"result","subtype":"conversation_result","duration_ms":1234,"duration_api_ms":567,"is_error":false,"num_turns":1,"session_id":"session_123"}"""

    // Execute: Parse valid JSON messages with line context
    val systemResult =
      JsonParser.parseJsonLineWithContext(validSystemMessage, 1)
    val userResult = JsonParser.parseJsonLineWithContext(validUserMessage, 2)
    val assistantResult =
      JsonParser.parseJsonLineWithContext(validAssistantMessage, 3)
    val resultResult =
      JsonParser.parseJsonLineWithContext(validResultMessage, 4)

    // Verify: Should return Right with parsed Message objects
    systemResult match
      case Right(Some(SystemMessage(subtype, data))) =>
        assertEquals(subtype, "user_context")
        assert(data.contains("context_user_id"))
      case other =>
        fail(s"Expected Right(Some(SystemMessage(...))) but got: $other")

    userResult match
      case Right(Some(UserMessage(content))) =>
        assertEquals(content, "Hello Claude!")
      case other =>
        fail(s"Expected Right(Some(UserMessage(...))) but got: $other")

    assistantResult match
      case Right(Some(assistant: AssistantMessage)) =>
        assertEquals(assistant.content.length, 1)
        assistant.content.head match
          case TextBlock(text) =>
            assertEquals(text, "Hello! How can I help you today?")
          case other => fail(s"Expected TextBlock but got: $other")
      case other =>
        fail(s"Expected Right(Some(AssistantMessage(...))) but got: $other")

    resultResult match
      case Right(Some(rm: ResultMessage)) =>
        assertEquals(rm.subtype, "conversation_result")
        assertEquals(rm.durationMs, 1234)
        assertEquals(rm.durationApiMs, 567)
        assertEquals(rm.isError, false)
        assertEquals(rm.numTurns, 1)
        assertEquals(rm.sessionId.value, "session_123")
      case other =>
        fail(s"Expected Right(Some(ResultMessage(...))) but got: $other")
  }

  test("should handle empty lines gracefully during parsing") {
    // Setup: Empty and whitespace-only strings
    val emptyLine = ""
    val whitespaceLine = "   \t  \n  "
    val justSpaces = "     "

    // Execute: Parse empty lines with line context
    val emptyResult = JsonParser.parseJsonLineWithContext(emptyLine, 1)
    val whitespaceResult =
      JsonParser.parseJsonLineWithContext(whitespaceLine, 2)
    val spacesResult = JsonParser.parseJsonLineWithContext(justSpaces, 3)

    // Verify: Should return Right(None) for empty lines
    assertEquals(emptyResult, Right(None))
    assertEquals(whitespaceResult, Right(None))
    assertEquals(spacesResult, Right(None))
  }

  test("should handle malformed JSON gracefully with appropriate errors") {
    // Setup: Invalid JSON strings with context
    val malformedJson1 = """{"type":"system","missing_quote:true}"""
    val malformedJson2 = """{"type":"user","content":"Hello" extra_text}"""
    val malformedJson3 = """{"type":"assistant",}"""
    val notJsonAtAll = """This is not JSON at all!"""

    // Execute: Parse malformed JSON with line context
    val result1 = JsonParser.parseJsonLineWithContext(malformedJson1, 5)
    val result2 = JsonParser.parseJsonLineWithContext(malformedJson2, 10)
    val result3 = JsonParser.parseJsonLineWithContext(malformedJson3, 15)
    val result4 = JsonParser.parseJsonLineWithContext(notJsonAtAll, 20)

    // Verify: Should return Left(JsonParsingError) with line context
    result1 match
      case Left(JsonParsingError(line, lineNumber, cause)) =>
        assertEquals(line, malformedJson1)
        assertEquals(lineNumber, 5)
        assert(cause != null)
      case other =>
        fail(s"Expected Left(JsonParsingError(...)) but got: $other")

    result2 match
      case Left(JsonParsingError(line, lineNumber, cause)) =>
        assertEquals(line, malformedJson2)
        assertEquals(lineNumber, 10)
        assert(cause != null)
      case other =>
        fail(s"Expected Left(JsonParsingError(...)) but got: $other")

    result3 match
      case Left(JsonParsingError(line, lineNumber, cause)) =>
        assertEquals(line, malformedJson3)
        assertEquals(lineNumber, 15)
        assert(cause != null)
      case other =>
        fail(s"Expected Left(JsonParsingError(...)) but got: $other")

    result4 match
      case Left(JsonParsingError(line, lineNumber, cause)) =>
        assertEquals(line, notJsonAtAll)
        assertEquals(lineNumber, 20)
        assert(cause != null)
      case other =>
        fail(s"Expected Left(JsonParsingError(...)) but got: $other")
  }

  test("should log parsing attempts and results appropriately") {
    // Setup: Mock logger capturing debug messages
    given MockLogger = MockLogger()

    val validJson = """{"type":"user","content":"Hello Claude!"}"""
    val malformedJson = """{"type":"user","invalid"}"""
    val emptyLine = ""

    // Execute: Parse different types of input with logging
    val validResult =
      JsonParser.parseJsonLineWithContextWithLogging(validJson, 1)
    val malformedResult =
      JsonParser.parseJsonLineWithContextWithLogging(malformedJson, 2)
    val emptyResult =
      JsonParser.parseJsonLineWithContextWithLogging(emptyLine, 3)

    // Verify: Results are correct
    assert(validResult.isRight)
    assert(malformedResult.isLeft)
    assert(emptyResult.isRight)

    // Verify: Appropriate debug and error log messages
    val logger = summon[MockLogger]

    // Should log parsing attempts
    assert(
      logger.debugMessages.exists(_.contains("Parsing JSON line 1"))
    )
    assert(
      logger.debugMessages.exists(_.contains("Parsing JSON line 2"))
    )
    assert(
      logger.debugMessages.exists(_.contains("Skipping empty line 3"))
    )

    // Should log successful parsing
    assert(
      logger.debugMessages.exists(
        _.contains("Successfully parsed message of type user")
      )
    )

    // Should log parsing errors
    assert(
      logger.errorMessages.exists(_.contains("Failed to parse JSON at line 2"))
    )
  }

  // Property-Based Tests - JSON Parsing Idempotency

  property(
    "should maintain idempotency when re-parsing serialized messages"
  ) {
    import MessageGenerators.given
    import JsonSerializationUtils.*

    forAll { (originalMessage: Message) =>
      // Serialize the original message to JSON
      val jsonString = serializeMessage(originalMessage)

      // Parse the JSON back to a Message object
      val parseResult = JsonParser.parseJsonLineWithContext(jsonString, 1)

      // Verify successful parsing and idempotency
      parseResult match
        case Right(Some(parsedMessage)) =>
          assertEquals(
            parsedMessage,
            originalMessage,
            s"Round-trip parsing failed for message type ${originalMessage.getClass.getSimpleName}.\n" +
              s"Original: $originalMessage\n" +
              s"JSON: $jsonString\n" +
              s"Parsed: $parsedMessage"
          )
        case Right(None) =>
          fail(s"Expected parsed message but got None for JSON: $jsonString")
        case Left(error) =>
          fail(
            s"Expected successful parsing but got error: $error for JSON: $jsonString"
          )
    }
  }

  property("should maintain idempotency for UserMessage parsing specifically") {
    import MessageGenerators.*
    import JsonSerializationUtils.*

    forAll(userMessageGen) { originalMessage =>
      val jsonString = serializeMessage(originalMessage)
      val parseResult = JsonParser.parseJsonLineWithContext(jsonString, 1)

      parseResult match
        case Right(Some(UserMessage(content))) =>
          assertEquals(UserMessage(content), originalMessage)
        case other =>
          fail(
            s"Expected Right(Some(UserMessage(...))) but got: $other for JSON: $jsonString"
          )
    }
  }

  property(
    "should maintain idempotency for AssistantMessage parsing specifically"
  ) {
    import MessageGenerators.*
    import JsonSerializationUtils.*

    forAll(assistantMessageGen) { originalMessage =>
      val jsonString = serializeMessage(originalMessage)
      val parseResult = JsonParser.parseJsonLineWithContext(jsonString, 1)

      parseResult match
        case Right(Some(assistant: AssistantMessage)) =>
          assertEquals(assistant, originalMessage)
        case other =>
          fail(
            s"Expected Right(Some(AssistantMessage(...))) but got: $other for JSON: $jsonString"
          )
    }
  }

  property(
    "should maintain idempotency for SystemMessage parsing specifically"
  ) {
    import MessageGenerators.*
    import JsonSerializationUtils.*

    forAll(systemMessageGen) { originalMessage =>
      val jsonString = serializeMessage(originalMessage)
      val parseResult = JsonParser.parseJsonLineWithContext(jsonString, 1)

      parseResult match
        case Right(Some(SystemMessage(subtype, data))) =>
          assertEquals(SystemMessage(subtype, data), originalMessage)
        case other =>
          fail(
            s"Expected Right(Some(SystemMessage(...))) but got: $other for JSON: $jsonString"
          )
    }
  }

  property(
    "should maintain idempotency for ResultMessage parsing specifically"
  ) {
    import MessageGenerators.*
    import JsonSerializationUtils.*

    forAll(resultMessageGen) { originalMessage =>
      val jsonString = serializeMessage(originalMessage)
      val parseResult = JsonParser.parseJsonLineWithContext(jsonString, 1)

      parseResult match
        case Right(Some(resultMessage: ResultMessage)) =>
          assertEquals(resultMessage, originalMessage)
        case other =>
          fail(
            s"Expected Right(Some(ResultMessage(...))) but got: $other for JSON: $jsonString"
          )
    }
  }

  property(
    "should maintain idempotency for KeepAliveMessage parsing specifically"
  ) {
    import MessageGenerators.*
    import JsonSerializationUtils.*

    forAll(keepAliveGen) { originalMessage =>
      val jsonString = serializeMessage(originalMessage)
      val parseResult = JsonParser.parseJsonLineWithContext(jsonString, 1)

      parseResult match
        case Right(Some(KeepAliveMessage)) => () // success
        case other                         =>
          fail(
            s"Expected Right(Some(KeepAliveMessage)) but got: $other for JSON: $jsonString"
          )
    }
  }

  property(
    "should maintain idempotency for StreamEventMessage parsing specifically"
  ) {
    import MessageGenerators.*
    import JsonSerializationUtils.*

    forAll(streamEventGen) { originalMessage =>
      val jsonString = serializeMessage(originalMessage)
      val parseResult = JsonParser.parseJsonLineWithContext(jsonString, 1)

      parseResult match
        case Right(Some(streamEvent: StreamEventMessage)) =>
          assertEquals(streamEvent, originalMessage)
        case other =>
          fail(
            s"Expected Right(Some(StreamEventMessage(...))) but got: $other for JSON: $jsonString"
          )
    }
  }

  property(
    "should maintain idempotency for UnknownMessage parsing specifically"
  ) {
    import MessageGenerators.*
    import JsonSerializationUtils.*

    forAll(unknownMessageGen) { originalMessage =>
      val jsonString = serializeMessage(originalMessage)
      val parseResult = JsonParser.parseJsonLineWithContext(jsonString, 1)

      parseResult match
        case Right(Some(unknown: UnknownMessage)) =>
          assertEquals(unknown, originalMessage)
        case other =>
          fail(
            s"Expected Right(Some(UnknownMessage(...))) but got: $other for JSON: $jsonString"
          )
    }
  }

  property(
    "should maintain idempotency for ControlResponse parsing specifically"
  ) {
    import MessageGenerators.*
    import JsonSerializationUtils.*

    forAll(controlResponseGen) { originalMessage =>
      val jsonString = serializeMessage(originalMessage)
      val parseResult = JsonParser.parseJsonLineWithContext(jsonString, 1)

      parseResult match
        case Right(Some(controlResponse: ControlResponse)) =>
          assertEquals(controlResponse, originalMessage)
        case other =>
          fail(
            s"Expected Right(Some(ControlResponse(...))) but got: $other for JSON: $jsonString"
          )
    }
  }

  test("should handle edge cases with empty content and special characters") {
    import JsonSerializationUtils.*

    // Test edge cases that the generators might not cover adequately
    val edgeCases = List(
      UserMessage(""), // Empty content
      UserMessage("Line1\nLine2\nLine3"), // Multiline
      UserMessage("Quotes: \"hello\" and 'world'"), // Mixed quotes
      UserMessage("Backslashes: \\ and forward / slashes"), // Escape characters
      UserMessage("Tab\tcharacter"), // Tab character
      AssistantMessage(List(TextBlock(""))), // Empty text block
      AssistantMessage(
        List(TextBlock("Special: \n\r\t\\\""))
      ), // All escape chars
      SystemMessage("test", Map.empty), // Empty data
      SystemMessage("test", Map("key" -> "")), // Empty value
      ResultMessage(
        "test",
        0,
        0,
        false,
        0,
        SessionId(""),
        None,
        None,
        Some("")
      ) // Minimal result with empty result
    )

    edgeCases.foreach { originalMessage =>
      val jsonString = serializeMessage(originalMessage)
      val parseResult = JsonParser.parseJsonLineWithContext(jsonString, 1)

      parseResult match
        case Right(Some(parsedMessage)) =>
          assertEquals(
            parsedMessage,
            originalMessage,
            s"Edge case failed for: $originalMessage\nJSON: $jsonString"
          )
        case other =>
          fail(
            s"Expected successful parsing for edge case: $originalMessage\nJSON: $jsonString\nGot: $other"
          )
    }
  }

  test("should handle large content blocks correctly during parsing") {
    import JsonSerializationUtils.*

    // Test with large content blocks
    val largeText = "x" * TestConstants.TestDataSizes.LARGE_DATA_SIZE
    val largeUserMessage = UserMessage(largeText)
    val largeAssistantMessage = AssistantMessage(
      List(
        TextBlock(largeText),
        TextBlock("normal text"),
        TextBlock(largeText + " with suffix")
      )
    )

    List(largeUserMessage, largeAssistantMessage).foreach { originalMessage =>
      val jsonString = serializeMessage(originalMessage)
      val parseResult = JsonParser.parseJsonLineWithContext(jsonString, 1)

      parseResult match
        case Right(Some(parsedMessage)) =>
          assertEquals(parsedMessage, originalMessage)
        case other =>
          fail(
            s"Large content test failed for: ${originalMessage.getClass.getSimpleName}"
          )
    }
  }
