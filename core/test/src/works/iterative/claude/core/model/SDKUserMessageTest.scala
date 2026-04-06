// PURPOSE: Unit tests for SDKUserMessage circe Encoder
// PURPOSE: Verifies the exact JSON shape required by the Claude Code stdin protocol

package works.iterative.claude.core.model

import munit.FunSuite
import io.circe.syntax.*

class SDKUserMessageTest extends FunSuite:

  test("Encoder produces correct JSON structure"):
    val msg = SDKUserMessage("hello", "session-1", None)
    val json = msg.asJson

    assertEquals(json.hcursor.get[String]("type").toOption, Some("user"))
    assertEquals(
      json.hcursor.get[String]("session_id").toOption,
      Some("session-1")
    )
    assert(json.hcursor.downField("parent_tool_use_id").focus.exists(_.isNull))

    val message = json.hcursor.downField("message")
    assertEquals(message.get[String]("role").toOption, Some("user"))
    assertEquals(message.get[String]("content").toOption, Some("hello"))

  test("Encoder produces string value for Some(parentToolUseId)"):
    val msg = SDKUserMessage("hello", "s1", Some("tool-123"))
    val json = msg.asJson

    assertEquals(
      json.hcursor.get[String]("parent_tool_use_id").toOption,
      Some("tool-123")
    )

  test("Encoder accepts pending as session_id"):
    val msg = SDKUserMessage("hello", "pending", None)
    val json = msg.asJson

    assertEquals(
      json.hcursor.get[String]("session_id").toOption,
      Some("pending")
    )

  test("Encoded JSON contains no embedded newlines"):
    val msg = SDKUserMessage("hello world", "session-1", None)
    val jsonStr = msg.asJson.noSpaces

    assert(
      !jsonStr.contains('\n'),
      s"JSON should not contain newlines: $jsonStr"
    )
