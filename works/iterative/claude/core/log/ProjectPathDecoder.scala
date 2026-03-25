// PURPOSE: Pure utility for decoding Claude project directory names to filesystem path strings
// PURPOSE: Claude stores project logs under ~/.claude/projects/ with paths encoded by replacing / with -

package works.iterative.claude.core.log

object ProjectPathDecoder:

  /** Decodes a Claude project directory name to a best-effort filesystem path.
    *
    * Claude encodes project paths by replacing each `/` separator with `-`.
    * This reverses the encoding by replacing every `-` with `/`. The result is
    * ambiguous when original path segments contained `-` characters; callers
    * should validate against the filesystem if accuracy is required.
    *
    * Examples:
    *   - "-home-mph-Devel" → "/home/mph/Devel"
    *   - "-" → "/"
    *   - "" → ""
    */
  def decode(dirName: String): String =
    if dirName.isEmpty then ""
    else dirName.replace('-', '/')
