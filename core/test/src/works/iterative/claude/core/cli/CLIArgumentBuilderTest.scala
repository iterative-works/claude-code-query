// PURPOSE: Tests CLI parameter mapping functionality for QueryOptions to CLI arguments conversion
// PURPOSE: Ensures all QueryOptions parameters are correctly translated to Claude Code CLI flags

package works.iterative.claude.core.cli

import munit.CatsEffectSuite
import works.iterative.claude.core.model.QueryOptions
import works.iterative.claude.core.cli.CLIArgumentBuilder
import works.iterative.claude.core.model.PermissionMode

class CLIArgumentBuilderTest extends CatsEffectSuite:

  test("maxTurns parameter maps to --max-turns CLI argument"):
    val options = QueryOptions(
      prompt = "test prompt",
      maxTurns = Some(5)
    )

    val args = CLIArgumentBuilder.buildArgs(options)

    assert(args.contains("--max-turns"))
    assert(args.contains("5"))

  test("model parameter maps to --model CLI argument"):
    val options = QueryOptions(
      prompt = "test prompt",
      model = Some("claude-3-5-sonnet-20241022")
    )

    val args = CLIArgumentBuilder.buildArgs(options)

    assert(args.contains("--model"))
    assert(args.contains("claude-3-5-sonnet-20241022"))

  test("allowedTools parameter maps to --allowedTools CLI argument"):
    val options = QueryOptions(
      prompt = "test prompt",
      allowedTools = Some(List("Read", "Write", "Bash"))
    )

    val args = CLIArgumentBuilder.buildArgs(options)

    assert(args.contains("--allowedTools"))
    assert(args.contains("Read,Write,Bash"))

  test("disallowedTools parameter maps to --disallowedTools CLI argument"):
    val options = QueryOptions(
      prompt = "test prompt",
      disallowedTools = Some(List("Bash", "Write"))
    )

    val args = CLIArgumentBuilder.buildArgs(options)

    assert(args.contains("--disallowedTools"))
    assert(args.contains("Bash,Write"))

  test("systemPrompt parameter maps to --system-prompt CLI argument"):
    val options = QueryOptions(
      prompt = "test prompt",
      systemPrompt = Some("You are a helpful assistant.")
    )

    val args = CLIArgumentBuilder.buildArgs(options)

    assert(args.contains("--system-prompt"))
    assert(args.contains("You are a helpful assistant."))

  test(
    "appendSystemPrompt parameter maps to --append-system-prompt CLI argument"
  ):
    val options = QueryOptions(
      prompt = "test prompt",
      appendSystemPrompt = Some("Additional instructions.")
    )

    val args = CLIArgumentBuilder.buildArgs(options)

    assert(args.contains("--append-system-prompt"))
    assert(args.contains("Additional instructions."))

  test("continueConversation parameter maps to --continue CLI argument"):
    val options = QueryOptions(
      prompt = "test prompt",
      continueConversation = Some(true)
    )

    val args = CLIArgumentBuilder.buildArgs(options)

    assert(args.contains("--continue"))

  test("resume parameter maps to --resume CLI argument"):
    val options = QueryOptions(
      prompt = "test prompt",
      resume = Some("conversation-id-123")
    )

    val args = CLIArgumentBuilder.buildArgs(options)

    assert(args.contains("--resume"))
    assert(args.contains("conversation-id-123"))

  test("permissionMode parameter maps to --permission-mode CLI argument"):
    val options = QueryOptions(
      prompt = "test prompt",
      permissionMode = Some(PermissionMode.AcceptEdits)
    )

    val args = CLIArgumentBuilder.buildArgs(options)

    assert(args.contains("--permission-mode"))
    assert(args.contains("acceptEdits"))

  test(
    "maxThinkingTokens parameter maps to --max-thinking-tokens CLI argument"
  ):
    val options = QueryOptions(
      prompt = "test prompt",
      maxThinkingTokens = Some(5000)
    )

    val args = CLIArgumentBuilder.buildArgs(options)

    assert(args.contains("--max-thinking-tokens"))
    assert(args.contains("5000"))

  test("multiple parameters combine correctly in CLI arguments"):
    val options = QueryOptions(
      prompt = "test prompt",
      maxTurns = Some(3),
      model = Some("claude-3-5-sonnet-20241022"),
      allowedTools = Some(List("Read", "Write")),
      systemPrompt = Some("You are helpful."),
      continueConversation = Some(true),
      permissionMode = Some(PermissionMode.AcceptEdits),
      maxThinkingTokens = Some(1000)
    )

    val args = CLIArgumentBuilder.buildArgs(options)

    // Verify all parameters are present
    assert(args.contains("--max-turns"))
    assert(args.contains("3"))
    assert(args.contains("--model"))
    assert(args.contains("claude-3-5-sonnet-20241022"))
    assert(args.contains("--allowedTools"))
    assert(args.contains("Read,Write"))
    assert(args.contains("--system-prompt"))
    assert(args.contains("You are helpful."))
    assert(args.contains("--continue"))
    assert(args.contains("--permission-mode"))
    assert(args.contains("acceptEdits"))
    assert(args.contains("--max-thinking-tokens"))
    assert(args.contains("1000"))

  test("None values produce no CLI arguments"):
    val options = QueryOptions(
      prompt = "test prompt",
      maxTurns = None,
      model = None,
      allowedTools = None,
      systemPrompt = None,
      permissionMode = None
    )

    val args = CLIArgumentBuilder.buildArgs(options)

    // Should contain no CLI arguments for None values
    assert(!args.contains("--max-turns"))
    assert(!args.contains("--model"))
    assert(!args.contains("--allowedTools"))
    assert(!args.contains("--system-prompt"))
    assert(!args.contains("--permission-mode"))

  test("empty tool lists produce no CLI arguments"):
    val options = QueryOptions(
      prompt = "test prompt",
      allowedTools = Some(List.empty),
      disallowedTools = Some(List.empty)
    )

    val args = CLIArgumentBuilder.buildArgs(options)

    // Should contain CLI flags but with empty values
    assert(args.contains("--allowedTools"))
    assert(args.contains(""))
    assert(args.contains("--disallowedTools"))

  test("PermissionMode.DontAsk maps to --permission-mode dontAsk"):
    val options = QueryOptions
      .simple("test prompt")
      .withPermissionMode(PermissionMode.DontAsk)

    val args = CLIArgumentBuilder.buildArgs(options)

    assert(args.containsSlice(List("--permission-mode", "dontAsk")))

  test("strictMcpConfig = Some(true) emits --strict-mcp-config"):
    val args = CLIArgumentBuilder.buildArgs(
      QueryOptions.simple("test prompt").withStrictMcpConfig(true)
    )
    assert(args.contains("--strict-mcp-config"))

  test("strictMcpConfig default (None) does not emit --strict-mcp-config"):
    val args = CLIArgumentBuilder.buildArgs(QueryOptions.simple("test prompt"))
    assert(!args.contains("--strict-mcp-config"))

  test("strictMcpConfig = Some(false) does not emit --strict-mcp-config"):
    val args = CLIArgumentBuilder.buildArgs(
      QueryOptions.simple("test prompt").withStrictMcpConfig(false)
    )
    assert(!args.contains("--strict-mcp-config"))

  test("mcpConfigPath maps to --mcp-config <path>"):
    val args = CLIArgumentBuilder.buildArgs(
      QueryOptions.simple("test prompt").withMcpConfigPath("./.mcp.json")
    )
    assert(args.containsSlice(List("--mcp-config", "./.mcp.json")))
    assert(!args.contains("--"))

  test("mcpConfigPath default (None) does not emit --mcp-config"):
    val args = CLIArgumentBuilder.buildArgs(QueryOptions.simple("test prompt"))
    assert(!args.contains("--mcp-config"))

  test(
    "settingSources non-empty maps to --setting-sources with CSV value"
  ):
    val args = CLIArgumentBuilder.buildArgs(
      QueryOptions
        .simple("test prompt")
        .withSettingSources(List("project", "user"))
    )
    assert(args.containsSlice(List("--setting-sources", "project,user")))

  test("settingSources default (Nil) does not emit --setting-sources"):
    val args = CLIArgumentBuilder.buildArgs(QueryOptions.simple("test prompt"))
    assert(!args.contains("--setting-sources"))

  test(
    "all four Option-C isolation flags round-trip in deterministic order"
  ):
    val options = QueryOptions
      .simple("test prompt")
      .withStrictMcpConfig(true)
      .withMcpConfigPath("./.mcp.json")
      .withSettingSources(List("project", "local"))
      .withPermissionMode(PermissionMode.DontAsk)

    val args = CLIArgumentBuilder.buildArgs(options)

    // presence
    assert(args.contains("--strict-mcp-config"))
    assert(args.containsSlice(List("--mcp-config", "./.mcp.json")))
    assert(args.containsSlice(List("--setting-sources", "project,local")))
    assert(args.containsSlice(List("--permission-mode", "dontAsk")))
    // buildArgs itself does not emit "--"; the caller inserts it before
    // the positional prompt.
    assert(!args.contains("--"))

    // deterministic order: permission-mode precedes the MCP/settings trio,
    // which appears as strict -> mcp-config -> setting-sources
    val pmIdx = args.indexOf("--permission-mode")
    val strictIdx = args.indexOf("--strict-mcp-config")
    val mcpIdx = args.indexOf("--mcp-config")
    val ssIdx = args.indexOf("--setting-sources")
    assert(pmIdx < strictIdx)
    assert(strictIdx < mcpIdx)
    assert(mcpIdx < ssIdx)

  test("special characters in prompts and values are preserved"):
    val options = QueryOptions(
      prompt = "test prompt",
      systemPrompt =
        Some("You are a helpful assistant! Use @special #chars & symbols."),
      model = Some("claude-3.5-sonnet@latest")
    )

    val args = CLIArgumentBuilder.buildArgs(options)

    assert(args.contains("--system-prompt"))
    assert(
      args.contains(
        "You are a helpful assistant! Use @special #chars & symbols."
      )
    )
    assert(args.contains("--model"))
    assert(args.contains("claude-3.5-sonnet@latest"))
