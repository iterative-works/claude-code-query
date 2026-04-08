// PURPOSE: Pure utility for resolving the Claude projects base directory and per-project paths
// PURPOSE: Encapsulates CLAUDE_CONFIG_DIR override semantics and path encoding convention

package works.iterative.claude.core.log

object ClaudeProjects:

  /** Resolves the `CLAUDE_CONFIG_DIR` override from an env lookup function.
    *
    * Reads `CLAUDE_CONFIG_DIR` via the injected `env` accessor and treats an
    * empty string as unset. No shell `~` expansion is performed on the value.
    *
    * @param env
    *   lookup function that returns `Some(value)` when the variable is set
    */
  def resolveConfigDir(env: String => Option[String]): Option[os.Path] =
    env("CLAUDE_CONFIG_DIR").filter(_.nonEmpty).map(os.Path(_))

  /** Resolves the Claude projects base directory from an optional config
    * override and home dir.
    *
    * Claude stores conversation logs under `~/.claude/projects/` by default.
    * The `CLAUDE_CONFIG_DIR` environment variable, when set to a non-empty
    * value, replaces `~/.claude` entirely — so the projects directory becomes
    * `$CLAUDE_CONFIG_DIR/projects`.
    *
    * Important: empty strings in `configDirOverride` must be filtered out by
    * the caller before constructing an `os.Path` (treat empty string as
    * absent). Shell `~` expansion is not performed on `CLAUDE_CONFIG_DIR`
    * values.
    *
    * @param configDirOverride
    *   resolved value of `CLAUDE_CONFIG_DIR`, `None` if unset or empty
    * @param home
    *   the user's home directory (use `os.home` in production code)
    */
  def baseDir(configDirOverride: Option[os.Path], home: os.Path): os.Path =
    configDirOverride.getOrElse(home / ".claude") / "projects"

  /** Resolves the project-specific subdirectory under the Claude projects base
    * for `cwd`.
    *
    * Encodes `cwd` using [[ProjectPathEncoder.encode]] and appends it to the
    * base directory.
    *
    * @param cwd
    *   the working directory path to encode
    * @param configDirOverride
    *   resolved value of `CLAUDE_CONFIG_DIR`, `None` if unset or empty
    * @param home
    *   the user's home directory (use `os.home` in production code)
    */
  def projectDirFor(
      cwd: os.Path,
      configDirOverride: Option[os.Path],
      home: os.Path
  ): os.Path =
    baseDir(configDirOverride, home) / ProjectPathEncoder.encode(cwd)
