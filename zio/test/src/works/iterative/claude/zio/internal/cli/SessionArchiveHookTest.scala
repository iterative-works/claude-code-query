// PURPOSE: Unit tests for the best-effort session archive hook — it fires a mirror and never fails
// PURPOSE: A mirror failure is swallowed and observable only as a logged warning, so it cannot break a session
//
// These tests build fixtures in a fresh per-test temp directory on purpose — a
// file adapter is what is under test, there is no network, and each test gets an
// isolated dir. This is the sanctioned exception to the no-filesystem-in-unit-
// tests policy.

package works.iterative.claude.zio.internal.cli

import zio.*
import zio.test.*
import works.iterative.claude.core.log.ArchiveConfig
import works.iterative.claude.core.model.SessionId
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object SessionArchiveHookTest extends ClaudeZioSpec:

  private val sessionId = SessionId("0d43043b-aaaa-bbbb-cccc-dddddddddddd")
  private val cwd       = os.Path("/home/tester/proj")
  private val mainLine  =
    s"""{"type":"user","sessionId":"$sessionId","uuid":"u1","message":{"content":"hi"}}"""

  /** A vendor tree with a main transcript and one sub-agent sidechain, plus an
    * empty archive dir.
    */
  private def fixture: UIO[ArchiveConfig] =
    ZIO.succeed:
      val vendorProjectsDir = os.temp.dir()
      val archiveDir        = os.temp.dir()
      val projectDir        = vendorProjectsDir / "-home-tester-proj"
      os.write(
        projectDir / s"$sessionId.jsonl",
        mainLine + "\n",
        createFolders = true
      )
      os.write(
        projectDir / sessionId.value / "subagents" / "agent-abc.jsonl",
        mainLine + "\n",
        createFolders = true
      )
      ArchiveConfig(vendorProjectsDir, archiveDir, cwd)

  def spec = suite("SessionArchiveHook")(
    test("afterResult mirrors the vendor tree into the archive directory"):
      for
        config <- fixture
        // A synchronous background runner makes the scheduled mirror finish
        // before afterResult returns, so no polling is needed.
        hook   <- SessionArchiveHook.mirroring(config, background = identity)
        _      <- hook.afterResult(sessionId)
        copied <- ZIO
                    .attemptBlocking(
                      os.exists(config.archiveDir / s"$sessionId.jsonl")
                    )
                    .orDie
      yield assertTrue(copied),
    test("afterResult logs a per-file warning when some files fail to mirror"):
      for
        config <- fixture
        // A blocker file where the sub-agents directory must go makes the
        // sub-agent copy fail while the run still completes with a report.
        _ <- ZIO
               .attemptBlocking(
                 os.write(
                   config.archiveDir / sessionId.value / "subagents",
                   "blocker",
                   createFolders = true
                 )
               )
               .orDie
        hook <- SessionArchiveHook.mirroring(config, background = identity)
        _    <- hook.afterResult(sessionId)
        logs <- ZTestLogger.logOutput
      yield assertTrue(
        logs.exists(l =>
          l.message().contains("failed file") &&
            l.message().contains("agent-abc.jsonl")
        )
      ),
    test("onClose never fails and logs a warning when the tree is missing"):
      for
        config <- fixture.map(_.copy(cwd = os.Path("/no/such/place")))
        hook   <- SessionArchiveHook.mirroring(config)
        exit   <- hook.onClose(sessionId).exit
        logs   <- ZTestLogger.logOutput
      yield assertTrue(
        exit == Exit.unit,
        logs.exists(_.message().contains("archive mirror failed"))
      )
  ) @@ TestAspect.withLiveClock
