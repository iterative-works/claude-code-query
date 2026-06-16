// PURPOSE: File system operations abstraction for CLI discovery testing
// PURPOSE: Enables dependency injection for unit tests with a stubbed file system

package works.iterative.claude.zio.internal.cli

import zio.*

// File system operations abstraction for testability
trait FileSystemOps:
  def which(command: String): UIO[Option[String]]
  def exists(path: String): UIO[Boolean]
  def isExecutable(path: String): UIO[Boolean]

// Default implementation using real system calls
object RealFileSystemOps extends FileSystemOps:
  def which(command: String): UIO[Option[String]] =
    ZIO
      .attemptBlocking:
        val result = os.proc("which", command).call(check = false)
        if result.exitCode == 0 then Some(result.out.trim())
        else None
      .orElseSucceed(None)

  def exists(path: String): UIO[Boolean] =
    ZIO.attemptBlocking(os.exists(os.Path(path))).orElseSucceed(false)

  def isExecutable(path: String): UIO[Boolean] =
    ZIO
      .attemptBlocking:
        val path_ = os.Path(path)
        os.exists(path_) && os.isFile(path_) && os.isExecutable(path_)
      .orElseSucceed(false)
