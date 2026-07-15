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

  test("assistant message carries uuid, parent_tool_use_id, and model"):
    val assistantStr = """{
      "type": "assistant",
      "message": {
        "id": "msg_01ABC",
        "role": "assistant",
        "model": "claude-sonnet-4-5-20250929",
        "content": [{"type": "text", "text": "Hello"}]
      },
      "parent_tool_use_id": "toolu_01RKf",
      "session_id": "0d43043b-b7c3-4b21-b1e3-27436b1a1f0b",
      "uuid": "5f3d0f6a-8e1a-4f8b-9c2d-1a2b3c4d5e6f"
    }"""
    JsonParser.parseMessage(parseJson(assistantStr)) match
      case assistant: AssistantMessage =>
        assertEquals(
          assistant.id,
          Some(MessageId("5f3d0f6a-8e1a-4f8b-9c2d-1a2b3c4d5e6f"))
        )
        assertEquals(assistant.parentToolUseId, Some("toolu_01RKf"))
        assertEquals(assistant.model, Some("claude-sonnet-4-5-20250929"))
        assertEquals(assistant.content, List(TextBlock("Hello")))
      case other => fail(s"Expected AssistantMessage, got: $other")

  test(
    "assistant message without uuid, parent_tool_use_id, or model parses with None fields"
  ):
    val assistantStr = """{
      "type": "assistant",
      "message": {
        "role": "assistant",
        "content": [{"type": "text", "text": "Hi"}]
      },
      "session_id": "s-1"
    }"""
    JsonParser.parseMessage(parseJson(assistantStr)) match
      case assistant: AssistantMessage =>
        assertEquals(assistant.id, None)
        assertEquals(assistant.parentToolUseId, None)
        assertEquals(assistant.model, None)
      case other => fail(s"Expected AssistantMessage, got: $other")

  test("assistant message with null parent_tool_use_id parses it as None"):
    val assistantStr = """{
      "type": "assistant",
      "message": {
        "role": "assistant",
        "content": [{"type": "text", "text": "Hi"}]
      },
      "parent_tool_use_id": null,
      "session_id": "s-1"
    }"""
    JsonParser.parseMessage(parseJson(assistantStr)) match
      case assistant: AssistantMessage =>
        assertEquals(assistant.parentToolUseId, None)
      case other => fail(s"Expected AssistantMessage, got: $other")

  // Realistic in-turn result: the full 21-key wire field list, origin ABSENT
  private val inTurnResultStr = """{
    "type": "result",
    "subtype": "success",
    "is_error": false,
    "duration_ms": 35286,
    "duration_api_ms": 33712,
    "num_turns": 6,
    "result": "Done. The task completed.",
    "stop_reason": null,
    "terminal_reason": null,
    "session_id": "0d43043b-b7c3-4b21-b1e3-27436b1a1f0b",
    "total_cost_usd": 0.0918,
    "usage": {
      "input_tokens": 12,
      "cache_creation_input_tokens": 5311,
      "cache_read_input_tokens": 28337,
      "output_tokens": 328,
      "service_tier": "standard"
    },
    "modelUsage": {"claude-sonnet-4-5-20250929": {"inputTokens": 12}},
    "permission_denials": [],
    "api_error_status": null,
    "fast_mode_state": "disabled",
    "ttft_ms": 2716,
    "ttft_stream_ms": 2687,
    "time_to_request_ms": 214,
    "uuid": "c1a9e6a1-3d7f-4b0e-8f2a-6d5c4b3a2f1e"
  }"""

  test("in-turn result: origin key absent parses as None"):
    JsonParser.parseMessage(parseJson(inTurnResultStr)) match
      case rm: ResultMessage => assertEquals(rm.origin, None)
      case other             => fail(s"Expected ResultMessage, got: $other")

  test("result carries uuid as MessageId"):
    JsonParser.parseMessage(parseJson(inTurnResultStr)) match
      case rm: ResultMessage =>
        assertEquals(
          rm.id,
          Some(MessageId("c1a9e6a1-3d7f-4b0e-8f2a-6d5c4b3a2f1e"))
        )
      case other => fail(s"Expected ResultMessage, got: $other")

  test("result usage parses into real token counts"):
    JsonParser.parseMessage(parseJson(inTurnResultStr)) match
      case rm: ResultMessage =>
        assertEquals(
          rm.usage,
          Some(
            TokenUsage(
              inputTokens = 12,
              outputTokens = 328,
              cacheCreationInputTokens = Some(5311),
              cacheReadInputTokens = Some(28337),
              serviceTier = Some("standard")
            )
          )
        )
      case other => fail(s"Expected ResultMessage, got: $other")

  test("result parses timing fields into ResultTimings"):
    JsonParser.parseMessage(parseJson(inTurnResultStr)) match
      case rm: ResultMessage =>
        assertEquals(
          rm.timings,
          ResultTimings(
            ttftMs = Some(2716),
            ttftStreamMs = Some(2687),
            timeToRequestMs = Some(214)
          )
        )
      case other => fail(s"Expected ResultMessage, got: $other")

  test("result parses null stop_reason, terminal_reason, api_error_status as None"):
    JsonParser.parseMessage(parseJson(inTurnResultStr)) match
      case rm: ResultMessage =>
        assertEquals(rm.stopReason, None)
        assertEquals(rm.terminalReason, None)
        assertEquals(rm.apiErrorStatus, None)
      case other => fail(s"Expected ResultMessage, got: $other")

  test("result parses string stop_reason, terminal_reason, api_error_status"):
    val withReasons = """{
      "type": "result",
      "subtype": "error_during_execution",
      "is_error": true,
      "duration_ms": 100,
      "duration_api_ms": 90,
      "num_turns": 1,
      "stop_reason": "interrupt",
      "terminal_reason": "user_interrupt",
      "api_error_status": "overloaded",
      "session_id": "s-1"
    }"""
    JsonParser.parseMessage(parseJson(withReasons)) match
      case rm: ResultMessage =>
        assertEquals(rm.stopReason, Some("interrupt"))
        assertEquals(rm.terminalReason, Some("user_interrupt"))
        assertEquals(rm.apiErrorStatus, Some("overloaded"))
      case other => fail(s"Expected ResultMessage, got: $other")

  test("empty permission_denials parses as Nil"):
    JsonParser.parseMessage(parseJson(inTurnResultStr)) match
      case rm: ResultMessage => assertEquals(rm.permissionDenials, Nil)
      case other             => fail(s"Expected ResultMessage, got: $other")

  test("populated permission_denials entries survive as raw json"):
    val denialJson = """{"tool_name":"Bash","tool_input":{"command":"rm -rf /"}}"""
    val withDenials = s"""{
      "type": "result",
      "subtype": "success",
      "is_error": false,
      "duration_ms": 100,
      "duration_api_ms": 90,
      "num_turns": 1,
      "permission_denials": [$denialJson],
      "session_id": "s-1"
    }"""
    JsonParser.parseMessage(parseJson(withDenials)) match
      case rm: ResultMessage =>
        assertEquals(
          rm.permissionDenials,
          List(PermissionDenial(parseJson(denialJson)))
        )
      case other => fail(s"Expected ResultMessage, got: $other")

  test("out-of-turn result: origin kind task-notification"):
    val outOfTurn = """{
      "type": "result",
      "subtype": "success",
      "is_error": false,
      "duration_ms": 42580,
      "duration_api_ms": 41000,
      "num_turns": 7,
      "origin": {"kind": "task-notification"},
      "session_id": "s-1"
    }"""
    JsonParser.parseMessage(parseJson(outOfTurn)) match
      case rm: ResultMessage =>
        assertEquals(rm.origin, Some(ResultOrigin.TaskNotification))
      case other => fail(s"Expected ResultMessage, got: $other")

  test("origin with an unsampled kind stays open as Other"):
    val unsampled = """{
      "type": "result",
      "subtype": "success",
      "is_error": false,
      "duration_ms": 1,
      "duration_api_ms": 1,
      "num_turns": 1,
      "origin": {"kind": "scheduled-task"},
      "session_id": "s-1"
    }"""
    JsonParser.parseMessage(parseJson(unsampled)) match
      case rm: ResultMessage =>
        assertEquals(rm.origin, Some(ResultOrigin.Other("scheduled-task")))
      case other => fail(s"Expected ResultMessage, got: $other")

  test("origin null is present-but-unrecognized, never mistaken for in-turn"):
    val nullOrigin = """{
      "type": "result",
      "subtype": "success",
      "is_error": false,
      "duration_ms": 1,
      "duration_api_ms": 1,
      "num_turns": 1,
      "origin": null,
      "session_id": "s-1"
    }"""
    JsonParser.parseMessage(parseJson(nullOrigin)) match
      case rm: ResultMessage =>
        assertEquals(rm.origin, Some(ResultOrigin.Other("null")))
      case other => fail(s"Expected ResultMessage, got: $other")

  test("result with usage present but token counts missing parses usage as None"):
    val emptyUsage = """{
      "type": "result",
      "subtype": "success",
      "is_error": false,
      "duration_ms": 1,
      "duration_api_ms": 1,
      "num_turns": 1,
      "usage": {},
      "session_id": "s-1"
    }"""
    JsonParser.parseMessage(parseJson(emptyUsage)) match
      case rm: ResultMessage => assertEquals(rm.usage, None)
      case other             => fail(s"Expected ResultMessage, got: $other")

  test("control_response parses into ControlResponse with request_id"):
    val controlResponseStr = """{
      "type": "control_response",
      "response": {
        "subtype": "success",
        "request_id": "req_probe_1",
        "response": {"still_queued": []}
      }
    }"""
    JsonParser.parseMessage(parseJson(controlResponseStr)) match
      case ControlResponse(requestId, subtype, payload) =>
        assertEquals(requestId, "req_probe_1")
        assertEquals(subtype, "success")
        assertEquals(payload, parseJson("""{"still_queued": []}"""))
      case other => fail(s"Expected ControlResponse, got: $other")

  test("control_response without inner response payload carries Json.Null"):
    val controlResponseStr = """{
      "type": "control_response",
      "response": {
        "subtype": "success",
        "request_id": "req_2"
      }
    }"""
    JsonParser.parseMessage(parseJson(controlResponseStr)) match
      case ControlResponse(requestId, subtype, payload) =>
        assertEquals(requestId, "req_2")
        assertEquals(subtype, "success")
        assertEquals(payload, io.circe.Json.Null)
      case other => fail(s"Expected ControlResponse, got: $other")

  test("control_response missing request_id degrades to UnknownMessage"):
    val malformed = """{"type":"control_response","response":{"subtype":"success"}}"""
    JsonParser.parseMessage(parseJson(malformed)) match
      case UnknownMessage("control_response", _) => ()
      case other => fail(s"Expected UnknownMessage, got: $other")
