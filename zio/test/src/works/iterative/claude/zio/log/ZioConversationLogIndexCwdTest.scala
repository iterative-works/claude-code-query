// PURPOSE: Tests for the log index cwd-convenience methods and environment factory
// PURPOSE: Verifies project-directory resolution via CLAUDE_CONFIG_DIR semantics

package works.iterative.claude.zio.log

import zio.*
import zio.test.*
import works.iterative.claude.core.log.ClaudeProjects
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object ZioConversationLogIndexCwdTest extends ClaudeZioSpec:
  private val cwd = os.Path("/a/b")

  // Builds the fixture under a throwaway home so cleanup never touches a real
  // ~/.claude, writing the given sessions into the resolved project directory.
  private def withCwdFixture[A](
      configDirOverride: Option[os.Path],
      sessionIds: List[String]
  )(body: ZioConversationLogIndex => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt:
        val home = os.temp.dir()
        val base = ClaudeProjects.projectDirFor(cwd, configDirOverride, home)
        os.makeDir.all(base)
        sessionIds.foreach(sid => os.write(base / s"$sid.jsonl", "{}"))
        home
    )(home =>
      ZIO
        .attempt:
          os.remove.all(home)
          configDirOverride.foreach(os.remove.all)
        .ignore
    )(home => body(ZioConversationLogIndex.make(configDirOverride, home)))

  def spec = suite("ZioConversationLogIndex (cwd)")(
    test("listSessionsFor resolves the project dir and lists its sessions"):
      withCwdFixture(None, List("s1", "s2")): index =>
        for sessions <- index.listSessionsFor(cwd)
        yield assertTrue(sessions.map(_.sessionId).toSet == Set("s1", "s2")),
    test("forSessionAt finds an existing session and misses an absent one"):
      withCwdFixture(None, List("abc")): index =>
        for
          found   <- index.forSessionAt(cwd, "abc")
          missing <- index.forSessionAt(cwd, "nope")
        yield assertTrue(found.exists(_.sessionId == "abc"), missing.isEmpty),
    test("listSessionsFor honors an explicit configDirOverride"):
      for
        overrideDir <- ZIO.attempt(os.temp.dir())
        result      <- withCwdFixture(Some(overrideDir), List("only")):
                         index => index.listSessionsFor(cwd)
      yield assertTrue(result.map(_.sessionId) == Seq("only")),
    test("apply builds an index from the environment without failing"):
      for _ <- ZioConversationLogIndex.apply()
      yield assertCompletes
  )
