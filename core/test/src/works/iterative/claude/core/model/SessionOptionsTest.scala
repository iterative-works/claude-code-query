// PURPOSE: Tests SessionOptions construction, defaults, and fluent builder methods
// PURPOSE: Ensures each with* builder sets only its targeted field and leaves others unchanged

package works.iterative.claude.core.model

import munit.CatsEffectSuite
import scala.concurrent.duration.*

class SessionOptionsTest extends CatsEffectSuite:

  test("SessionOptions() has all None fields"):
    val opts = SessionOptions()
    assertEquals(opts.cwd, None)
    assertEquals(opts.executable, None)
    assertEquals(opts.executableArgs, None)
    assertEquals(opts.pathToClaudeCodeExecutable, None)
    assertEquals(opts.maxTurns, None)
    assertEquals(opts.allowedTools, None)
    assertEquals(opts.disallowedTools, None)
    assertEquals(opts.systemPrompt, None)
    assertEquals(opts.appendSystemPrompt, None)
    assertEquals(opts.mcpTools, None)
    assertEquals(opts.permissionMode, None)
    assertEquals(opts.continueConversation, None)
    assertEquals(opts.resume, None)
    assertEquals(opts.model, None)
    assertEquals(opts.maxThinkingTokens, None)
    assertEquals(opts.timeout, None)
    assertEquals(opts.inheritEnvironment, None)
    assertEquals(opts.environmentVariables, None)

  test("SessionOptions.defaults equals SessionOptions()"):
    assertEquals(SessionOptions.defaults, SessionOptions())

  // SessionOptions has no prompt field — enforced at compile time by the type definition
  test("each with* builder sets only its field"):
    val base = SessionOptions()

    assertEquals(base.withCwd("/tmp"), base.copy(cwd = Some("/tmp")))
    assertEquals(
      base.withExecutable("bun"),
      base.copy(executable = Some("bun"))
    )
    assertEquals(
      base.withExecutableArgs(List("--arg1")),
      base.copy(executableArgs = Some(List("--arg1")))
    )
    assertEquals(
      base.withClaudeExecutable("/usr/local/bin/claude"),
      base.copy(pathToClaudeCodeExecutable = Some("/usr/local/bin/claude"))
    )
    assertEquals(base.withMaxTurns(10), base.copy(maxTurns = Some(10)))
    assertEquals(
      base.withAllowedTools(List("Read", "Write")),
      base.copy(allowedTools = Some(List("Read", "Write")))
    )
    assertEquals(
      base.withDisallowedTools(List("Bash")),
      base.copy(disallowedTools = Some(List("Bash")))
    )
    assertEquals(
      base.withSystemPrompt("You are helpful."),
      base.copy(systemPrompt = Some("You are helpful."))
    )
    assertEquals(
      base.withAppendSystemPrompt("Be concise."),
      base.copy(appendSystemPrompt = Some("Be concise."))
    )
    assertEquals(
      base.withMcpTools(List("mcp__fs__read")),
      base.copy(mcpTools = Some(List("mcp__fs__read")))
    )
    assertEquals(
      base.withPermissionMode(PermissionMode.AcceptEdits),
      base.copy(permissionMode = Some(PermissionMode.AcceptEdits))
    )
    assertEquals(
      base.withContinueConversation(true),
      base.copy(continueConversation = Some(true))
    )
    assertEquals(
      base.withResume("session-id-abc"),
      base.copy(resume = Some("session-id-abc"))
    )
    assertEquals(
      base.withModel("claude-opus-4-5"),
      base.copy(model = Some("claude-opus-4-5"))
    )
    assertEquals(
      base.withMaxThinkingTokens(8000),
      base.copy(maxThinkingTokens = Some(8000))
    )
    assertEquals(
      base.withTimeout(30.seconds),
      base.copy(timeout = Some(30.seconds))
    )
    assertEquals(
      base.withInheritEnvironment(false),
      base.copy(inheritEnvironment = Some(false))
    )
    assertEquals(
      base.withEnvironmentVariables(Map("KEY" -> "value")),
      base.copy(environmentVariables = Some(Map("KEY" -> "value")))
    )
