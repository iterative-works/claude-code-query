// PURPOSE: Tests that archive path resolution encodes a known cwd and never decodes a directory name
// PURPOSE: Pins the encode-only rule, including a cwd whose segments already contain dashes

package works.iterative.claude.core.log

import munit.FunSuite
import works.iterative.claude.core.model.SessionId

class ArchivePathsTest extends FunSuite:
  private def config(cwd: os.Path): ArchiveConfig =
    ArchiveConfig(
      vendorProjectsDir = os.Path("/vendor/projects"),
      archiveDir = os.Path("/archive"),
      cwd = cwd
    )

  test("projectDir encodes the cwd by replacing separators with dashes"):
    val c = config(os.Path("/home/mph/proj"))
    assertEquals(
      ArchivePaths.projectDir(c),
      os.Path("/vendor/projects/-home-mph-proj")
    )

  test("a cwd containing dashes resolves to its own encoding, never decoded"):
    // /home/a-b/proj must encode to -home-a-b-proj; a decoder would ambiguously
    // read that name back as /home/a/b/proj, which is a different directory.
    val c = config(os.Path("/home/a-b/proj"))
    assertEquals(
      ArchivePaths.projectDir(c),
      os.Path("/vendor/projects/-home-a-b-proj")
    )

  test("mainTranscript and treeDir hang off the encoded project directory"):
    val c = config(os.Path("/home/mph/proj"))
    assertEquals(
      ArchivePaths.mainTranscript(c, SessionId("sess-1")),
      Right(os.Path("/vendor/projects/-home-mph-proj/sess-1.jsonl"))
    )
    assertEquals(
      ArchivePaths.treeDir(c, SessionId("sess-1")),
      Right(os.Path("/vendor/projects/-home-mph-proj/sess-1"))
    )

  test("mainTranscript and treeDir reject a traversing id rather than deriving a path"):
    val c = config(os.Path("/home/mph/proj"))
    for id <- List("../x", "a/b", "..", "", "a\\b", "/etc/passwd") do
      assertEquals(
        ArchivePaths.mainTranscript(c, SessionId(id)),
        Left(ArchiveError.InvalidSessionId(id))
      )
      assertEquals(
        ArchivePaths.treeDir(c, SessionId(id)),
        Left(ArchiveError.InvalidSessionId(id))
      )

  test("locating derives vendorProjectsDir from CLAUDE_CONFIG_DIR override"):
    val c = ArchiveConfig.locating(
      configDirOverride = Some(os.Path("/cfg/.claude-iw")),
      home = os.Path("/home/mph"),
      archiveDir = os.Path("/archive"),
      cwd = os.Path("/home/mph/proj")
    )
    assertEquals(
      ArchivePaths.projectDir(c),
      os.Path("/cfg/.claude-iw/projects/-home-mph-proj")
    )
