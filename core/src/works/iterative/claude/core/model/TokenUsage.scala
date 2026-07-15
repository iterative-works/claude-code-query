package works.iterative.claude.core.model

// PURPOSE: Token usage statistics from Claude API responses
// PURPOSE: Captures input/output tokens and optional cache-related token counts

import io.circe.Json

case class TokenUsage(
    inputTokens: Int,
    outputTokens: Int,
    cacheCreationInputTokens: Option[Int],
    cacheReadInputTokens: Option[Int],
    serviceTier: Option[String]
)

object TokenUsage:
  // Decodes a wire `usage` object; None when required token counts are absent
  def fromJson(json: Json): Option[TokenUsage] =
    val cursor = json.hcursor
    for
      inputTokens <- cursor.get[Int]("input_tokens").toOption
      outputTokens <- cursor.get[Int]("output_tokens").toOption
    yield TokenUsage(
      inputTokens = inputTokens,
      outputTokens = outputTokens,
      cacheCreationInputTokens =
        cursor.get[Int]("cache_creation_input_tokens").toOption,
      cacheReadInputTokens =
        cursor.get[Int]("cache_read_input_tokens").toOption,
      serviceTier = cursor.get[String]("service_tier").toOption
    )
