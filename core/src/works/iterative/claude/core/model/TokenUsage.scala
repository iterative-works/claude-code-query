package works.iterative.claude.core.model

// PURPOSE: Token usage statistics from Claude API responses
// PURPOSE: Captures input/output tokens and optional cache-related token counts

case class TokenUsage(
    inputTokens: Int,
    outputTokens: Int,
    cacheCreationInputTokens: Option[Int],
    cacheReadInputTokens: Option[Int],
    serviceTier: Option[String]
)
