package works.iterative.claude.core.model

// PURPOSE: Permission mode configuration for Claude Code CLI tool permissions
// PURPOSE: Controls how tool permission prompts are handled during execution

// Permission modes
enum PermissionMode:
  case Default
  case AcceptEdits
  case BypassPermissions
