// PURPOSE: Configuration options for Claude Code queries, mapping to CLI arguments
// PURPOSE: Provides comprehensive parameter configuration for subprocess execution

package works.iterative.claude

import works.iterative.claude.model.*

/** Configuration options for Claude Code queries.
  *
  * Maps to Claude Code CLI arguments for subprocess execution.
  */
case class QueryOptions(
    /** The user prompt/question to send to Claude */
    prompt: String,

    /** Working directory for the Claude Code CLI process (maps to subprocess
      * cwd)
      */
    cwd: Option[String] = None,

    /** Which JavaScript runtime to use (node/bun) - for internal SDK use */
    executable: Option[String] = None,

    /** Arguments to pass to the JavaScript runtime - for internal SDK use */
    executableArgs: Option[List[String]] = None,

    /** Path to the Claude Code executable (overrides default bundled CLI) */
    pathToClaudeCodeExecutable: Option[String] = None,

    /** Maximum number of conversation turns in non-interactive mode
      * (--max-turns)
      */
    maxTurns: Option[Int] = None,

    /** List of tools Claude is allowed to use (--allowedTools). Examples:
      * ["Read", "Write", "Bash"], ["mcp__filesystem__read_file"]
      */
    allowedTools: Option[List[String]] = None,

    /** List of tools Claude is NOT allowed to use (--disallowedTools).
      * Examples: ["Bash"], ["mcp__github__create_issue"]
      */
    disallowedTools: Option[List[String]] = None,

    /** Custom system prompt to override Claude's default behavior
      * (--system-prompt). Only works in non-interactive mode. Example: "You are
      * a senior backend engineer."
      */
    systemPrompt: Option[String] = None,

    /** Additional instructions to append to the default system prompt
      * (--append-system-prompt). Only works in non-interactive mode. Example:
      * "Be concise in responses."
      */
    appendSystemPrompt: Option[String] = None,

    /** List of MCP (Model Context Protocol) tools to enable. MCP tool names
      * follow pattern: mcp__<serverName>__<toolName>
      */
    mcpTools: Option[List[String]] = None,

    /** How to handle tool permission prompts (--permission-mode).
      *   - Default: CLI prompts for dangerous tools
      *   - AcceptEdits: Auto-accept file edit operations
      *   - BypassPermissions: Allow all tools without prompting (use with
      *     caution)
      */
    permissionMode: Option[PermissionMode] = None,

    /** Whether to continue from the most recent conversation (--continue) */
    continueConversation: Option[Boolean] = None,

    /** Session ID to resume a specific conversation (--resume). Example:
      * "550e8400-e29b-41d4-a716-446655440000"
      */
    resume: Option[String] = None,

    /** Specific Claude model to use (--model). Example:
      * "claude-3-5-sonnet-20241022"
      */
    model: Option[String] = None,

    /** Maximum tokens for Claude's internal reasoning/thinking. Controls the
      * depth of Claude's step-by-step reasoning process
      */
    maxThinkingTokens: Option[Int] = None,

    /** Timeout for the Claude Code CLI process execution. If not specified, no
      * timeout is applied and the process can run indefinitely.
      */
    timeout: Option[scala.concurrent.duration.FiniteDuration] = None,

    /** Whether to inherit the parent process environment variables. If None or
      * Some(true), inherits all environment variables. If Some(false), starts
      * with empty environment.
      */
    inheritEnvironment: Option[Boolean] = None,

    /** Additional environment variables to set for the Claude Code CLI process.
      * These are added to the process environment (if inheritEnvironment is
      * true) or used as the complete environment (if inheritEnvironment is
      * false).
      */
    environmentVariables: Option[Map[String, String]] = None
)
