// PURPOSE: End-to-end test locating and mirroring a real CLI session's transcript via the archive
// PURPOSE: Runs only when ANTHROPIC_API_KEY is set so it is skipped without credentials

package works.iterative.claude.zio

import zio.*
import zio.test.*
import works.iterative.claude.core.log.ArchiveConfig
import works.iterative.claude.core.log.ArchivePaths
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec
import works.iterative.claude.zio.log.ZioConversationArchive

object ConversationArchiveE2ETest extends ClaudeZioSpec:

  private def sessionIdOf(messages: Seq[Message]): Option[SessionId] =
    messages.collectFirst { case r: ResultMessage => r.sessionId }

  def spec = suite("ConversationArchive (e2e, real CLI)")(
    test("locates, reads, and mirrors a real one-shot query's transcript tree"):
      for
        configDir <- ZIO.attempt(os.temp.dir())
        workDir   <- ZIO.attempt(os.temp.dir())
        archiveDir <- ZIO.attempt(os.temp.dir())
        options = QueryOptions
                    .simple("Reply with exactly one word: pong")
                    .withCwd(workDir.toString)
                    .withEnvironmentVariables(
                      Map("CLAUDE_CONFIG_DIR" -> configDir.toString)
                    )
        messages  <- ClaudeCode.querySync(options)
        sessionId <- ZIO
                       .fromOption(sessionIdOf(messages))
                       .orElseFail(
                         new RuntimeException("no session id in CLI result")
                       )
        config  = ArchiveConfig(configDir / "projects", archiveDir, workDir)
        archive = ZioConversationArchive(config)
        located  <- archive.forSession(sessionId)
        entries  <- archive.entries(sessionId).runCollect
        tailPage <- archive.lastEntries(sessionId, 5)
        report   <- archive.mirror(sessionId)
        projectDir = ArchivePaths.projectDir(config)
        mirrorDir  = ArchivePaths.archiveProjectDir(config)
        identical = os.walk(projectDir)
                      .filter(os.isFile)
                      .forall: source =>
                        val rel  = source.subRelativeTo(projectDir)
                        val dest = mirrorDir / rel
                        os.exists(dest) && java.util.Arrays.equals(
                          os.read.bytes(source),
                          os.read.bytes(dest)
                        )
      yield assertTrue(
        located.exists(_.sessionId == sessionId),
        entries.nonEmpty,
        // The tail is the last entries of what the full read returns.
        tailPage.entries.nonEmpty,
        tailPage.entries.map(_.uuid).toList ==
          entries.takeRight(tailPage.entries.size).map(_.uuid).toList,
        report.copied.nonEmpty,
        identical
      )
  ) @@ TestAspect.withLiveClock
    @@ TestAspect.timeout(Duration.fromSeconds(120))
    @@ TestAspect.ifEnvSet("ANTHROPIC_API_KEY")
