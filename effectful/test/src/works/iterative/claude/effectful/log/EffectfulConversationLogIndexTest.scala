// PURPOSE: Integration tests for EffectfulConversationLogIndex using real temp directories
// PURPOSE: Verifies listSessions and forSession with cats-effect IO assertions

package works.iterative.claude.effectful.log

import munit.CatsEffectSuite
import cats.effect.IO
import works.iterative.claude.core.log.model.LogFileMetadata
import works.iterative.claude.core.log.model.SubAgentMetadata

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

  // listSubAgents tests

  private def withSubAgentsDir(
      sessionId: String,
      agentFiles: List[(String, Option[String])]
  )(body: os.Path => IO[Unit]): IO[Unit] =
    IO(os.temp.dir()).flatMap: tmpRoot =>
      val projectDir = tmpRoot / "-home-mph-test-project"
      val subagentsDir = projectDir / sessionId / "subagents"
      IO(os.makeDir.all(projectDir)) >>
        IO(os.write(projectDir / s"$sessionId.jsonl", "")) >>
        IO(if agentFiles.nonEmpty then os.makeDir.all(subagentsDir)) >>
        IO:
          agentFiles.foreach:
            case (agentId, Some(metaJson)) =>
              os.write(subagentsDir / s"agent-$agentId.jsonl", "")
              os.write(subagentsDir / s"agent-$agentId.meta.json", metaJson)
            case (agentId, None) =>
              os.write(subagentsDir / s"agent-$agentId.jsonl", "")
        >> body(projectDir).guarantee(IO(os.remove.all(tmpRoot)))

  private def validMetaJson(
      agentId: String,
      agentType: Option[String] = None,
      description: Option[String] = None
  ): String =
    val typeField =
      agentType.map(t => s""","agentType":"$t"""").getOrElse("")
    val descField =
      description.map(d => s""","description":"$d"""").getOrElse("")
    s"""{"agentId":"$agentId"$typeField$descField}"""

  test("listSubAgents returns empty Seq when subagents directory does not exist"):
    withSubAgentsDir("sess-1", List.empty): projectDir =>
      index
        .listSubAgents(projectDir, "sess-1")
        .map: result =>
          assertEquals(result, Seq.empty[SubAgentMetadata])

  test("listSubAgents returns empty Seq when subagents directory is empty"):
    IO(os.temp.dir()).flatMap: tmpRoot =>
      val projectDir = tmpRoot / "-home-mph-test-project"
      IO(os.makeDir.all(projectDir / "sess-1" / "subagents")) >>
        index
          .listSubAgents(projectDir, "sess-1")
          .map: result =>
            assertEquals(result, Seq.empty[SubAgentMetadata])
          .guarantee(IO(os.remove.all(tmpRoot)))

  test("listSubAgents discovers sub-agent with valid .meta.json"):
    withSubAgentsDir(
      "sess-2",
      List(("abc", Some(validMetaJson("abc"))))
    ): projectDir =>
      index
        .listSubAgents(projectDir, "sess-2")
        .map: result =>
          assertEquals(result.length, 1)
          assertEquals(result.head.agentId, "abc")

  test("listSubAgents populates all metadata fields from .meta.json"):
    withSubAgentsDir(
      "sess-3",
      List(
        (
          "abc",
          Some(
            validMetaJson(
              "abc",
              agentType = Some("researcher"),
              description = Some("Does research")
            )
          )
        )
      )
    ): projectDir =>
      index
        .listSubAgents(projectDir, "sess-3")
        .map: result =>
          assertEquals(result.length, 1)
          val meta = result.head
          assertEquals(meta.agentId, "abc")
          assertEquals(meta.agentType, Some("researcher"))
          assertEquals(meta.description, Some("Does research"))

  test("listSubAgents sets transcriptPath to the .jsonl file path"):
    withSubAgentsDir(
      "sess-4",
      List(("abc", Some(validMetaJson("abc"))))
    ): projectDir =>
      index
        .listSubAgents(projectDir, "sess-4")
        .map: result =>
          assertEquals(result.length, 1)
          assertEquals(
            result.head.transcriptPath,
            projectDir / "sess-4" / "subagents" / "agent-abc.jsonl"
          )

  test("listSubAgents skips sub-agent when .meta.json is missing"):
    withSubAgentsDir("sess-5", List(("abc", None))): projectDir =>
      index
        .listSubAgents(projectDir, "sess-5")
        .map: result =>
          assertEquals(result, Seq.empty[SubAgentMetadata])

  test("listSubAgents skips sub-agent when .meta.json is malformed"):
    IO(os.temp.dir()).flatMap: tmpRoot =>
      val projectDir = tmpRoot / "-home-mph-test-project"
      val subagentsDir = projectDir / "sess-6" / "subagents"
      IO(os.makeDir.all(subagentsDir)) >>
        IO(os.write(subagentsDir / "agent-abc.jsonl", "")) >>
        IO(os.write(subagentsDir / "agent-abc.meta.json", "NOT VALID JSON {{{")) >>
        index
          .listSubAgents(projectDir, "sess-6")
          .map: result =>
            assertEquals(result, Seq.empty[SubAgentMetadata])
          .guarantee(IO(os.remove.all(tmpRoot)))

  test("listSubAgents discovers multiple sub-agents"):
    withSubAgentsDir(
      "sess-7",
      List(
        ("aaa", Some(validMetaJson("aaa"))),
        ("bbb", Some(validMetaJson("bbb"))),
        ("ccc", Some(validMetaJson("ccc")))
      )
    ): projectDir =>
      index
        .listSubAgents(projectDir, "sess-7")
        .map: result =>
          assertEquals(result.length, 3)
          assertEquals(result.map(_.agentId).toSet, Set("aaa", "bbb", "ccc"))

  test("listSubAgents ignores non-agent files in subagents directory"):
    IO(os.temp.dir()).flatMap: tmpRoot =>
      val projectDir = tmpRoot / "-home-mph-test-project"
      val subagentsDir = projectDir / "sess-8" / "subagents"
      IO(os.makeDir.all(subagentsDir)) >>
        IO(os.write(subagentsDir / "agent-abc.jsonl", "")) >>
        IO(os.write(subagentsDir / "agent-abc.meta.json", validMetaJson("abc"))) >>
        IO(os.write(subagentsDir / "notes.txt", "irrelevant")) >>
        index
          .listSubAgents(projectDir, "sess-8")
          .map: result =>
            assertEquals(result.length, 1)
            assertEquals(result.head.agentId, "abc")
          .guarantee(IO(os.remove.all(tmpRoot)))
