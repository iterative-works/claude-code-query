// PURPOSE: Configuration locating a session's vendor record tree and its archive mirror target
// PURPOSE: Holds the vendor projects directory (CLAUDE_CONFIG_DIR convention), archive dir, and cwd

package works.iterative.claude.core.log

/** Configuration for [[ConversationArchive]].
  *
  * @param vendorProjectsDir
  *   the Claude `projects` directory holding per-cwd session trees.
  *   Configurable because `CLAUDE_CONFIG_DIR` relocates it (e.g.
  *   `/home/mph/.claude-iw`, not `~/.claude`). Use [[ArchiveConfig.locating]]
  *   to derive it from a config-dir override and home directory.
  * @param archiveDir
  *   destination root the session tree is mirrored into.
  * @param cwd
  *   the known working directory a session ran under; the record is located by
  *   encoding this cwd, never by decoding a directory name.
  */
case class ArchiveConfig(
    vendorProjectsDir: os.Path,
    archiveDir: os.Path,
    cwd: os.Path
)

object ArchiveConfig:

  /** Builds a config whose `vendorProjectsDir` is derived from
    * `CLAUDE_CONFIG_DIR` semantics via [[ClaudeProjects.baseDir]].
    *
    * @param configDirOverride
    *   resolved value of `CLAUDE_CONFIG_DIR`, `None` if unset or empty
    * @param home
    *   the user's home directory (use `os.home` in production code)
    */
  def locating(
      configDirOverride: Option[os.Path],
      home: os.Path,
      archiveDir: os.Path,
      cwd: os.Path
  ): ArchiveConfig =
    ArchiveConfig(
      ClaudeProjects.baseDir(configDirOverride, home),
      archiveDir,
      cwd
    )
