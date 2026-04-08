// PURPOSE: Tests for cwd-based convenience methods on EffectfulConversationLogIndex
// PURPOSE: Verifies listSessionsFor and forSessionAt using temp dir fixtures and the test seam

package works.iterative.claude.effectful.log

import munit.CatsEffectSuite
import cats.effect.IO
import works.iterative.claude.core.log.ClaudeProjects

class EffectfulConversationLogIndexCwdTest extends CatsEffectSuite:

  private val cwd = os.Path("/a/b")

  private def withCwdFixture(
      configDirOverride: Option[os.Path],
      home: os.Path,
      sessionIds: List[String]
  )(body: (EffectfulConversationLogIndex, os.Path) => IO[Unit]): IO[Unit] =
    val base = ClaudeProjects.projectDirFor(cwd, configDirOverride, home)
    IO(os.makeDir.all(base)) >>
      IO(sessionIds.foreach(sid => os.write(base / s"$sid.jsonl", ""))).flatMap: _ =>
        val index = EffectfulConversationLogIndex.make(configDirOverride, home)
        body(index, base).guarantee:
          IO(os.remove.all(configDirOverride.getOrElse(home / ".claude")))

  test("listSessionsFor returns same session IDs as path-based listSessions"):
    withCwdFixture(Some(os.temp.dir()), os.Path("/tmp/unused"), List("sess-1", "sess-2")):
      (index, projectDir) =>
        for
          byCwd  <- index.listSessionsFor(cwd)
          byPath <- index.listSessions(projectDir)
        yield assertEquals(
          byCwd.map(_.sessionId).toSet,
          byPath.map(_.sessionId).toSet
        )

  test("forSessionAt returns same Option as path-based forSession"):
    withCwdFixture(Some(os.temp.dir()), os.Path("/tmp/unused"), List("target-sess")):
      (index, projectDir) =>
        for
          byCwd  <- index.forSessionAt(cwd, "target-sess")
          byPath <- index.forSession(projectDir, "target-sess")
        yield assertEquals(byCwd.map(_.sessionId), byPath.map(_.sessionId))

  test("forSessionAt returns None for missing session"):
    withCwdFixture(Some(os.temp.dir()), os.Path("/tmp/unused"), List("other-sess")):
      (index, _) =>
        index.forSessionAt(cwd, "nonexistent").map: result =>
          assertEquals(result, None)

  test("configDirOverride = None falls back to home / .claude / projects"):
    IO(os.temp.dir()).flatMap: tmpHome =>
      withCwdFixture(None, tmpHome, List("fallback-sess")):
        (index, _) =>
          index.listSessionsFor(cwd).map: result =>
            assertEquals(result.map(_.sessionId).toSet, Set("fallback-sess"))
      .guarantee(IO(os.remove.all(tmpHome)))

  test("production apply() returns a working index (smoke test)"):
    EffectfulConversationLogIndex.apply().flatMap: index =>
      index.listSessionsFor(os.Path("/nonexistent-smoke-test-path")).map: result =>
        assertEquals(result, Seq.empty)
