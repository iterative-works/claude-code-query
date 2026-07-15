// PURPOSE: Token usage statistics from Claude API responses
// PURPOSE: Captures input/output tokens and optional cache-related token counts

package works.iterative.claude.core.model

import io.circe.Json

case class TokenUsage(
    inputTokens: Long,
    outputTokens: Long,
    cacheCreationInputTokens: Option[Long],
    cacheReadInputTokens: Option[Long],
    serviceTier: Option[String]
)

object TokenUsage:
  // Decodes a wire `usage` object; None when required token counts are absent
  def fromJson(json: Json): Option[TokenUsage] =
    val cursor = json.hcursor
    for
      inputTokens <- cursor.get[Long]("input_tokens").toOption
      outputTokens <- cursor.get[Long]("output_tokens").toOption
    yield TokenUsage(
      inputTokens = inputTokens,
      outputTokens = outputTokens,
      cacheCreationInputTokens =
        cursor.get[Long]("cache_creation_input_tokens").toOption,
      cacheReadInputTokens =
        cursor.get[Long]("cache_read_input_tokens").toOption,
      serviceTier = cursor.get[String]("service_tier").toOption
    )
