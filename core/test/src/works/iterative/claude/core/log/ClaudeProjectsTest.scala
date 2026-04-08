// PURPOSE: Unit tests for ClaudeProjects base directory resolution and project dir encoding
// PURPOSE: Covers override absent/present cases and composition with ProjectPathEncoder

package works.iterative.claude.core.log

import munit.FunSuite

class ClaudeProjectsTest extends FunSuite:

  test("baseDir with no override uses home / .claude / projects"):
    val home = os.Path("/tmp/fakeHome")
    assertEquals(
      ClaudeProjects.baseDir(None, home),
      os.Path("/tmp/fakeHome/.claude/projects")
    )

  test("baseDir with Some(override) uses override / projects"):
    val custom = os.Path("/tmp/custom")
    assertEquals(
      ClaudeProjects.baseDir(Some(custom), os.Path("/tmp/home")),
      os.Path("/tmp/custom/projects")
    )

  test("projectDirFor composes baseDir with encoded cwd"):
    val home = os.Path("/tmp/fakeHome")
    assertEquals(
      ClaudeProjects.projectDirFor(os.Path("/a/b"), None, home),
      os.Path("/tmp/fakeHome/.claude/projects/-a-b")
    )

  test("projectDirFor with override uses custom base"):
    val custom = os.Path("/tmp/custom")
    assertEquals(
      ClaudeProjects.projectDirFor(os.Path("/a/b"), Some(custom), os.Path("/tmp/home")),
      os.Path("/tmp/custom/projects/-a-b")
    )

  test("resolveConfigDir treats empty string as unset"):
    val result = ClaudeProjects.resolveConfigDir(_ => Some(""))
    assertEquals(result, None)

  test("resolveConfigDir resolves non-empty value to os.Path"):
    val result = ClaudeProjects.resolveConfigDir(_ => Some("/tmp/x"))
    assertEquals(result, Some(os.Path("/tmp/x")))

  test("resolveConfigDir returns None when env var is unset"):
    val result = ClaudeProjects.resolveConfigDir(_ => None)
    assertEquals(result, None)
