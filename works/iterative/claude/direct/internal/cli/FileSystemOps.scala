// PURPOSE: Direct-style file system operations for CLI discovery and file validation
// PURPOSE: Provides path resolution and file existence checking without IO wrapper
package works.iterative.claude.direct.internal.cli

object FileSystemOps:

  // == Command Discovery Operations ==

  def which(command: String): Option[String] =
    executeWhichCommand(command).flatMap(processWhichResult)

  private def executeWhichCommand(command: String): Option[os.CommandResult] =
    safeExecute(() => os.proc("which", command).call(check = false))

  private def processWhichResult(result: os.CommandResult): Option[String] =
    if result.exitCode == 0 then Some(result.out.trim())
    else None

  // == File System Validation Operations ==

  def exists(path: String): Boolean =
    safePathOperation(() => os.exists(pathFromString(path)))

  def isExecutable(path: String): Boolean =
    safePathOperation(() => checkExecutablePermissions(path))

  private def checkExecutablePermissions(path: String): Boolean =
    val osPath = pathFromString(path)
    if fileExistsAndIsRegular(osPath) then hasExecutePermission(osPath)
    else false

  private def fileExistsAndIsRegular(osPath: os.Path): Boolean =
    os.exists(osPath) && os.isFile(osPath)

  private def hasExecutePermission(osPath: os.Path): Boolean =
    val perms = os.perms(osPath)
    perms.toString.contains("x")

  // == Path Utilities ==

  private def pathFromString(pathString: String): os.Path =
    os.Path(pathString, os.pwd)

  // == Error Handling ==

  private def safeExecute[T](operation: () => T): Option[T] =
    try Some(operation())
    catch case _: Exception => None

  private def safePathOperation(operation: () => Boolean): Boolean =
    try operation()
    catch case _: Exception => false
