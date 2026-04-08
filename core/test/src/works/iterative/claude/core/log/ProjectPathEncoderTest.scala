// PURPOSE: Unit tests for ProjectPathEncoder path encoding to Claude project directory names
// PURPOSE: Covers typical multi-segment paths, root path, and paths with existing dashes

package works.iterative.claude.core.log

import munit.FunSuite

class ProjectPathEncoderTest extends FunSuite:

  test("encodes typical multi-segment path by replacing / with -"):
    assertEquals(
      ProjectPathEncoder.encode(os.Path("/home/mph/ops/kanon")),
      "-home-mph-ops-kanon"
    )

  test("encodes root path as single dash"):
    assertEquals(
      ProjectPathEncoder.encode(os.Path("/")),
      "-"
    )

  test("path with existing dashes encodes verbatim — all / replaced by -"):
    assertEquals(
      ProjectPathEncoder.encode(os.Path("/a-b/c")),
      "-a-b-c"
    )
