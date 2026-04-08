// PURPOSE: Integration tests for EffectfulConversationLogIndex using real temp directories
// PURPOSE: Verifies listSessions and forSession with cats-effect IO assertions

package works.iterative.claude.effectful.log

import munit.CatsEffectSuite
import cats.effect.IO
import works.iterative.claude.core.log.model.LogFileMetadata

class EffectfulConversationLogIndexTest extends CatsEffectSuite:

  private val index = EffectfulConversationLogIndex.make(None, os.home)

  private def withProjectDir(
      dirName: String,
      sessionIds: List[String]
  )(body: os.Path => IO[Unit]): IO[Unit] =
    IO(os.temp.dir()).flatMap: tmpRoot =>
      val projectDir = tmpRoot / dirName
      IO(os.makeDir.all(projectDir)) >>
        IO(
          sessionIds.foreach(sid => os.write(projectDir / s"$sid.jsonl", ""))
        ) >>
        body(projectDir).guarantee(IO(os.remove.all(tmpRoot)))

  test("listSessions returns empty Seq for empty project directory"):
    withProjectDir("-home-mph-test-project", List.empty): projectDir =>
      index
        .listSessions(projectDir)
        .map: result =>
          assertEquals(result.toList, List.empty[LogFileMetadata])

  test("listSessions finds all .jsonl files as sessions"):
    val sessionIds = List("session-aaa", "session-bbb", "session-ccc")
    withProjectDir("-home-mph-test-project", sessionIds): projectDir =>
      index
        .listSessions(projectDir)
        .map: result =>
          assertEquals(result.length, 3)
          val found = result.map(_.sessionId).toSet
          assertEquals(found, sessionIds.toSet)

  test("listSessions populates sessionId from filename without extension"):
    withProjectDir("-home-mph-test-project", List("abc-def-123")): projectDir =>
      index
        .listSessions(projectDir)
        .map: result =>
          assertEquals(result.head.sessionId, "abc-def-123")

  test("listSessions decodes cwd from parent directory name"):
    withProjectDir("-home-mph-Devel-myproject", List("session-x")):
      projectDir =>
        index
          .listSessions(projectDir)
          .map: result =>
            assertEquals(result.head.cwd, Some("/home/mph/Devel/myproject"))

  test("listSessions sets summary, gitBranch, createdAt to None"):
    withProjectDir("-home-mph-test", List("session-z")): projectDir =>
      index
        .listSessions(projectDir)
        .map: result =>
          assertEquals(result.head.summary, None)
          assertEquals(result.head.gitBranch, None)
          assertEquals(result.head.createdAt, None)

  test("listSessions ignores non-.jsonl files"):
    IO(os.temp.dir()).flatMap: tmpRoot =>
      val projectDir = tmpRoot / "-home-mph-proj"
      IO(os.makeDir.all(projectDir)) >>
        IO(os.write(projectDir / "session-good.jsonl", "")) >>
        IO(os.write(projectDir / "notes.txt", "")) >>
        index
          .listSessions(projectDir)
          .map: result =>
            assertEquals(result.length, 1)
            assertEquals(result.head.sessionId, "session-good")
          .guarantee(IO(os.remove.all(tmpRoot)))

  test("forSession returns Some(metadata) when session exists"):
    withProjectDir("-home-mph-test", List("session-find-me")): projectDir =>
      index
        .forSession(projectDir, "session-find-me")
        .map: result =>
          assert(
            result.isDefined,
            "Expected Some(LogFileMetadata) for existing session"
          )
          assertEquals(result.get.sessionId, "session-find-me")

  test("forSession returns None when session does not exist"):
    withProjectDir("-home-mph-test", List("other-session")): projectDir =>
      index
        .forSession(projectDir, "nonexistent-session")
        .map: result =>
          assertEquals(result, None)

  test("forSession returns metadata with correct path"):
    withProjectDir("-home-mph-test", List("target-session")): projectDir =>
      index
        .forSession(projectDir, "target-session")
        .map:
          case Some(meta) =>
            assertEquals(meta.path, projectDir / "target-session.jsonl")
          case None => fail("Expected Some(LogFileMetadata)")
