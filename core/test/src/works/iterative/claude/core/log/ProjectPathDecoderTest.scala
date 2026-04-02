// PURPOSE: Unit tests for ProjectPathDecoder path decoding from Claude project directory names
// PURPOSE: Covers standard paths, edge cases like root, single segment, and empty input

package works.iterative.claude.core.log

import munit.FunSuite

class ProjectPathDecoderTest extends FunSuite:

  test("decodes typical multi-segment encoded path"):
    assertEquals(
      ProjectPathDecoder.decode("-home-mph-Devel-projects-foo"),
      "/home/mph/Devel/projects/foo"
    )

  test("decodes single-segment path"):
    assertEquals(
      ProjectPathDecoder.decode("-home"),
      "/home"
    )

  test("decodes two-segment path"):
    assertEquals(
      ProjectPathDecoder.decode("-home-mph"),
      "/home/mph"
    )

  test("decodes path with deep nesting — all dashes become slashes"):
    // The encoder replaced every / with -, so decoding reverses that by replacing all - with /
    // Segments containing - in the original are ambiguous and cannot be recovered
    assertEquals(
      ProjectPathDecoder.decode("-home-mph-Devel-projects-myapp"),
      "/home/mph/Devel/projects/myapp"
    )

  test("returns empty string for empty input"):
    assertEquals(
      ProjectPathDecoder.decode(""),
      ""
    )

  test("decodes root path encoded as single dash"):
    // A leading dash with nothing after is best-effort decoded to just /
    assertEquals(
      ProjectPathDecoder.decode("-"),
      "/"
    )

  test("best-effort decode replaces every dash with slash"):
    // -home-my-project decodes to /home/my/project (ambiguous — callers must validate)
    assertEquals(
      ProjectPathDecoder.decode("-home-my-project"),
      "/home/my/project"
    )
