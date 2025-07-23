package works.iterative.claude.effectful.internal.cli

// PURPOSE: File system operations abstraction for CLI discovery testing
// PURPOSE: Enables dependency injection for unit tests with mocked file system

import cats.effect.IO

// File system operations abstraction for testability
trait FileSystemOps:
  def which(command: String): IO[Option[String]]
  def exists(path: String): IO[Boolean]
  def isExecutable(path: String): IO[Boolean]

// Default implementation using real system calls
object RealFileSystemOps extends FileSystemOps:
  def which(command: String): IO[Option[String]] =
    IO:
      try
        val result = os.proc("which", command).call(check = false)
        if result.exitCode == 0 then Some(result.out.trim())
        else None
      catch case _: Exception => None

  def exists(path: String): IO[Boolean] =
    IO:
      try os.exists(os.Path(path))
      catch case _: Exception => false

  def isExecutable(path: String): IO[Boolean] =
    IO:
      try
        val path_ = os.Path(path)
        // Check if file exists, is a file, and has execute permissions
        os.exists(path_) && os.isFile(path_) && os.isExecutable(path_)
      catch case _: Exception => false
