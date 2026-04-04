// PURPOSE: Represents a user message written to Claude Code CLI stdin in stream-json mode
// PURPOSE: Provides circe Encoder producing the exact JSON shape the CLI protocol expects

package works.iterative.claude.core.model

import io.circe.{Encoder, Json}

case class SDKUserMessage(
    content: String,
    sessionId: String,
    parentToolUseId: Option[String] = None
)

object SDKUserMessage:
  given Encoder[SDKUserMessage] = Encoder.instance { msg =>
    Json.obj(
      "type" -> Json.fromString("user"),
      "message" -> Json.obj(
        "role" -> Json.fromString("user"),
        "content" -> Json.fromString(msg.content)
      ),
      "parent_tool_use_id" -> msg.parentToolUseId.fold(Json.Null)(
        Json.fromString
      ),
      "session_id" -> Json.fromString(msg.sessionId)
    )
  }
