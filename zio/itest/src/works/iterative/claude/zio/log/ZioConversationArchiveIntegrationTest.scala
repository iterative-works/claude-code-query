// PURPOSE: Integration test running locate -> entries -> subagentEntries -> mirror over a fixture tree
// PURPOSE: Verifies byte-identical mirroring, idempotency, append catch-up, and shrink recopy fallback

package works.iterative.claude.zio.log

import zio.*
import zio.test.*
import works.iterative.claude.core.log.ArchiveConfig
import works.iterative.claude.core.log.model.RecordRoot
import works.iterative.claude.core.model.SessionId
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object ZioConversationArchiveIntegrationTest extends ClaudeZioSpec:

  private val sessionId = "b1a2c3d4-e5f6-7788-99aa-bbccddeeff00"
  private val cwd       = os.Path("/home/tester/integration-proj")
  private val encoded   = "-home-tester-integration-proj"
  private val toolUseId = "toolu_INTEGRATION"

  private def line(uuid: String, sidechain: Boolean): String =
    s"""{"type":"user","sessionId":"$sessionId","uuid":"$uuid","isSidechain":$sidechain,"message":{"content":"$uuid"}}"""

  private def relFiles(root: os.Path): Set[os.SubPath] =
    if !os.exists(root) then Set.empty
    else os.walk(root).filter(os.isFile).map(_.subRelativeTo(root)).toSet

  /** True when every file under the session's source tree has a byte-identical
    * counterpart under the archive.
    */
  private def treesMatch(config: ArchiveConfig): Boolean =
    val projectDir = config.vendorProjectsDir / encoded
    val mirrorDir  = config.archiveDir / encoded
    val sessionFiles = relFiles(projectDir).filter(rel =>
      rel == os.sub / s"$sessionId.jsonl" ||
        rel.segments.headOption.contains(sessionId)
    )
    sessionFiles.nonEmpty && sessionFiles.forall: rel =>
      os.exists(mirrorDir / rel) &&
        java.util.Arrays.equals(
          os.read.bytes(projectDir / rel),
          os.read.bytes(mirrorDir / rel)
        )

  private def snapshot(root: os.Path): Map[os.SubPath, List[Byte]] =
    if !os.exists(root) then Map.empty
    else
      os.walk(root)
        .filter(os.isFile)
        .map(p => p.subRelativeTo(root) -> os.read.bytes(p).toList)
        .toMap

  private def buildVendorTree: ZIO[Any, Throwable, ArchiveConfig] =
    ZIO.attempt:
      val config = ArchiveConfig(os.temp.dir(), os.temp.dir(), cwd)
      val projectDir = config.vendorProjectsDir / encoded
      os.write(
        projectDir / s"$sessionId.jsonl",
        Seq(line("m1", false), line("m2", false)).mkString("", "\n", "\n"),
        createFolders = true
      )
      val subagents = projectDir / sessionId / "subagents"
      os.write(subagents / "agent-99.jsonl", line("s1", true) + "\n",
        createFolders = true)
      os.write(
        subagents / "agent-99.meta.json",
        s"""{"agentType":"general-purpose","description":"probe","toolUseId":"$toolUseId"}"""
      )
      config

  def spec = suite("ZioConversationArchive (integration)")(
    test("locate, read, join, and mirror a whole session tree, then keep it current"):
      for
        config  <- buildVendorTree
        archive  = ZioConversationArchive(config)

        // locate
        located <- archive.forSession(SessionId(sessionId))

        // read main + sub-agent sidechain
        main    <- archive.entries(SessionId(sessionId)).runCollect
        sub     <- archive.subagentEntries(SessionId(sessionId), toolUseId).runCollect

        // mirror the whole tree
        first   <- archive.mirror(SessionId(sessionId))

        // idempotent second run
        second  <- archive.mirror(SessionId(sessionId))

        // source strictly extended -> mirror catches up by appending
        projectDir = config.vendorProjectsDir / encoded
        _       <- ZIO.attempt(
                     os.write.append(
                       projectDir / s"$sessionId.jsonl",
                       line("m3", false) + "\n"
                     )
                   )
        extend  <- archive.mirror(SessionId(sessionId))
        afterExtend <- archive.entries(SessionId(sessionId)).runCollect

        // source shrinks -> full recopy fallback
        _       <- ZIO.attempt(
                     os.write.over(
                       projectDir / s"$sessionId.jsonl",
                       line("m1", false) + "\n"
                     )
                   )
        shrink  <- archive.mirror(SessionId(sessionId))
      yield assertTrue(
        located.exists(_.sessionId.value == sessionId),
        main.map(_.uuid.getOrElse("")).toList == List("m1", "m2"),
        sub.map(_.uuid.getOrElse("")).toList == List("s1"),
        first.copied.size == 3,
        first.extended.isEmpty && first.refreshed.isEmpty,
        treesMatch(config),
        second.skipped.size == 3,
        second.copied.isEmpty && second.extended.isEmpty && second.refreshed.isEmpty,
        extend.extended == Seq(os.sub / s"$sessionId.jsonl"),
        afterExtend.map(_.uuid.getOrElse("")).toList == List("m1", "m2", "m3"),
        treesMatch(config),
        shrink.refreshed == Seq(os.sub / s"$sessionId.jsonl"),
        treesMatch(config)
      ),
    test("serves a pruned session from the mirror and never writes back"):
      for
        config <- buildVendorTree
        archive = ZioConversationArchive(config)

        // full mirror of the tree
        _ <- archive.mirror(SessionId(sessionId))
        mirrorDir = config.archiveDir / encoded
        before <- ZIO.attempt(snapshot(mirrorDir))

        // the vendor tree is pruned entirely (cleanupPeriodDays)
        _ <- ZIO.attempt(os.remove.all(config.vendorProjectsDir / encoded))

        // read path still serves full history from the mirror
        located     <- archive.forSession(SessionId(sessionId))
        mainEntries <- archive.entries(SessionId(sessionId)).runCollect
        lastPage    <- archive.lastEntries(SessionId(sessionId), 2)
        olderPage   <- ZIO.foreach(lastPage.older)(t =>
          archive.entriesBefore(t, 2)
        )
        sub <- archive
          .subagentEntries(SessionId(sessionId), toolUseId)
          .runCollect

        // custody is one-directional: mirroring a vendor-absent session is a
        // no-op that leaves the archive byte-identical
        report <- archive.mirror(SessionId(sessionId))
        after  <- ZIO.attempt(snapshot(mirrorDir))
      yield assertTrue(
        located.exists(_.root == RecordRoot.Archive),
        mainEntries.map(_.uuid.getOrElse("")).toList == List("m1", "m2"),
        lastPage.entries.map(_.uuid.getOrElse("")).toList == List("m1", "m2"),
        lastPage.older.isEmpty,
        olderPage.isEmpty,
        sub.map(_.uuid.getOrElse("")).toList == List("s1"),
        report.isEmpty,
        before.nonEmpty,
        before == after
      )
  )
