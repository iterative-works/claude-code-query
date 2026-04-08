// PURPOSE: Pure utility for encoding filesystem paths to Claude project directory names
// PURPOSE: Converts absolute paths to dash-encoded names matching Claude's storage convention

package works.iterative.claude.core.log

object ProjectPathEncoder:

  /** Encodes an absolute filesystem path to Claude's project directory name
    * format.
    *
    * Claude stores conversation logs in a projects directory, using a
    * path-encoded name for each project subdirectory. The encoding replaces
    * every `/` separator with `-`, so `/home/user/project` becomes
    * `-home-user-project`.
    *
    * Note: this encoding is lossy when original path segments contain `-`
    * characters. The symmetric decoder (`ProjectPathDecoder.decode`) cannot
    * unambiguously recover those paths.
    *
    * Examples:
    *   - `os.Path("/home/mph/ops/kanon")` → `"-home-mph-ops-kanon"`
    *   - `os.Path("/")` → `"-"`
    *   - `os.Path("/a-b/c")` → `"-a-b-c"`
    *
    * @param path
    *   the absolute filesystem path to encode
    */
  def encode(path: os.Path): String =
    path.toString.replace('/', '-')
