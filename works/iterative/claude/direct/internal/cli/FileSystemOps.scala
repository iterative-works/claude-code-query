// PURPOSE: Direct-style file system operations for CLI discovery and file validation
// PURPOSE: Provides path resolution and file existence checking without IO wrapper
package works.iterative.claude.direct.internal.cli

object FileSystemOps:
  def which(command: String): Option[String] =
    try
      val result = os.proc("which", command).call(check = false)
      if result.exitCode == 0 then Some(result.out.trim())
      else None
    catch case _: Exception => None

  def exists(path: String): Boolean = ???

  def isExecutable(path: String): Boolean = ???
