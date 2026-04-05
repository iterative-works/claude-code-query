// PURPOSE: Tests buildSessionArgs mapping from SessionOptions to Claude Code CLI arguments
// PURPOSE: Ensures required streaming flags are always present and all options map correctly

package works.iterative.claude.core.cli

import munit.CatsEffectSuite
import works.iterative.claude.core.model.{SessionOptions, PermissionMode}

class SessionOptionsArgsTest extends CatsEffectSuite:

  private val requiredFlags =
    List(
      "--print",
      "--input-format",
      "stream-json",
      "--output-format",
      "stream-json"
    )

  test("default options produce only the three required session flags"):
    val args = CLIArgumentBuilder.buildSessionArgs(SessionOptions())
    assertEquals(args, requiredFlags)

  test("required session flags appear at the start of the argument list"):
    val args = CLIArgumentBuilder.buildSessionArgs(
      SessionOptions().withModel("claude-opus-4-5")
    )
    assertEquals(args.take(requiredFlags.length), requiredFlags)

  test("no trailing prompt argument"):
    val args = CLIArgumentBuilder.buildSessionArgs(SessionOptions())
    assertEquals(args.length, requiredFlags.length)

  test("maxTurns maps to --max-turns"):
    val args =
      CLIArgumentBuilder.buildSessionArgs(SessionOptions().withMaxTurns(5))
    assert(args.contains("--max-turns"))
    assert(args.contains("5"))

  test("model maps to --model"):
    val args = CLIArgumentBuilder.buildSessionArgs(
      SessionOptions().withModel("claude-opus-4-5")
    )
    assert(args.contains("--model"))
    assert(args.contains("claude-opus-4-5"))

  test("allowedTools maps to --allowedTools with comma-joined value"):
    val args = CLIArgumentBuilder.buildSessionArgs(
      SessionOptions().withAllowedTools(List("Read", "Write", "Bash"))
    )
    assert(args.contains("--allowedTools"))
    assert(args.contains("Read,Write,Bash"))

  test("disallowedTools maps to --disallowedTools with comma-joined value"):
    val args = CLIArgumentBuilder.buildSessionArgs(
      SessionOptions().withDisallowedTools(List("Bash", "Write"))
    )
    assert(args.contains("--disallowedTools"))
    assert(args.contains("Bash,Write"))

  test("systemPrompt maps to --system-prompt"):
    val args = CLIArgumentBuilder.buildSessionArgs(
      SessionOptions().withSystemPrompt("You are a code reviewer.")
    )
    assert(args.contains("--system-prompt"))
    assert(args.contains("You are a code reviewer."))

  test("appendSystemPrompt maps to --append-system-prompt"):
    val args = CLIArgumentBuilder.buildSessionArgs(
      SessionOptions().withAppendSystemPrompt("Be concise.")
    )
    assert(args.contains("--append-system-prompt"))
    assert(args.contains("Be concise."))

  test(
    "continueConversation Some(true) maps to --continue, Some(false) produces no flag"
  ):
    val argsTrue = CLIArgumentBuilder.buildSessionArgs(
      SessionOptions().withContinueConversation(true)
    )
    assert(argsTrue.contains("--continue"))

    val argsFalse = CLIArgumentBuilder.buildSessionArgs(
      SessionOptions().withContinueConversation(false)
    )
    assert(!argsFalse.contains("--continue"))

  test("resume maps to --resume"):
    val args = CLIArgumentBuilder.buildSessionArgs(
      SessionOptions().withResume("session-id-xyz")
    )
    assert(args.contains("--resume"))
    assert(args.contains("session-id-xyz"))

  test("permissionMode maps to --permission-mode for all three enum values"):
    val defaultArgs = CLIArgumentBuilder.buildSessionArgs(
      SessionOptions().withPermissionMode(PermissionMode.Default)
    )
    assert(defaultArgs.contains("--permission-mode"))
    assert(defaultArgs.contains("default"))

    val acceptEditsArgs = CLIArgumentBuilder.buildSessionArgs(
      SessionOptions().withPermissionMode(PermissionMode.AcceptEdits)
    )
    assert(acceptEditsArgs.contains("--permission-mode"))
    assert(acceptEditsArgs.contains("acceptEdits"))

    val bypassArgs = CLIArgumentBuilder.buildSessionArgs(
      SessionOptions().withPermissionMode(PermissionMode.BypassPermissions)
    )
    assert(bypassArgs.contains("--permission-mode"))
    assert(bypassArgs.contains("bypassPermissions"))

  test("maxThinkingTokens maps to --max-thinking-tokens"):
    val args = CLIArgumentBuilder.buildSessionArgs(
      SessionOptions().withMaxThinkingTokens(8000)
    )
    assert(args.contains("--max-thinking-tokens"))
    assert(args.contains("8000"))

  test("None values produce no extra args beyond the required flags"):
    val args = CLIArgumentBuilder.buildSessionArgs(SessionOptions())
    assertEquals(args, requiredFlags)

  test("multiple options combine correctly"):
    val args = CLIArgumentBuilder.buildSessionArgs(
      SessionOptions()
        .withModel("claude-opus-4-5")
        .withSystemPrompt("You are a code reviewer")
        .withMaxTurns(3)
        .withPermissionMode(PermissionMode.AcceptEdits)
    )
    // Required flags come first
    assertEquals(args.take(requiredFlags.length), requiredFlags)
    // All option flags are present
    assert(args.contains("--model"))
    assert(args.contains("claude-opus-4-5"))
    assert(args.contains("--system-prompt"))
    assert(args.contains("You are a code reviewer"))
    assert(args.contains("--max-turns"))
    assert(args.contains("3"))
    assert(args.contains("--permission-mode"))
    assert(args.contains("acceptEdits"))
