// PURPOSE: End-to-end test exercising the real Claude Code CLI through ClaudeCode
// PURPOSE: Runs only when ANTHROPIC_API_KEY is set so it is skipped without credentials

package works.iterative.claude.zio

import zio.*
import zio.test.*
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object ClaudeCodeE2ETest extends ClaudeZioSpec:
  def spec = suite("ClaudeCode (e2e, real CLI)")(
    test("queryResult returns a non-empty answer from the real CLI"):
      for answer <- ClaudeCode.queryResult(
                      QueryOptions.simple(
                        "Reply with exactly one word: pong"
                      )
                    )
      yield assertTrue(answer.nonEmpty)
  ) @@ TestAspect.withLiveClock
    @@ TestAspect.timeout(Duration.fromSeconds(120))
    @@ TestAspect.ifEnvSet("ANTHROPIC_API_KEY")
