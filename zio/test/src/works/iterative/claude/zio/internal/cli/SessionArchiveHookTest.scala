// PURPOSE: Unit tests for the best-effort session archive hook — it fires a mirror and never fails
// PURPOSE: A mirror failure is swallowed and observable only as a logged warning, so it cannot break a session

package works.iterative.claude.zio.internal.cli

import zio.*
import zio.test.*
import works.iterative.claude.core.log.ArchiveConfig
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object SessionArchiveHookTest extends ClaudeZioSpec:

  private val sessionId = "0d43043b-aaaa-bbbb-cccc-dddddddddddd"
  private val cwd       = os.Path("/home/tester/proj")
  private val mainLine  =
    s"""{"type":"user","sessionId":"$sessionId","uuid":"u1","message":{"content":"hi"}}"""

  /** A vendor tree with a single main transcript, plus an empty archive dir. */
  private def fixture: UIO[ArchiveConfig] =
    ZIO.succeed:
      val vendorProjectsDir = os.temp.dir()
      val archiveDir        = os.temp.dir()
      os.write(
        vendorProjectsDir / "-home-tester-proj" / s"$sessionId.jsonl",
        mainLine + "\n",
        createFolders = true
      )
      ArchiveConfig(vendorProjectsDir, archiveDir, cwd)

  def spec = suite("SessionArchiveHook")(
    test("afterResult mirrors the vendor tree into the archive directory"):
      for
        config <- fixture
        _      <- SessionArchiveHook.mirroring(config).afterResult(sessionId)
        copied <- ZIO.attemptBlocking(
                    os.exists(config.archiveDir / s"$sessionId.jsonl")
                  ).orDie
      yield assertTrue(copied),
    test("afterResult never fails and logs a warning when the tree is missing"):
      for
        config <- fixture.map(_.copy(cwd = os.Path("/no/such/place")))
        exit   <- SessionArchiveHook.mirroring(config).afterResult(sessionId).exit
        logs   <- ZTestLogger.logOutput
      yield assertTrue(
        exit == Exit.unit,
        logs.exists(_.message().contains("archive mirror failed"))
      )
  ) @@ TestAspect.withLiveClock
