// PURPOSE: Configuration options for Claude Code streaming sessions, mapping to CLI arguments
// PURPOSE: Provides all session startup parameters except the prompt, which arrives per-turn

package works.iterative.claude.core.model

/** Configuration options for a Claude Code streaming session.
  *
  * Contains all fields from QueryOptions except prompt, which is sent per-turn
  * during the session. Fields that are process-level configuration (timeout,
  * inheritEnvironment, environmentVariables, executable, executableArgs,
  * pathToClaudeCodeExecutable) are consumed by the session runner and not
  * translated to CLI flags.
  */
case class SessionOptions(
    /** Working directory for the Claude Code CLI process */
    cwd: Option[String] = None,

    /** Which JavaScript runtime to use (node/bun) - for internal SDK use */
    executable: Option[String] = None,

    /** Arguments to pass to the JavaScript runtime - for internal SDK use */
    executableArgs: Option[List[String]] = None,

    /** Path to the Claude Code executable (overrides default bundled CLI) */
    pathToClaudeCodeExecutable: Option[String] = None,

    /** Maximum number of conversation turns (--max-turns) */
    maxTurns: Option[Int] = None,

    /** List of tools Claude is allowed to use (--allowedTools) */
    allowedTools: Option[List[String]] = None,

    /** List of tools Claude is NOT allowed to use (--disallowedTools) */
    disallowedTools: Option[List[String]] = None,

    /** Custom system prompt to override Claude's default behavior
      * (--system-prompt)
      */
    systemPrompt: Option[String] = None,

    /** Additional instructions to append to the default system prompt
      * (--append-system-prompt)
      */
    appendSystemPrompt: Option[String] = None,

    /** List of MCP (Model Context Protocol) tools to enable */
    mcpTools: Option[List[String]] = None,

    /** How to handle tool permission prompts (--permission-mode) */
    permissionMode: Option[PermissionMode] = None,

    /** Whether to continue from the most recent conversation (--continue) */
    continueConversation: Option[Boolean] = None,

    /** Session ID to resume a specific conversation (--resume) */
    resume: Option[String] = None,

    /** Specific Claude model to use (--model) */
    model: Option[String] = None,

    /** Maximum tokens for Claude's internal reasoning/thinking */
    maxThinkingTokens: Option[Int] = None,

    /** Timeout for the Claude Code CLI process execution */
    timeout: Option[scala.concurrent.duration.FiniteDuration] = None,

    /** Whether to inherit the parent process environment variables */
    inheritEnvironment: Option[Boolean] = None,

    /** Additional environment variables to set for the Claude Code CLI process
      */
    environmentVariables: Option[Map[String, String]] = None,

    /** Restrict Claude to only the MCP servers listed in --mcp-config
      * (--strict-mcp-config). `Some(true)` emits the flag; `Some(false)` and
      * `None` emit nothing.
      */
    strictMcpConfig: Option[Boolean] = None,

    /** Path to an MCP configuration file (--mcp-config <path> --). The trailing
      * `--` terminator is mandatory because the flag is variadic.
      */
    mcpConfigPath: Option[String] = None,

    /** Ordered list of setting sources to load (--setting-sources <csv>). Empty
      * list emits nothing.
      */
    settingSources: List[String] = Nil
):
  def withCwd(cwd: String): SessionOptions = copy(cwd = Some(cwd))
  def withExecutable(executable: String): SessionOptions =
    copy(executable = Some(executable))
  def withExecutableArgs(args: List[String]): SessionOptions =
    copy(executableArgs = Some(args))
  def withClaudeExecutable(path: String): SessionOptions =
    copy(pathToClaudeCodeExecutable = Some(path))
  def withMaxTurns(turns: Int): SessionOptions = copy(maxTurns = Some(turns))
  def withAllowedTools(tools: List[String]): SessionOptions =
    copy(allowedTools = Some(tools))
  def withDisallowedTools(tools: List[String]): SessionOptions =
    copy(disallowedTools = Some(tools))
  def withSystemPrompt(prompt: String): SessionOptions =
    copy(systemPrompt = Some(prompt))
  def withAppendSystemPrompt(prompt: String): SessionOptions =
    copy(appendSystemPrompt = Some(prompt))
  def withMcpTools(tools: List[String]): SessionOptions =
    copy(mcpTools = Some(tools))
  def withPermissionMode(mode: PermissionMode): SessionOptions =
    copy(permissionMode = Some(mode))
  def withContinueConversation(continue: Boolean): SessionOptions =
    copy(continueConversation = Some(continue))
  def withResume(sessionId: String): SessionOptions =
    copy(resume = Some(sessionId))
  def withModel(model: String): SessionOptions = copy(model = Some(model))
  def withMaxThinkingTokens(tokens: Int): SessionOptions =
    copy(maxThinkingTokens = Some(tokens))
  def withTimeout(
      timeout: scala.concurrent.duration.FiniteDuration
  ): SessionOptions = copy(timeout = Some(timeout))
  def withInheritEnvironment(inherit: Boolean): SessionOptions =
    copy(inheritEnvironment = Some(inherit))
  def withEnvironmentVariables(vars: Map[String, String]): SessionOptions =
    copy(environmentVariables = Some(vars))
  def withStrictMcpConfig(flag: Boolean): SessionOptions =
    copy(strictMcpConfig = Some(flag))
  def withMcpConfigPath(path: String): SessionOptions =
    copy(mcpConfigPath = Some(path))
  def withSettingSources(sources: List[String]): SessionOptions =
    copy(settingSources = sources)

object SessionOptions:
  /** Create SessionOptions with all defaults */
  val defaults: SessionOptions = SessionOptions()
