// PURPOSE: Pure resolution of a session's vendor record paths by encoding a known cwd
// PURPOSE: Locates only in the encode direction; never decodes a directory name back to a cwd

package works.iterative.claude.core.log

object ArchivePaths:

  /** The project directory holding a cwd's session trees, resolved by encoding
    * `config.cwd`. A cwd containing `-` resolves to its own encoding; the
    * decode direction is never used because it is ambiguous.
    */
  def projectDir(config: ArchiveConfig): os.Path =
    config.vendorProjectsDir / ProjectPathEncoder.encode(config.cwd)

  /** The `<sessionId>.jsonl` main-thread transcript for a session. */
  def mainTranscript(config: ArchiveConfig, sessionId: String): os.Path =
    projectDir(config) / s"$sessionId.jsonl"

  /** The `<sessionId>/` directory holding sub-agent and workflow sidechains. */
  def treeDir(config: ArchiveConfig, sessionId: String): os.Path =
    projectDir(config) / sessionId
