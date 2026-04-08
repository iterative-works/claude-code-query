// PURPOSE: Tests for cwd-based convenience methods on DirectConversationLogIndex
// PURPOSE: Verifies listSessionsFor and forSessionAt using temp dir fixtures and the test seam

package works.iterative.claude.direct.log

import munit.FunSuite
import works.iterative.claude.core.log.ClaudeProjects

class DirectConversationLogIndexCwdTest extends FunSuite:

  private val cwd = os.Path("/a/b")

  private def withCwdFixture(
      configDirOverride: Option[os.Path],
      home: os.Path,
      sessionIds: List[String]
  )(body: (DirectConversationLogIndex, os.Path) => Unit): Unit =
    val base = ClaudeProjects.projectDirFor(cwd, configDirOverride, home)
    try
      os.makeDir.all(base)
      sessionIds.foreach(sid => os.write(base / s"$sid.jsonl", ""))
      val index = DirectConversationLogIndex(configDirOverride, home)
      body(index, base)
    finally os.remove.all(configDirOverride.getOrElse(home / ".claude"))

  test("listSessionsFor returns same metadata as path-based listSessions"):
    withCwdFixture(Some(os.temp.dir()), os.Path("/tmp/unused"), List("sess-1", "sess-2")):
      (index, projectDir) =>
        val byCwd  = index.listSessionsFor(cwd)
        val byPath = index.listSessions(projectDir)
        assertEquals(byCwd.map(_.sessionId).toSet, byPath.map(_.sessionId).toSet)

  test("forSessionAt returns same Option as path-based forSession"):
    withCwdFixture(Some(os.temp.dir()), os.Path("/tmp/unused"), List("target-sess")):
      (index, projectDir) =>
        val byCwd  = index.forSessionAt(cwd, "target-sess")
        val byPath = index.forSession(projectDir, "target-sess")
        assertEquals(byCwd.map(_.sessionId), byPath.map(_.sessionId))

  test("forSessionAt returns None for missing session"):
    withCwdFixture(Some(os.temp.dir()), os.Path("/tmp/unused"), List("other-sess")):
      (index, _) =>
        assertEquals(index.forSessionAt(cwd, "nonexistent"), None)

  test("configDirOverride = None falls back to home / .claude / projects"):
    val tmpHome = os.temp.dir()
    try
      withCwdFixture(None, tmpHome, List("fallback-sess")):
        (index, _) =>
          val result = index.listSessionsFor(cwd)
          assertEquals(result.map(_.sessionId).toSet, Set("fallback-sess"))
    finally os.remove.all(tmpHome)

  test("no-arg apply() returns a working index (smoke test)"):
    val index = DirectConversationLogIndex()
    val result = index.listSessionsFor(os.Path("/nonexistent-smoke-test-path"))
    assertEquals(result, Seq.empty)
