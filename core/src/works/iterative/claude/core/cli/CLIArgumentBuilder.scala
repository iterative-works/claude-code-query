// PURPOSE: Converts QueryOptions case class parameters into Claude Code CLI arguments
// PURPOSE: Ensures all SDK parameters are properly mapped to their corresponding CLI flags

package works.iterative.claude.core.cli

import works.iterative.claude.core.model.{
  QueryOptions,
  SessionOptions,
  PermissionMode
}

object CLIArgumentBuilder:
  /** Converts QueryOptions into list of CLI arguments for Claude Code
    * subprocess
    */
  def buildArgs(options: QueryOptions): List[String] =
    val maxTurnsArgs = options.maxTurns match
      case Some(turns) => List("--max-turns", turns.toString)
      case None        => List.empty

    val modelArgs = options.model match
      case Some(model) => List("--model", model)
      case None        => List.empty

    val allowedToolsArgs = options.allowedTools match
      case Some(tools) => List("--allowedTools", tools.mkString(","))
      case None        => List.empty

    val disallowedToolsArgs = options.disallowedTools match
      case Some(tools) => List("--disallowedTools", tools.mkString(","))
      case None        => List.empty

    val systemPromptArgs = options.systemPrompt match
      case Some(prompt) => List("--system-prompt", prompt)
      case None         => List.empty

    val appendSystemPromptArgs = options.appendSystemPrompt match
      case Some(prompt) => List("--append-system-prompt", prompt)
      case None         => List.empty

    val continueConversationArgs = options.continueConversation match
      case Some(true) => List("--continue")
      case _          => List.empty

    val resumeArgs = options.resume match
      case Some(conversationId) => List("--resume", conversationId)
      case None                 => List.empty

    val permissionModeArgs = options.permissionMode match
      case Some(PermissionMode.Default) => List("--permission-mode", "default")
      case Some(PermissionMode.AcceptEdits) =>
        List("--permission-mode", "acceptEdits")
      case Some(PermissionMode.BypassPermissions) =>
        List("--permission-mode", "bypassPermissions")
      case Some(PermissionMode.DontAsk) =>
        List("--permission-mode", "dontAsk")
      case None => List.empty

    val maxThinkingTokensArgs = options.maxThinkingTokens match
      case Some(tokens) => List("--max-thinking-tokens", tokens.toString)
      case None         => List.empty

    val strictMcpConfigArgs = options.strictMcpConfig match
      case Some(true) => List("--strict-mcp-config")
      case _          => List.empty

    val mcpConfigPathArgs = options.mcpConfigPath match
      case Some(path) => List("--mcp-config", path, "--")
      case None       => List.empty

    val settingSourcesArgs =
      if options.settingSources.nonEmpty then
        List("--setting-sources", options.settingSources.mkString(","))
      else List.empty

    List(
      maxTurnsArgs,
      modelArgs,
      allowedToolsArgs,
      disallowedToolsArgs,
      systemPromptArgs,
      appendSystemPromptArgs,
      continueConversationArgs,
      resumeArgs,
      permissionModeArgs,
      maxThinkingTokensArgs,
      strictMcpConfigArgs,
      mcpConfigPathArgs,
      settingSourcesArgs
    ).flatten

  /** Converts SessionOptions into list of CLI arguments for a Claude Code
    * streaming session subprocess. Always prepends the required streaming
    * flags: --print, --input-format stream-json, --output-format stream-json.
    * Does not append a trailing prompt argument.
    */
  def buildSessionArgs(options: SessionOptions): List[String] =
    val maxTurnsArgs = options.maxTurns match
      case Some(turns) => List("--max-turns", turns.toString)
      case None        => List.empty

    val modelArgs = options.model match
      case Some(model) => List("--model", model)
      case None        => List.empty

    val allowedToolsArgs = options.allowedTools match
      case Some(tools) => List("--allowedTools", tools.mkString(","))
      case None        => List.empty

    val disallowedToolsArgs = options.disallowedTools match
      case Some(tools) => List("--disallowedTools", tools.mkString(","))
      case None        => List.empty

    val systemPromptArgs = options.systemPrompt match
      case Some(prompt) => List("--system-prompt", prompt)
      case None         => List.empty

    val appendSystemPromptArgs = options.appendSystemPrompt match
      case Some(prompt) => List("--append-system-prompt", prompt)
      case None         => List.empty

    val continueConversationArgs = options.continueConversation match
      case Some(true) => List("--continue")
      case _          => List.empty

    val resumeArgs = options.resume match
      case Some(conversationId) => List("--resume", conversationId)
      case None                 => List.empty

    val permissionModeArgs = options.permissionMode match
      case Some(PermissionMode.Default) => List("--permission-mode", "default")
      case Some(PermissionMode.AcceptEdits) =>
        List("--permission-mode", "acceptEdits")
      case Some(PermissionMode.BypassPermissions) =>
        List("--permission-mode", "bypassPermissions")
      case Some(PermissionMode.DontAsk) =>
        List("--permission-mode", "dontAsk")
      case None => List.empty

    val maxThinkingTokensArgs = options.maxThinkingTokens match
      case Some(tokens) => List("--max-thinking-tokens", tokens.toString)
      case None         => List.empty

    val strictMcpConfigArgs = options.strictMcpConfig match
      case Some(true) => List("--strict-mcp-config")
      case _          => List.empty

    val mcpConfigPathArgs = options.mcpConfigPath match
      case Some(path) => List("--mcp-config", path, "--")
      case None       => List.empty

    val settingSourcesArgs =
      if options.settingSources.nonEmpty then
        List("--setting-sources", options.settingSources.mkString(","))
      else List.empty

    List(
      List(
        "--print",
        "--input-format",
        "stream-json",
        "--output-format",
        "stream-json"
      ),
      maxTurnsArgs,
      modelArgs,
      allowedToolsArgs,
      disallowedToolsArgs,
      systemPromptArgs,
      appendSystemPromptArgs,
      continueConversationArgs,
      resumeArgs,
      permissionModeArgs,
      maxThinkingTokensArgs,
      strictMcpConfigArgs,
      mcpConfigPathArgs,
      settingSourcesArgs
    ).flatten
