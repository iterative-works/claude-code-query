// PURPOSE: Pure resolution of a session's vendor record paths by encoding a known cwd
// PURPOSE: Locates only in the encode direction; never decodes a directory name back to a cwd

package works.iterative.claude.core.log

object ArchivePaths:

  /** Accepted shape for vendor session and sub-agent ids — UUID- or
    * `agent-<hex>`-shaped tokens. Validating an id against this before deriving
    * any path keeps `..`, `/`, `\`, and absolute paths from ever reaching path
    * construction, so no derived path can escape the project directory.
    */
  val ValidIdPattern: scala.util.matching.Regex = "^[A-Za-z0-9_-]+$".r

  /** Accepts an id only when it matches [[ValidIdPattern]]; otherwise rejects
    * it with [[ArchiveError.InvalidSessionId]] so no path is derived from it.
    * This is the single choke point every id-derived path must pass through.
    */
  def validateId(id: String): Either[ArchiveError, String] =
    Either.cond(
      ValidIdPattern.matches(id),
      id,
      ArchiveError.InvalidSessionId(id)
    )

  /** The project directory holding a cwd's session trees, resolved by encoding
    * `config.cwd`. A cwd containing `-` resolves to its own encoding; the
    * decode direction is never used because it is ambiguous.
    */
  def projectDir(config: ArchiveConfig): os.Path =
    config.vendorProjectsDir / ProjectPathEncoder.encode(config.cwd)

  /** The `<sessionId>.jsonl` main-thread transcript for a session, or a
    * rejection when the id is not an accepted shape.
    */
  def mainTranscript(
      config: ArchiveConfig,
      sessionId: String
  ): Either[ArchiveError, os.Path] =
    validateId(sessionId).map(id => projectDir(config) / s"$id.jsonl")

  /** The `<sessionId>/` directory holding sub-agent and workflow sidechains, or
    * a rejection when the id is not an accepted shape.
    */
  def treeDir(
      config: ArchiveConfig,
      sessionId: String
  ): Either[ArchiveError, os.Path] =
    validateId(sessionId).map(id => projectDir(config) / id)
