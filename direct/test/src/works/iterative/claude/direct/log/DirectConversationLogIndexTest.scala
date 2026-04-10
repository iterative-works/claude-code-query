// PURPOSE: Integration tests for DirectConversationLogIndex using real temp directories
// PURPOSE: Verifies listSessions and forSession with actual file system operations

package works.iterative.claude.direct.log

import munit.FunSuite
import works.iterative.claude.core.log.model.LogFileMetadata

class DirectConversationLogIndexTest extends FunSuite:

  private val index = DirectConversationLogIndex()

  // Helper to create a temp project directory with .jsonl session files
  private def withProjectDir(
      dirName: String,
      sessionIds: List[String]
  )(body: os.Path => Unit): Unit =
    val tmpRoot = os.temp.dir()
    try
      val projectDir = tmpRoot / dirName
      os.makeDir.all(projectDir)
      sessionIds.foreach: sid =>
        os.write(projectDir / s"$sid.jsonl", "")
      body(projectDir)
    finally os.remove.all(tmpRoot)

  test("listSessions returns empty Seq for empty project directory"):
    withProjectDir("-home-mph-test-project", List.empty): projectDir =>
      val result = index.listSessions(projectDir)
      assertEquals(result, Seq.empty[LogFileMetadata])

  test("listSessions finds all .jsonl files as sessions"):
    val sessionIds = List("session-aaa", "session-bbb", "session-ccc")
    withProjectDir("-home-mph-test-project", sessionIds): projectDir =>
      val result = index.listSessions(projectDir)
      assertEquals(result.length, 3)
      val found = result.map(_.sessionId).toSet
      assertEquals(found, sessionIds.toSet)

  test("listSessions populates path pointing to .jsonl file"):
    withProjectDir("-home-mph-test-project", List("my-session")): projectDir =>
      val result = index.listSessions(projectDir)
      assertEquals(result.length, 1)
      assert(
        result.head.path.last == "my-session.jsonl",
        s"Expected .jsonl file path but got ${result.head.path}"
      )

  test(
    "listSessions populates sessionId from filename without .jsonl extension"
  ):
    withProjectDir("-home-mph-test-project", List("abc-def-123")): projectDir =>
      val result = index.listSessions(projectDir)
      assertEquals(result.head.sessionId, "abc-def-123")

  test("listSessions decodes cwd from parent directory name"):
    withProjectDir("-home-mph-Devel-myproject", List("session-x")):
      projectDir =>
        val result = index.listSessions(projectDir)
        assertEquals(result.head.cwd, Some("/home/mph/Devel/myproject"))

  test("listSessions populates fileSize from stat"):
    withProjectDir("-home-mph-test", List("session-y")): projectDir =>
      val jsonlPath = projectDir / "session-y.jsonl"
      os.write.over(jsonlPath, "some content here")
      val result = index.listSessions(projectDir)
      assert(result.head.fileSize > 0L, "Expected non-zero file size")

  test("listSessions sets summary, gitBranch, createdAt to None"):
    withProjectDir("-home-mph-test", List("session-z")): projectDir =>
      val result = index.listSessions(projectDir)
      assertEquals(result.head.summary, None)
      assertEquals(result.head.gitBranch, None)
      assertEquals(result.head.createdAt, None)

  test("listSessions ignores non-.jsonl files"):
    val tmpRoot = os.temp.dir()
    try
      val projectDir = tmpRoot / "-home-mph-proj"
      os.makeDir.all(projectDir)
      os.write(projectDir / "session-good.jsonl", "")
      os.write(projectDir / "notes.txt", "")
      os.write(projectDir / "data.json", "")
      val result = index.listSessions(projectDir)
      assertEquals(result.length, 1)
      assertEquals(result.head.sessionId, "session-good")
    finally os.remove.all(tmpRoot)

  test("forSession returns Some(metadata) when session exists"):
    withProjectDir("-home-mph-test", List("session-find-me")): projectDir =>
      val result = index.forSession(projectDir, "session-find-me")
      assert(
        result.isDefined,
        "Expected Some(LogFileMetadata) for existing session"
      )
      assertEquals(result.get.sessionId, "session-find-me")

  test("forSession returns None when session does not exist"):
    withProjectDir("-home-mph-test", List("other-session")): projectDir =>
      val result = index.forSession(projectDir, "nonexistent-session")
      assertEquals(result, None)

  test("forSession returns metadata with correct path"):
    withProjectDir("-home-mph-test", List("target-session")): projectDir =>
      val result = index.forSession(projectDir, "target-session")
      result match
        case Some(meta) =>
          assertEquals(meta.path, projectDir / "target-session.jsonl")
        case None => fail("Expected Some(LogFileMetadata)")

  // listSubAgents tests

  private def withSubAgentsDir(
      sessionId: String,
      agentFiles: List[(String, Option[String])]
  )(body: os.Path => Unit): Unit =
    val tmpRoot = os.temp.dir()
    try
      val projectDir = tmpRoot / "-home-mph-test-project"
      os.makeDir.all(projectDir)
      os.write(projectDir / s"$sessionId.jsonl", "")
      val subagentsDir = projectDir / sessionId / "subagents"
      if agentFiles.nonEmpty then os.makeDir.all(subagentsDir)
      agentFiles.foreach:
        case (agentId, Some(metaJson)) =>
          os.write(subagentsDir / s"agent-$agentId.jsonl", "")
          os.write(subagentsDir / s"agent-$agentId.meta.json", metaJson)
        case (agentId, None) =>
          os.write(subagentsDir / s"agent-$agentId.jsonl", "")
      body(projectDir)
    finally os.remove.all(tmpRoot)

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
      val result = index.listSubAgents(projectDir, "sess-1")
      assertEquals(result, Seq.empty)

  test("listSubAgents returns empty Seq when subagents directory is empty"):
    val tmpRoot = os.temp.dir()
    try
      val projectDir = tmpRoot / "-home-mph-test-project"
      os.makeDir.all(projectDir / "sess-1" / "subagents")
      val result = index.listSubAgents(projectDir, "sess-1")
      assertEquals(result, Seq.empty)
    finally os.remove.all(tmpRoot)

  test("listSubAgents discovers sub-agent with valid .meta.json"):
    withSubAgentsDir(
      "sess-2",
      List(("abc", Some(validMetaJson("abc"))))
    ): projectDir =>
      val result = index.listSubAgents(projectDir, "sess-2")
      assertEquals(result.length, 1)
      assertEquals(result.head.agentId, "agent-abc")

  test("listSubAgents populates all metadata fields from .meta.json"):
    withSubAgentsDir(
      "sess-3",
      List(
        (
          "abc",
          Some(
            validMetaJson("abc", agentType = Some("researcher"), description = Some("Does research"))
          )
        )
      )
    ): projectDir =>
      val result = index.listSubAgents(projectDir, "sess-3")
      assertEquals(result.length, 1)
      val meta = result.head
      assertEquals(meta.agentId, "agent-abc")
      assertEquals(meta.agentType, Some("researcher"))
      assertEquals(meta.description, Some("Does research"))

  test("listSubAgents sets transcriptPath to the .jsonl file path"):
    withSubAgentsDir(
      "sess-4",
      List(("abc", Some(validMetaJson("abc"))))
    ): projectDir =>
      val result = index.listSubAgents(projectDir, "sess-4")
      assertEquals(result.length, 1)
      assertEquals(
        result.head.transcriptPath,
        projectDir / "sess-4" / "subagents" / "agent-abc.jsonl"
      )

  test("listSubAgents skips sub-agent when .meta.json is missing"):
    withSubAgentsDir("sess-5", List(("abc", None))): projectDir =>
      val result = index.listSubAgents(projectDir, "sess-5")
      assertEquals(result, Seq.empty)

  test("listSubAgents skips sub-agent when .meta.json is malformed"):
    val tmpRoot = os.temp.dir()
    try
      val projectDir = tmpRoot / "-home-mph-test-project"
      val subagentsDir = projectDir / "sess-6" / "subagents"
      os.makeDir.all(subagentsDir)
      os.write(subagentsDir / "agent-abc.jsonl", "")
      os.write(subagentsDir / "agent-abc.meta.json", "NOT VALID JSON {{{")
      val result = index.listSubAgents(projectDir, "sess-6")
      assertEquals(result, Seq.empty)
    finally os.remove.all(tmpRoot)

  test("listSubAgents discovers multiple sub-agents"):
    withSubAgentsDir(
      "sess-7",
      List(
        ("aaa", Some(validMetaJson("aaa"))),
        ("bbb", Some(validMetaJson("bbb"))),
        ("ccc", Some(validMetaJson("ccc")))
      )
    ): projectDir =>
      val result = index.listSubAgents(projectDir, "sess-7")
      assertEquals(result.length, 3)
      assertEquals(result.map(_.agentId).toSet, Set("agent-aaa", "agent-bbb", "agent-ccc"))

  test("listSubAgents ignores non-agent files in subagents directory"):
    val tmpRoot = os.temp.dir()
    try
      val projectDir = tmpRoot / "-home-mph-test-project"
      val subagentsDir = projectDir / "sess-8" / "subagents"
      os.makeDir.all(subagentsDir)
      os.write(subagentsDir / "agent-abc.jsonl", "")
      os.write(subagentsDir / "agent-abc.meta.json", validMetaJson("abc"))
      os.write(subagentsDir / "notes.txt", "irrelevant")
      val result = index.listSubAgents(projectDir, "sess-8")
      assertEquals(result.length, 1)
      assertEquals(result.head.agentId, "agent-abc")
    finally os.remove.all(tmpRoot)
