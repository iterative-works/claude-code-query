package works.iterative.claude.core.parsing

// PURPOSE: Pure parsing of content blocks from Claude CLI JSON output
// PURPOSE: Handles text, tool_use, tool_result, thinking, and redacted_thinking block types

import io.circe.Json
import works.iterative.claude.core.model.*

object ContentBlockParser:

  def parseContentBlock(json: Json): Option[ContentBlock] =
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
        case "thinking" =>
          for
            thinking <- cursor.get[String]("thinking").toOption
            signature <- cursor.get[String]("signature").toOption
          yield ThinkingBlock(thinking, signature)
        case "redacted_thinking" =>
          cursor.get[String]("data").toOption.map(RedactedThinkingBlock.apply)
        case _ => None
