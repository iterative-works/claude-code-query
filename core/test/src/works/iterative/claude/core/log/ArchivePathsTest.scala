// PURPOSE: Tests that archive path resolution encodes a known cwd and never decodes a directory name
// PURPOSE: Pins the encode-only rule, including a cwd whose segments already contain dashes

package works.iterative.claude.core.log

import munit.FunSuite
import works.iterative.claude.core.log.model.RecordRoot
import works.iterative.claude.core.log.model.SessionRecord
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

  test("archiveProjectDir mirrors the vendor layout under the archive root"):
    // The archive is projects-dir-shaped: the same encoded-cwd segment the
    // vendor tree uses hangs off archiveDir, so read fallback shares the layout.
    val c = config(os.Path("/home/mph/proj"))
    assertEquals(
      ArchivePaths.archiveProjectDir(c),
      os.Path("/archive/-home-mph-proj")
    )

  test("candidates resolve vendor first, then the archive, in that order"):
    val c = config(os.Path("/home/mph/proj"))
    assertEquals(
      ArchivePaths.candidates(c, SessionId("sess-1")),
      Right(
        Seq(
          SessionRecord(
            SessionId("sess-1"),
            os.Path("/vendor/projects/-home-mph-proj/sess-1.jsonl"),
            os.Path("/vendor/projects/-home-mph-proj/sess-1"),
            RecordRoot.Vendor
          ),
          SessionRecord(
            SessionId("sess-1"),
            os.Path("/archive/-home-mph-proj/sess-1.jsonl"),
            os.Path("/archive/-home-mph-proj/sess-1"),
            RecordRoot.Archive
          )
        )
      )
    )

  test("candidates reject a traversing id rather than deriving any path"):
    val c = config(os.Path("/home/mph/proj"))
    for id <- List("../x", "a/b", "..", "", "a\\b", "/etc/passwd") do
      assertEquals(
        ArchivePaths.candidates(c, SessionId(id)),
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
