package works.iterative.claude

import cats.effect.IO
import fs2.Stream

case class QueryOptions(
  prompt: String,
  cwd: Option[String] = None,
  executable: Option[String] = None,
  executableArgs: Option[List[String]] = None,
  pathToClaudeCodeExecutable: Option[String] = None,
  maxTurns: Option[Int] = None,
  allowedTools: Option[List[String]] = None,
  disallowedTools: Option[List[String]] = None,
  systemPrompt: Option[String] = None,
  appendSystemPrompt: Option[String] = None,
  mcpTools: Option[List[String]] = None,
  permissionMode: Option[PermissionMode] = None,
  continueConversation: Option[Boolean] = None,
  resume: Option[String] = None,
  model: Option[String] = None,
  maxThinkingTokens: Option[Int] = None
)

trait ClaudeCode:
  def query(options: QueryOptions): Stream[IO, Message]