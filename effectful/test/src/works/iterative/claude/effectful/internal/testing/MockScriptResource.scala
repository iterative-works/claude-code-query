// PURPOSE: Utility for resolving mock CLI scripts from classpath resources
// PURPOSE: Extracts executable scripts from test resources to a temp directory for test execution

package works.iterative.claude.effectful.internal.testing

import java.nio.file.{Files, Path, StandardCopyOption}
import java.nio.file.attribute.PosixFilePermissions
import scala.util.Using

/** Resolves mock CLI scripts from classpath resources to executable temp files.
  *
  * Mock scripts are stored as test resources and need to be extracted to the
  * filesystem before they can be executed.
  */
object MockScriptResource:

  private lazy val tempDir: Path =
    val dir = Files.createTempDirectory("mock-claude-resources-")
    dir.toFile.deleteOnExit()
    dir

  /** Returns the absolute path to a mock script resource extracted from
    * classpath.
    *
    * @param resourceName
    *   the script name under /bin/ (e.g. "mock-claude")
    * @return
    *   absolute path to an executable copy of the script
    */
  def path(resourceName: String): String =
    val resourcePath = s"/bin/$resourceName"
    val resourceStream = getClass.getResourceAsStream(resourcePath)
    require(
      resourceStream != null,
      s"Mock script resource not found on classpath: $resourcePath"
    )

    val dest = tempDir.resolve(resourceName)
    Using(resourceStream)(stream =>
      Files.copy(stream, dest, StandardCopyOption.REPLACE_EXISTING)
    ).get
    Files.setPosixFilePermissions(
      dest,
      PosixFilePermissions.fromString("rwxr-xr-x")
    )

    dest.toAbsolutePath.toString
