package works.iterative.claude.core.parsing

// PURPOSE: Unit tests for total message parsing — every JSON line survives as a typed Message
// PURPOSE: Verifies UnknownMessage fallback, wire-field extraction, and control protocol parsing

import munit.FunSuite
import io.circe.parser
import works.iterative.claude.core.model.*

class JsonParserTotalityTest extends FunSuite:

  private def parseJson(jsonStr: String): io.circe.Json =
    parser.parse(jsonStr).getOrElse(fail(s"Test JSON does not parse: $jsonStr"))

  test("unknown message type survives as UnknownMessage carrying full json"):
    val rateLimitStr =
      """{"type":"rate_limit_event","rate_limit":{"status":"allowed","unifiedRateLimitFallbackAvailable":false},"uuid":"7e6a41a5-0001-4a41-a5eb-46a4c7c3d282","session_id":"0d43043b-b7c3-4b21-b1e3-27436b1a1f0b"}"""
    val json = parseJson(rateLimitStr)
    JsonParser.parseMessage(json) match
      case UnknownMessage(messageType, raw) =>
        assertEquals(messageType, "rate_limit_event")
        assertEquals(raw, json)
      case other => fail(s"Expected UnknownMessage, got: $other")

  test("JSON object without a type key survives as UnknownMessage"):
    val json = parseJson("""{"foo":"bar"}""")
    JsonParser.parseMessage(json) match
      case UnknownMessage(messageType, raw) =>
        assertEquals(messageType, "")
        assertEquals(raw, json)
      case other => fail(s"Expected UnknownMessage, got: $other")

  test("known type with missing required fields degrades to UnknownMessage"):
    val json = parseJson("""{"type":"result","subtype":"success"}""")
    JsonParser.parseMessage(json) match
      case UnknownMessage(messageType, raw) =>
        assertEquals(messageType, "result")
        assertEquals(raw, json)
      case other => fail(s"Expected UnknownMessage, got: $other")

  test("parseJsonLine yields None for a blank line"):
    assertEquals(JsonParser.parseJsonLine("   \t "), None)

  test("parseJsonLine yields None for a non-JSON line"):
    assertEquals(JsonParser.parseJsonLine("not json at all"), None)

  test("parseJsonLine wraps every parsed JSON line in Some"):
    val line = """{"type":"never_seen_before","payload":42}"""
    JsonParser.parseJsonLine(line) match
      case Some(UnknownMessage("never_seen_before", _)) => ()
      case other => fail(s"Expected Some(UnknownMessage), got: $other")
