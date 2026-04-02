package works.iterative.claude.core.log.model

// PURPOSE: Token usage statistics from Claude API responses in conversation logs
// PURPOSE: Captures input/output tokens and optional cache-related token counts

case class TokenUsage(
    inputTokens: Int,
    outputTokens: Int,
    cacheCreationInputTokens: Option[Int],
    cacheReadInputTokens: Option[Int],
    serviceTier: Option[String]
)
