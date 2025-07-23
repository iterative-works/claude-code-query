// PURPOSE: Direct-style file system operations for CLI discovery and file validation
// PURPOSE: Provides path resolution and file existence checking without IO wrapper
package works.iterative.claude.direct.internal.cli

object FileSystemOps:
  def which(command: String): Option[String] = ???

  def exists(path: String): Boolean = ???

  def isExecutable(path: String): Boolean = ???
