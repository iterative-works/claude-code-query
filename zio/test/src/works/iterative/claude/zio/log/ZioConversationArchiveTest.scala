// PURPOSE: Tests the ZIO conversation archive over fixture session trees in temp directories
// PURPOSE: Covers locate, entries (raw-tolerant), sub-agent join, and idempotent append-aware mirror

package works.iterative.claude.zio.log

import zio.*
import zio.test.*
import works.iterative.claude.core.log.ArchiveConfig
import works.iterative.claude.core.log.ArchiveError
import works.iterative.claude.core.log.ArchiveError.SessionNotFound
import works.iterative.claude.core.log.ArchiveError.SubAgentNotFound
import works.iterative.claude.core.log.model.RawLogEntry
import works.iterative.claude.core.model.SessionId
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object ZioConversationArchiveTest extends ClaudeZioSpec:

  private val sessionId     = "0d43043b-1111-2222-3333-444455556666"
  private val cwd           = os.Path("/home/tester/proj")
  private val parentToolUse = "toolu_01RKfPARENT"

  private val mainLine1 =
    s"""{"type":"user","sessionId":"$sessionId","uuid":"u1","message":{"content":"first"}}"""
  private val mainLine2 =
    s"""{"type":"assistant","sessionId":"$sessionId","uuid":"u2","message":{"content":"reply","model":"claude"}}"""
  private val mainUnknown =
    s"""{"type":"vendor-future-type","sessionId":"$sessionId","uuid":"u3","surprise":42}"""
  private val subAgentLine =
    s"""{"type":"user","sessionId":"$sessionId","uuid":"a1","isSidechain":true,"message":{"content":"sub work"}}"""
  private val subAgentMeta =
    s"""{"agentType":"general-purpose","description":"probe","toolUseId":"$parentToolUse"}"""

  private case class Fixture(
      archive: ZioConversationArchive,
      config: ArchiveConfig
  )

  /** Builds a ground-truth-shaped vendor tree and returns a fresh archive plus
    * its config. `vendorProjectsDir` and `archiveDir` are separate temp dirs.
    */
  private def fixture: ZIO[Any, Throwable, Fixture] =
    ZIO.attempt:
      val vendorProjectsDir = os.temp.dir()
      val archiveDir        = os.temp.dir()
      val config            = ArchiveConfig(vendorProjectsDir, archiveDir, cwd)
      val projectDir        = vendorProjectsDir / "-home-tester-proj"
      os.write(
        projectDir / s"$sessionId.jsonl",
        Seq(mainLine1, mainLine2, mainUnknown).mkString("", "\n", "\n"),
        createFolders = true
      )
      val subagents = projectDir / sessionId / "subagents"
      os.write(subagents / "agent-abc.jsonl", subAgentLine + "\n",
        createFolders = true)
      os.write(subagents / "agent-abc.meta.json", subAgentMeta)
      Fixture(ZioConversationArchive(config), config)

  private val isPosixFs: Boolean =
    java.nio.file.FileSystems.getDefault.supportedFileAttributeViews
      .contains("posix")

  private def relFilesUnder(root: os.Path): Set[os.SubPath] =
    if !os.exists(root) then Set.empty
    else os.walk(root).filter(os.isFile).map(_.subRelativeTo(root)).toSet

  /** Asserts every source file under the session tree has a byte-identical
    * counterpart under the archive.
    */
  private def assertMirrorMatchesSource(config: ArchiveConfig): TestResult =
    val projectDir = config.vendorProjectsDir / "-home-tester-proj"
    val sourceRel =
      relFilesUnder(projectDir).filter(rel =>
        rel == os.sub / s"$sessionId.jsonl" || rel.segments.headOption
          .contains(sessionId)
      )
    val identical = sourceRel.forall: rel =>
      os.exists(config.archiveDir / rel) &&
        java.util.Arrays.equals(
          os.read.bytes(projectDir / rel),
          os.read.bytes(config.archiveDir / rel)
        )
    assertTrue(sourceRel.nonEmpty, identical)

  def spec = suite("ZioConversationArchive")(
    test("rejects a traversing session id rather than resolving outside the project"):
      val traversals = List("../x", "a/b", "..", "", "a\\b", "/etc/passwd")
      for
        fx <- fixture
        archive = fx.archive
        located <- ZIO.foreach(traversals)(id => archive.forSession(SessionId(id)).either)
        mirrored <- ZIO.foreach(traversals)(id => archive.mirror(SessionId(id)).either)
      yield
        def allRejected(results: List[Either[ArchiveError, ?]]): Boolean =
          results.forall:
            case Left(ArchiveError.InvalidSessionId(_)) => true
            case _                                      => false
        assertTrue(allRejected(located), allRejected(mirrored)),
    test("rejects a traversing sub-agent id rather than reaching outside the project"):
      for
        fx <- fixture
        archive = fx.archive
        bySession <- archive.subagentEntries(SessionId("../x"), parentToolUse).runCollect.either
        byTool    <- archive.subagentEntries(SessionId(sessionId), "../x").runCollect.either
      yield assertTrue(
        bySession == Left(ArchiveError.InvalidSessionId("../x")),
        byTool == Left(ArchiveError.InvalidSessionId("../x"))
      ),
    test("forSession locates an existing session and misses an absent one"):
      for
        fx <- fixture
        archive = fx.archive
        found        <- archive.forSession(SessionId(sessionId))
        missing      <- archive.forSession(SessionId("no-such-session"))
      yield assertTrue(
        found.exists(_.sessionId.value == sessionId),
        found.exists(_.mainTranscript.last == s"$sessionId.jsonl"),
        missing.isEmpty
      ),
    test("entries streams main-thread entries and degrades unknown types to raw"):
      for
        fx <- fixture
        archive = fx.archive
        entries      <- archive.entries(SessionId(sessionId)).runCollect
      yield assertTrue(
        entries.map(_.uuid.getOrElse("")).toList == List("u1", "u2", "u3"),
        entries.last.payload match
          case RawLogEntry(entryType, _) => entryType == "vendor-future-type"
          case _                         => false
      ),
    test("entries fails with SessionNotFound for an absent session"):
      for
        fx <- fixture
        archive = fx.archive
        result       <- archive.entries(SessionId("no-such-session")).runCollect.either
      yield assertTrue(result == Left(SessionNotFound("no-such-session"))),
    test("subagentEntries joins a sub-agent by its meta.json toolUseId"):
      for
        fx <- fixture
        archive = fx.archive
        entries      <- archive.subagentEntries(SessionId(sessionId), parentToolUse).runCollect
      yield assertTrue(
        entries.map(_.uuid.getOrElse("")).toList == List("a1"),
        entries.forall(_.isSidechain)
      ),
    test("subagentEntries fails when no sub-agent records the tool_use id"):
      for
        fx <- fixture
        archive = fx.archive
        result       <- archive
                          .subagentEntries(SessionId(sessionId), "toolu_UNKNOWN")
                          .runCollect
                          .either
      yield assertTrue(
        result == Left(SubAgentNotFound(sessionId, "toolu_UNKNOWN"))
      ),
    test("subagentEntries fails with SubAgentNotFound when a meta.json has no transcript"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        orphanTool = "toolu_ORPHAN"
        _ <- ZIO.attempt:
               val subagents =
                 config.vendorProjectsDir / "-home-tester-proj" / sessionId / "subagents"
               os.write(
                 subagents / "agent-orphan.meta.json",
                 s"""{"agentType":"general-purpose","description":"gone","toolUseId":"$orphanTool"}"""
               )
        result <- archive
                    .subagentEntries(SessionId(sessionId), orphanTool)
                    .runCollect
                    .either
      yield assertTrue(result == Left(SubAgentNotFound(sessionId, orphanTool))),
    test("mirror fails with SessionNotFound for an absent session"):
      for
        fx <- fixture
        archive = fx.archive
        result       <- archive.mirror(SessionId("no-such-session")).either
      yield assertTrue(result == Left(SessionNotFound("no-such-session"))),
    test("mirror copies the whole tree byte-identically and reports it"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        report            <- archive.mirror(SessionId(sessionId))
      yield assertTrue(
        report.copied.size == 3,
        report.extended.isEmpty,
        report.refreshed.isEmpty,
        report.skipped.isEmpty
      ) && assertMirrorMatchesSource(config),
    test("mirror is idempotent: a second run skips every file"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        _                 <- archive.mirror(SessionId(sessionId))
        second            <- archive.mirror(SessionId(sessionId))
      yield assertTrue(
        second.skipped.size == 3,
        second.copied.isEmpty,
        second.extended.isEmpty,
        second.refreshed.isEmpty
      ) && assertMirrorMatchesSource(config),
    test("mirror skips an equal-size content change, leaving the archive stale"):
      // Size is the only signal, so a same-length edit is invisible to the
      // mirror: it is skipped and the archived copy stays byte-divergent.
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        _                 <- archive.mirror(SessionId(sessionId))
        projectDir = config.vendorProjectsDir / "-home-tester-proj"
        mainPath   = projectDir / s"$sessionId.jsonl"
        _ <- ZIO.attempt:
               val edited = os.read(mainPath).replace("first", "FIRST")
               os.write.over(mainPath, edited)
        report <- archive.mirror(SessionId(sessionId))
      yield
        val archivedMain = config.archiveDir / s"$sessionId.jsonl"
        assertTrue(
          report.skipped.contains(os.sub / s"$sessionId.jsonl"),
          !java.util.Arrays.equals(
            os.read.bytes(mainPath),
            os.read.bytes(archivedMain)
          )
        ),
    test("mirror keeps a vendor-pruned sub-agent file in the archive"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        _                 <- archive.mirror(SessionId(sessionId))
        subagents = config.vendorProjectsDir / "-home-tester-proj" /
          sessionId / "subagents"
        archivedAgent = config.archiveDir / sessionId / "subagents" /
          "agent-abc.jsonl"
        archivedMeta = config.archiveDir / sessionId / "subagents" /
          "agent-abc.meta.json"
        agentSnapshot <- ZIO.attempt(os.read.bytes(archivedAgent))
        metaSnapshot  <- ZIO.attempt(os.read.bytes(archivedMeta))
        _ <- ZIO.attempt:
               os.remove.all(subagents / "agent-abc.jsonl")
               os.remove.all(subagents / "agent-abc.meta.json")
        _ <- archive.mirror(SessionId(sessionId))
      yield assertTrue(
        os.exists(archivedAgent),
        java.util.Arrays.equals(os.read.bytes(archivedAgent), agentSnapshot),
        os.exists(archivedMeta),
        java.util.Arrays.equals(os.read.bytes(archivedMeta), metaSnapshot)
      ),
    test("mirror appends when the source strictly extends the mirrored prefix"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        _                 <- archive.mirror(SessionId(sessionId))
        projectDir = config.vendorProjectsDir / "-home-tester-proj"
        _ <- ZIO.attempt(
               os.write.append(
                 projectDir / s"$sessionId.jsonl",
                 mainLine2 + "\n"
               )
             )
        report <- archive.mirror(SessionId(sessionId))
      yield assertTrue(
        report.extended == Seq(os.sub / s"$sessionId.jsonl"),
        report.refreshed.isEmpty
      ) && assertMirrorMatchesSource(config),
    test("mirror recopies when the source shrinks below the mirror"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        _                 <- archive.mirror(SessionId(sessionId))
        projectDir = config.vendorProjectsDir / "-home-tester-proj"
        _ <- ZIO.attempt(
               os.write.over(projectDir / s"$sessionId.jsonl", mainLine1 + "\n")
             )
        report <- archive.mirror(SessionId(sessionId))
      yield assertTrue(
        report.refreshed == Seq(os.sub / s"$sessionId.jsonl"),
        report.extended.isEmpty
      ) && assertMirrorMatchesSource(config),
    test("mirror records a per-file copy failure instead of aborting the run"):
      // A blocker file where the sub-agents directory must go makes the
      // sub-agent copies fail while the main transcript still mirrors. The run
      // reports the failures rather than throwing away all progress.
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        _ <- ZIO.attempt(
               os.write(
                 config.archiveDir / sessionId / "subagents",
                 "blocker",
                 createFolders = true
               )
             )
        result <- archive.mirror(SessionId(sessionId)).either
      yield assertTrue(result.isRight) && (result match
        case Right(report) =>
          assertTrue(
            report.copied.contains(os.sub / s"$sessionId.jsonl"),
            report.failed
              .map(_._1)
              .contains(os.sub / sessionId / "subagents" / "agent-abc.jsonl")
          )
        case Left(_) => assertTrue(false)
      ),
    test("mirror recopies when a grown source diverges from the mirrored prefix"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        _                 <- archive.mirror(SessionId(sessionId))
        projectDir = config.vendorProjectsDir / "-home-tester-proj"
        // Longer than the mirror, but the first bytes differ: not a true
        // extension, so the append fast path must fall back to a full recopy.
        _ <- ZIO.attempt(
               os.write.over(
                 projectDir / s"$sessionId.jsonl",
                 "PREFIX-CHANGED\n" + mainLine1 + "\n" + mainLine2 + "\n" +
                   mainUnknown + "\n"
               )
             )
        report <- archive.mirror(SessionId(sessionId))
      yield assertTrue(
        report.refreshed == Seq(os.sub / s"$sessionId.jsonl"),
        report.extended.isEmpty
      ) && assertMirrorMatchesSource(config),
    test("mirror does not follow a symlink in the vendor tree"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        _ <- ZIO.attempt:
               val outside = os.temp.dir() / "outside.jsonl"
               os.write(outside, "SECRET OUTSIDE THE TREE\n")
               val subagents =
                 config.vendorProjectsDir / "-home-tester-proj" / sessionId /
                   "subagents"
               os.symlink(subagents / "leak.jsonl", outside)
        report <- archive.mirror(SessionId(sessionId))
      yield assertTrue(
        // The symlink is neither planned nor copied into the archive.
        report.total > 0,
        !report.copied.exists(_.last == "leak.jsonl"),
        !os.exists(
          config.archiveDir / sessionId / "subagents" / "leak.jsonl",
          followLinks = false
        )
      ),
    test("mirror restricts the archive directory tree to owner-only on POSIX"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        _ <- archive.mirror(SessionId(sessionId))
      yield
        if !isPosixFs then assertTrue(true)
        else
          val dirs = config.archiveDir +:
            os.walk(config.archiveDir).filter(os.isDir(_, followLinks = false))
          assertTrue(dirs.forall(d => os.perms(d).toString == "rwx------"))
  )
