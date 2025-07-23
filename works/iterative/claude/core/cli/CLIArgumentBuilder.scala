// PURPOSE: Converts QueryOptions case class parameters into Claude Code CLI arguments
// PURPOSE: Ensures all SDK parameters are properly mapped to their corresponding CLI flags

package works.iterative.claude.core.cli

import works.iterative.claude.core.model.{QueryOptions, PermissionMode}

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
        List("--permission-mode", "accept-edits")
      case Some(PermissionMode.BypassPermissions) =>
        List("--permission-mode", "bypass-permissions")
      case None => List.empty

    val maxThinkingTokensArgs = options.maxThinkingTokens match
      case Some(tokens) => List("--max-thinking-tokens", tokens.toString)
      case None         => List.empty

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
      maxThinkingTokensArgs
    ).flatten
