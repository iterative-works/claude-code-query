// PURPOSE: Tests for direct-style JSON parsing functionality
// PURPOSE: Verifies JSON parsing without IO effects, returning Either results
package works.iterative.claude.direct.internal.parsing

import works.iterative.claude.core.{JsonParsingError}
import works.iterative.claude.core.model.*
import works.iterative.claude.direct.internal.parsing.JsonParser
import works.iterative.claude.direct.internal.cli.Logger
import works.iterative.claude.direct.internal.testing.TestConstants
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

class JsonParserTest extends munit.FunSuite with munit.ScalaCheckSuite:

  // Mock Logger for testing
  class MockLogger extends Logger:
    var debugMessages: List[String] = List.empty
    var infoMessages: List[String] = List.empty
    var warnMessages: List[String] = List.empty
    var errorMessages: List[String] = List.empty

    def debug(msg: String): Unit = debugMessages = msg :: debugMessages
    def info(msg: String): Unit = infoMessages = msg :: infoMessages
    def warn(msg: String): Unit = warnMessages = msg :: warnMessages
    def error(msg: String): Unit = errorMessages = msg :: errorMessages

  // JSON serialization utilities for property testing
  object JsonSerializationUtils:

    def serializeMessage(message: Message): String = message match
      case UserMessage(content) =>
        s"""{"type":"user","content":${escapeJsonString(content)}}"""

      case AssistantMessage(content) =>
        val contentJson =
          content.map(serializeContentBlock).mkString("[", ",", "]")
        s"""{"type":"assistant","message":{"content":$contentJson}}"""

      case SystemMessage(subtype, data) =>
        val dataJson = data
          .map { case (key, value) =>
            s""""$key":${serializeJsonValue(value)}"""
          }
          .mkString(",")
        s"""{"type":"system","subtype":${escapeJsonString(subtype)}${
            if dataJson.nonEmpty then "," + dataJson else ""
          }}"""

      case ResultMessage(
            subtype,
            durationMs,
            durationApiMs,
            isError,
            numTurns,
            sessionId,
            totalCostUsd,
            usage,
            result
          ) =>
        val optionalFields = List(
          totalCostUsd.map(cost => s""""total_cost_usd":$cost"""),
          usage.map(u => s""""usage":{}"""), // Simplified usage serialization
          result.map(r => s""""result":${escapeJsonString(r)}""")
        ).flatten
        val allFields = List(
          s""""type":"result"""",
          s""""subtype":${escapeJsonString(subtype)}""",
          s""""duration_ms":$durationMs""",
          s""""duration_api_ms":$durationApiMs""",
          s""""is_error":$isError""",
          s""""num_turns":$numTurns""",
          s""""session_id":${escapeJsonString(sessionId)}"""
        ) ++ optionalFields
        s"""{${allFields.mkString(",")}}"""

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
      Gen.choose(1, TestConstants.TestDataSizes.MEDIUM_DATA_SIZE).map("x" * _) // Variable length content
    )

    // Generator for system message data
    val systemDataGen: Gen[Map[String, Any]] = Gen.oneOf(
      Gen.const(Map.empty[String, Any]),
      Gen.const(Map("context_user_id" -> "user_123")),
      Gen.const(Map("key1" -> "value1", "key2" -> 42, "key3" -> true)),
      Gen.const(Map("nested" -> "data", "count" -> TestConstants.TestDataSizes.SMALL_DATA_SIZE))
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
    val assistantMessageGen: Gen[AssistantMessage] =
      Gen
        .listOfN(Gen.choose(1, 3).sample.getOrElse(1), contentBlockGen)
        .map(AssistantMessage.apply)

    // Generator for SystemMessage
    val systemMessageGen: Gen[SystemMessage] = for {
      subtype <- Gen.oneOf("user_context", "session_start", "config_update")
      data <- systemDataGen
    } yield SystemMessage(subtype, data)

    // Generator for ResultMessage
    val resultMessageGen: Gen[ResultMessage] = for {
      subtype <- Gen.oneOf(
        "conversation_result",
        "error_result",
        "timeout_result"
      )
      durationMs <- Gen.choose(TestConstants.TestDataSizes.SMALL_DATA_SIZE, TestConstants.TestDataSizes.LARGE_DATA_SIZE)
      durationApiMs <- Gen.choose(50, TestConstants.TestParameters.MAX_THINKING_TOKENS_LARGE)
      isError <- Gen.oneOf(true, false)
      numTurns <- Gen.choose(1, 10)
      sessionId <- Gen.alphaNumStr.suchThat(_.nonEmpty)
      totalCostUsd <- Gen.option(Gen.choose(0.001, 1.0))
      usage <- Gen.option(Gen.const(Map.empty[String, Any]))
      result <- Gen.option(safeTextGen)
    } yield ResultMessage(
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

    // Generator for any Message type
    val messageGen: Gen[Message] = Gen.oneOf(
      userMessageGen,
      assistantMessageGen,
      systemMessageGen,
      resultMessageGen
    )

    // Implicit Arbitrary instances
    given Arbitrary[Message] = Arbitrary(messageGen)
    given Arbitrary[UserMessage] = Arbitrary(userMessageGen)
    given Arbitrary[AssistantMessage] = Arbitrary(assistantMessageGen)
    given Arbitrary[SystemMessage] = Arbitrary(systemMessageGen)
    given Arbitrary[ResultMessage] = Arbitrary(resultMessageGen)

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
      case Right(Some(AssistantMessage(content))) =>
        assertEquals(content.length, 1)
        content.head match
          case TextBlock(text) =>
            assertEquals(text, "Hello! How can I help you today?")
          case other => fail(s"Expected TextBlock but got: $other")
      case other =>
        fail(s"Expected Right(Some(AssistantMessage(...))) but got: $other")

    resultResult match
      case Right(
            Some(
              ResultMessage(
                subtype,
                durationMs,
                durationApiMs,
                isError,
                numTurns,
                sessionId,
                _,
                _,
                _
              )
            )
          ) =>
        assertEquals(subtype, "conversation_result")
        assertEquals(durationMs, 1234)
        assertEquals(durationApiMs, 567)
        assertEquals(isError, false)
        assertEquals(numTurns, 1)
        assertEquals(sessionId, "session_123")
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
        case Right(Some(AssistantMessage(content))) =>
          assertEquals(AssistantMessage(content), originalMessage)
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
        "",
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
