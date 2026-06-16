// PURPOSE: Tests for the ZIO conversation log index over temporary project directories
// PURPOSE: Verifies session listing, lookup, sub-agent discovery, and empty-directory handling

package works.iterative.claude.zio.log

import zio.*
import zio.test.*
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object ZioConversationLogIndexTest extends ClaudeZioSpec:
  private val index = ZioConversationLogIndex.make(None, os.home)

  def spec = suite("ZioConversationLogIndex")(
    test("listSessions returns metadata for each .jsonl file"):
      for
        dir      <- ZIO.attempt(os.temp.dir())
        _        <- ZIO.attempt(os.write(dir / "s1.jsonl", "{}"))
        _        <- ZIO.attempt(os.write(dir / "s2.jsonl", "{}"))
        _        <- ZIO.attempt(os.write(dir / "ignore.txt", "x"))
        metadata <- index.listSessions(dir)
      yield assertTrue(metadata.map(_.sessionId).toSet == Set("s1", "s2")),
    test("listSessions returns empty for a nonexistent directory"):
      for metadata <- index.listSessions(os.Path("/nonexistent/zzz-claude-sdk"))
      yield assertTrue(metadata.isEmpty),
    test("forSession finds an existing session and misses an absent one"):
      for
        dir     <- ZIO.attempt(os.temp.dir())
        _       <- ZIO.attempt(os.write(dir / "abc.jsonl", "{}"))
        found   <- index.forSession(dir, "abc")
        missing <- index.forSession(dir, "nope")
      yield assertTrue(found.exists(_.sessionId == "abc"), missing.isEmpty),
    test("listSubAgents parses agent meta sidecars"):
      for
        dir      <- ZIO.attempt(os.temp.dir())
        subagents = dir / "sess" / "subagents"
        _        <- ZIO.attempt(os.makeDir.all(subagents))
        _        <- ZIO.attempt(os.write(subagents / "agent-1.jsonl", "{}"))
        _        <- ZIO.attempt(
                      os.write(
                        subagents / "agent-1.meta.json",
                        """{"agentType":"explorer","description":"d"}"""
                      )
                    )
        agents   <- index.listSubAgents(dir, "sess")
      yield assertTrue(
        agents.map(_.agentId) == Seq("agent-1"),
        agents.head.agentType.contains("explorer")
      )
  )
