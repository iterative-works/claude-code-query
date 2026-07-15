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
import works.iterative.claude.core.log.model.RecordRoot
import works.iterative.claude.core.model.SessionId
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object ZioConversationArchiveTest extends ClaudeZioSpec:

  private val sessionId     = "0d43043b-1111-2222-3333-444455556666"
  private val cwd           = os.Path("/home/tester/proj")
  private val encoded       = "-home-tester-proj"
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

  /** Byte-for-byte snapshot of every file under `root`, for asserting custody
    * leaves the archive untouched.
    */
  private def snapshot(root: os.Path): Map[os.SubPath, List[Byte]] =
    if !os.exists(root) then Map.empty
    else
      os.walk(root)
        .filter(os.isFile)
        .map(p => p.subRelativeTo(root) -> os.read.bytes(p).toList)
        .toMap

  /** Asserts every source file under the session tree has a byte-identical
    * counterpart under the archive.
    */
  private def assertMirrorMatchesSource(config: ArchiveConfig): TestResult =
    val projectDir  = config.vendorProjectsDir / encoded
    val mirrorDir   = config.archiveDir / encoded
    val sourceRel =
      relFilesUnder(projectDir).filter(rel =>
        rel == os.sub / s"$sessionId.jsonl" || rel.segments.headOption
          .contains(sessionId)
      )
    val identical = sourceRel.forall: rel =>
      os.exists(mirrorDir / rel) &&
        java.util.Arrays.equals(
          os.read.bytes(projectDir / rel),
          os.read.bytes(mirrorDir / rel)
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
    test("classifyPresence separates confirmed absence from indeterminate existence"):
      import ZioConversationArchive.TranscriptPresence.*
      assertTrue(
        ZioConversationArchive.classifyPresence(
          isRegularFile = true,
          notExists = false,
          exists = true
        ) == Present,
        // Confirmed absent: notExists is decisive.
        ZioConversationArchive.classifyPresence(false, true, false) == Absent,
        // Exists but is not a regular file (e.g. a directory): not a transcript.
        ZioConversationArchive.classifyPresence(false, false, true) == Absent,
        // Nothing confirmed either way: an unreadable parent, not an absence.
        ZioConversationArchive.classifyPresence(
          false,
          false,
          false
        ) == Indeterminate
      ),
    test("locate fails typed when the vendor tree is unreadable, not falling through"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        // Archive now holds the session, so an exists-only check would happily
        // fall through to it and mask the vendor-side access fault.
        _ <- archive.mirror(SessionId(sessionId))
        vendorDir = config.vendorProjectsDir / encoded
        probed <- ZIO.attempt:
          if !isPosixFs then None
          else
            os.perms.set(vendorDir, os.PermSet.fromString("---------"))
            val enforced = !java.nio.file.Files.isReadable(vendorDir.toNIO)
            if enforced then Some(vendorDir)
            else
              os.perms.set(vendorDir, os.PermSet.fromString("rwx------"))
              None
        result <- probed match
          case Some(_) => archive.forSession(SessionId(sessionId)).either
          case None    => ZIO.succeed(Right(None))
        _ <- ZIO.attempt(
          probed.foreach(d => os.perms.set(d, os.PermSet.fromString("rwx------")))
        )
      yield probed match
        case None =>
          // Not POSIX, or permissions are not enforced (e.g. running as root):
          // the indeterminate case cannot be provoked here.
          assertTrue(true)
        case Some(_) =>
          assertTrue(result match
            case Left(ArchiveError.ArchiveIOError(_, _)) => true
            case _                                       => false
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
        val archivedMain = config.archiveDir / encoded / s"$sessionId.jsonl"
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
        archivedAgent = config.archiveDir / encoded / sessionId / "subagents" /
          "agent-abc.jsonl"
        archivedMeta = config.archiveDir / encoded / sessionId / "subagents" /
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
                 config.archiveDir / encoded / sessionId / "subagents",
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
          config.archiveDir / encoded / sessionId / "subagents" / "leak.jsonl",
          followLinks = false
        )
      ),
    test("mirror does not dereference a symlinked main transcript"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        linkedId = "sess-linked"
        _ <- ZIO.attempt:
               val secret = os.temp.dir() / "secret.jsonl"
               os.write(secret, "TOP SECRET MAIN\n")
               val projectDir = config.vendorProjectsDir / "-home-tester-proj"
               os.symlink(projectDir / s"$linkedId.jsonl", secret)
        report <- archive.mirror(SessionId(linkedId))
      yield assertTrue(
        // The symlinked main transcript is neither planned nor copied.
        report.copied.isEmpty,
        !os.exists(
          config.archiveDir / encoded / s"$linkedId.jsonl",
          followLinks = false
        )
      ),
    test("mirror does not walk a symlinked session tree directory"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        treeLinkedId = "sess-treelink"
        _ <- ZIO.attempt:
               val outside = os.temp.dir()
               os.write(outside / "loot.jsonl", "OUTSIDE THE TREE\n")
               val projectDir = config.vendorProjectsDir / "-home-tester-proj"
               os.write(projectDir / s"$treeLinkedId.jsonl", "main line\n")
               os.symlink(projectDir / treeLinkedId, outside)
        report <- archive.mirror(SessionId(treeLinkedId))
      yield assertTrue(
        // Only the real main transcript is mirrored; the symlinked tree root
        // is not walked, so nothing behind it reaches the archive.
        report.copied == Seq(os.sub / s"$treeLinkedId.jsonl"),
        !os.exists(
          config.archiveDir / encoded / treeLinkedId,
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
          assertTrue(dirs.forall(d => os.perms(d).toString == "rwx------")),
    test("mirror writes the archive under the encoded-cwd segment, not flat"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        _ <- archive.mirror(SessionId(sessionId))
      yield assertTrue(
        os.exists(config.archiveDir / encoded / s"$sessionId.jsonl"),
        os.exists(
          config.archiveDir / encoded / sessionId / "subagents" /
            "agent-abc.jsonl"
        ),
        // The old flat layout is gone: nothing hangs directly off archiveDir.
        !os.exists(config.archiveDir / s"$sessionId.jsonl")
      ),
    test("forSession and entries fall back to the archive when the vendor tree is pruned"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        _ <- archive.mirror(SessionId(sessionId))
        _ <- ZIO.attempt(os.remove.all(config.vendorProjectsDir / encoded))
        located     <- archive.forSession(SessionId(sessionId))
        mainEntries <- archive.entries(SessionId(sessionId)).runCollect
        subEntries  <- archive
          .subagentEntries(SessionId(sessionId), parentToolUse)
          .runCollect
      yield assertTrue(
        located.exists(_.root == RecordRoot.Archive),
        located.exists(
          _.mainTranscript == config.archiveDir / encoded / s"$sessionId.jsonl"
        ),
        mainEntries.map(_.uuid.getOrElse("")).toList == List("u1", "u2", "u3"),
        subEntries.map(_.uuid.getOrElse("")).toList == List("a1")
      ),
    test("mirror is a no-op when the vendor tree is gone, leaving the archive intact"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        _ <- archive.mirror(SessionId(sessionId))
        mirrorDir = config.archiveDir / encoded
        before <- ZIO.attempt(snapshot(mirrorDir))
        _ <- ZIO.attempt(os.remove.all(config.vendorProjectsDir / encoded))
        report      <- archive.mirror(SessionId(sessionId))
        reportAgain <- archive.mirror(SessionId(sessionId))
        after       <- ZIO.attempt(snapshot(mirrorDir))
      yield assertTrue(
        report.isEmpty,
        reportAgain.isEmpty,
        before.nonEmpty,
        before == after
      ),
    test("lastEntries returns the final entries and pages backward to the start"):
      for
        fx <- fixture
        archive = fx.archive
        page1 <- archive.lastEntries(SessionId(sessionId), 2)
        page2 <- ZIO.foreach(page1.older)(t => archive.entriesBefore(t, 2))
      yield assertTrue(
        page1.entries.map(_.uuid.getOrElse("")).toList == List("u2", "u3"),
        page1.older.isDefined,
        page2.exists(_.entries.map(_.uuid.getOrElse("")).toList == List("u1")),
        page2.exists(_.older.isEmpty)
      ),
    test("lastEntries past the entry count returns all with no older page"):
      for
        fx <- fixture
        archive = fx.archive
        page <- archive.lastEntries(SessionId(sessionId), 10)
      yield assertTrue(
        page.entries.map(_.uuid.getOrElse("")).toList == List("u1", "u2", "u3"),
        page.older.isEmpty
      ),
    test("lastEntries rejects a non-positive page size before locating"):
      for
        fx <- fixture
        archive = fx.archive
        zero <- archive.lastEntries(SessionId(sessionId), 0).either
        neg  <- archive.lastEntries(SessionId(sessionId), -3).either
      yield assertTrue(
        zero == Left(ArchiveError.InvalidPageSize(0)),
        neg == Left(ArchiveError.InvalidPageSize(-3))
      ),
    test("lastEntries rejects an absurd page size above the contract maximum"):
      for
        fx <- fixture
        archive = fx.archive
        tooBig = works.iterative.claude.core.log.PageSize.Max + 1
        result <- archive.lastEntries(SessionId(sessionId), tooBig).either
      yield assertTrue(result == Left(ArchiveError.InvalidPageSize(tooBig))),
    test("entriesBefore rejects a non-positive page size"):
      for
        fx <- fixture
        archive = fx.archive
        page1 <- archive.lastEntries(SessionId(sessionId), 1)
        result <- ZIO.foreach(page1.older)(t =>
          archive.entriesBefore(t, 0).either
        )
      yield assertTrue(
        result.contains(Left(ArchiveError.InvalidPageSize(0)))
      ),
    test("lastEntries fails with SessionNotFound for an absent session"):
      for
        fx <- fixture
        archive = fx.archive
        result <- archive.lastEntries(SessionId("no-such-session"), 5).either
      yield assertTrue(result == Left(SessionNotFound("no-such-session"))),
    test("lastEntries tails the archive when the vendor tree is pruned"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        _ <- archive.mirror(SessionId(sessionId))
        _ <- ZIO.attempt(os.remove.all(config.vendorProjectsDir / encoded))
        page <- archive.lastEntries(SessionId(sessionId), 3)
      yield assertTrue(
        page.entries.map(_.uuid.getOrElse("")).toList == List("u1", "u2", "u3")
      ),
    test("an older-page token stays valid after the transcript grows"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        page1 <- archive.lastEntries(SessionId(sessionId), 1)
        _ <- ZIO.attempt(
               os.write.append(
                 config.vendorProjectsDir / encoded / s"$sessionId.jsonl",
                 mainLine2 + "\n"
               )
             )
        older <- ZIO.foreach(page1.older)(t => archive.entriesBefore(t, 10))
      yield assertTrue(
        page1.entries.map(_.uuid.getOrElse("")).toList == List("u3"),
        older.exists(
          _.entries.map(_.uuid.getOrElse("")).toList == List("u1", "u2")
        )
      ),
    test("a page token minted on the vendor tree fails once the source moves to the mirror"):
      for
        fx <- fixture
        archive = fx.archive
        config  = fx.config
        _     <- archive.mirror(SessionId(sessionId))
        page1 <- archive.lastEntries(SessionId(sessionId), 1)
        _ <- ZIO.attempt(os.remove.all(config.vendorProjectsDir / encoded))
        result <- ZIO.foreach(page1.older)(t =>
          archive.entriesBefore(t, 1).either
        )
      yield assertTrue(
        page1.older.isDefined,
        result.contains(Left(ArchiveError.PageSourceMoved(sessionId)))
      )
  )
