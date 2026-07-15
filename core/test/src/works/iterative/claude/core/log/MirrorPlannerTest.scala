// PURPOSE: Tests the pure mirror planner's size-driven copy/extend/recopy/skip decisions
// PURPOSE: Covers empty mirror, up-to-date, extension, shrink, added sub-agent, changed meta, custody

package works.iterative.claude.core.log

import munit.FunSuite

class MirrorPlannerTest extends FunSuite:
  private val main    = os.sub / "sess.jsonl"
  private val agent   = os.sub / "sess" / "subagents" / "agent-1.jsonl"
  private val meta    = os.sub / "sess" / "subagents" / "agent-1.meta.json"

  test("empty mirror copies every source file"):
    val plan = MirrorPlanner.plan(
      source = Map(main -> 100L, agent -> 50L),
      mirror = Map.empty
    )
    assertEquals(
      plan.actions.toSet,
      Set(MirrorAction.Copy(main), MirrorAction.Copy(agent))
    )

  test("up-to-date sizes skip every file"):
    val plan = MirrorPlanner.plan(
      source = Map(main -> 100L, agent -> 50L),
      mirror = Map(main -> 100L, agent -> 50L)
    )
    assertEquals(
      plan.actions.toSet,
      Set(MirrorAction.Skip(main), MirrorAction.Skip(agent))
    )

  test("a source grown past the mirror is a candidate extend from mirrored size"):
    val plan = MirrorPlanner.plan(
      source = Map(main -> 180L),
      mirror = Map(main -> 100L)
    )
    assertEquals(plan.actions, Seq(MirrorAction.Extend(main, 100L)))

  test("a source shorter than the mirror is a full recopy"):
    val plan = MirrorPlanner.plan(
      source = Map(main -> 40L),
      mirror = Map(main -> 100L)
    )
    assertEquals(plan.actions, Seq(MirrorAction.Recopy(main)))

  test("a sub-agent file absent from the mirror is copied"):
    val plan = MirrorPlanner.plan(
      source = Map(main -> 100L, agent -> 50L),
      mirror = Map(main -> 100L)
    )
    assertEquals(
      plan.actions.toSet,
      Set(MirrorAction.Skip(main), MirrorAction.Copy(agent))
    )

  test("a meta.json whose size changed is planned by size difference"):
    // Larger changed meta is a candidate extend; the interpreter's prefix check
    // turns it into a recopy when the bytes are not a true extension.
    val grown   = MirrorPlanner.plan(Map(meta -> 55L), Map(meta -> 40L))
    val shrunk  = MirrorPlanner.plan(Map(meta -> 30L), Map(meta -> 40L))
    assertEquals(grown.actions, Seq(MirrorAction.Extend(meta, 40L)))
    assertEquals(shrunk.actions, Seq(MirrorAction.Recopy(meta)))

  test("a file the vendor pruned stays in the archive: not in the plan"):
    // Present in the mirror, absent from source -> no action touches it (custody).
    val plan = MirrorPlanner.plan(
      source = Map(main -> 100L),
      mirror = Map(main -> 100L, agent -> 50L)
    )
    assertEquals(plan.actions, Seq(MirrorAction.Skip(main)))
