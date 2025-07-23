package works.iterative.claude.core.model

// PURPOSE: Content block type hierarchy for structured message content
// PURPOSE: Represents different types of content within assistant messages

// Content block types - simplified like Python SDK
sealed trait ContentBlock

case class TextBlock(text: String) extends ContentBlock

case class ToolUseBlock(
    id: String,
    name: String,
    input: Map[String, Any]
) extends ContentBlock

case class ToolResultBlock(
    toolUseId: String,
    content: Option[String] = None,
    isError: Option[Boolean] = None
) extends ContentBlock
