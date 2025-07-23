// PURPOSE: Tests environment variable inheritance behavior in ProcessManager
// PURPOSE: Verifies fs2 ProcessBuilder environment configuration works correctly

package works.iterative.claude.internal.cli

import munit.CatsEffectSuite
import cats.effect.IO
import fs2.io.process.ProcessBuilder
import works.iterative.claude.QueryOptions

class EnvironmentInheritanceTest extends CatsEffectSuite:

  test(
    "fs2 ProcessBuilder.withInheritEnv(false) completely clears environment"
  ):
    // This test directly verifies fs2 ProcessBuilder behavior
    val result = ProcessBuilder("env", List())
      .withInheritEnv(false)
      .spawn[IO]
      .use { process =>
        process.stdout
          .through(fs2.text.utf8.decode)
          .through(fs2.text.lines)
          .compile
          .toList
      }

    result.map { envVars =>
      assertEquals(
        envVars.size,
        0,
        "WithInheritEnv(false) should result in empty environment"
      )
      assert(
        !envVars.exists(_.startsWith("PATH=")),
        "PATH should not be present with inheritEnv(false)"
      )
    }

  test("fs2 ProcessBuilder.withInheritEnv(true) preserves parent environment"):
    val result = ProcessBuilder("env", List())
      .withInheritEnv(true)
      .spawn[IO]
      .use { process =>
        process.stdout
          .through(fs2.text.utf8.decode)
          .through(fs2.text.lines)
          .filter(_.startsWith("PATH="))
          .take(1)
          .compile
          .toList
      }

    result.map { pathVars =>
      assert(
        pathVars.nonEmpty,
        "PATH should be present with inheritEnv(true)"
      )
    }

  test("ProcessManager respects inheritEnvironment = Some(false)"):
    val options = QueryOptions(
      prompt = "test",
      inheritEnvironment = Some(false)
    )

    val pb = ProcessManager.default.configureProcessBuilder(
      "/usr/bin/env",
      List(),
      options
    )

    assertEquals(pb.inheritEnv, false, "Should not inherit environment")

  test("ProcessManager respects inheritEnvironment = Some(true)"):
    val options = QueryOptions(
      prompt = "test",
      inheritEnvironment = Some(true)
    )

    val pb = ProcessManager.default.configureProcessBuilder(
      "/usr/bin/env",
      List(),
      options
    )

    assertEquals(pb.inheritEnv, true, "Should inherit environment")

  test("ProcessManager defaults to inheritEnvironment = true when None"):
    val options = QueryOptions(
      prompt = "test",
      inheritEnvironment = None
    )

    val pb = ProcessManager.default.configureProcessBuilder(
      "/usr/bin/env",
      List(),
      options
    )

    assertEquals(pb.inheritEnv, true, "Should default to inherit environment")

  test(
    "ProcessManager with inheritEnvironment=false starts with empty environment"
  ):
    val options = QueryOptions(
      prompt = "test",
      inheritEnvironment = Some(false)
    )

    val pb = ProcessManager.default.configureProcessBuilder(
      "/usr/bin/env",
      List(),
      options
    )

    val result = pb.spawn[IO].use { process =>
      process.stdout
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
        .compile
        .toList
    }

    result.map { envVars =>
      assertEquals(
        envVars.size,
        0,
        "Should have empty environment with inheritEnvironment=false"
      )
    }

  test("ProcessManager with inheritEnvironment=false but custom env vars"):
    val customEnv = Map(
      "CUSTOM_VAR" -> "custom-value",
      "API_KEY" -> "test-key"
    )

    val options = QueryOptions(
      prompt = "test",
      inheritEnvironment = Some(false),
      environmentVariables = Some(customEnv)
    )

    val pb = ProcessManager.default.configureProcessBuilder(
      "/usr/bin/env",
      List(),
      options
    )

    val result = pb.spawn[IO].use { process =>
      process.stdout
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
        .compile
        .toList
    }

    result.map { envVars =>
      // Filter out empty lines that env command might produce
      val nonEmptyEnvVars = envVars.filter(_.trim.nonEmpty)

      assert(
        nonEmptyEnvVars.contains("CUSTOM_VAR=custom-value"),
        "Should have custom variable"
      )
      assert(
        nonEmptyEnvVars.contains("API_KEY=test-key"),
        "Should have API key"
      )
      assert(
        !nonEmptyEnvVars.exists(_.startsWith("PATH=")),
        "Should not have PATH from parent environment"
      )
      assertEquals(
        nonEmptyEnvVars.size,
        2,
        s"Should only have the 2 custom environment variables, got: ${nonEmptyEnvVars.mkString(", ")}"
      )
    }

  test("ProcessManager with inheritEnvironment=true and custom env vars"):
    val customEnv = Map("CUSTOM_VAR" -> "custom-value")

    val options = QueryOptions(
      prompt = "test",
      inheritEnvironment = Some(true),
      environmentVariables = Some(customEnv)
    )

    val pb = ProcessManager.default.configureProcessBuilder(
      "/usr/bin/env",
      List(),
      options
    )

    val result = pb.spawn[IO].use { process =>
      process.stdout
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
        .compile
        .toList
    }

    result.map { envVars =>
      assert(
        envVars.contains("CUSTOM_VAR=custom-value"),
        "Should have custom variable"
      )
      assert(
        envVars.exists(_.startsWith("PATH=")),
        "Should have PATH from parent environment"
      )
      assert(
        envVars.size > 2,
        "Should have more than just custom variables (inherited ones too)"
      )
    }

  test("PATH variable behavior verification"):
    // Test that PATH is completely absent when inheritEnv=false
    val optionsWithoutInherit = QueryOptions(
      prompt = "test",
      inheritEnvironment = Some(false)
    )

    val pbWithoutInherit = ProcessManager.default.configureProcessBuilder(
      "/usr/bin/env",
      List(),
      optionsWithoutInherit
    )

    val resultWithoutInherit = pbWithoutInherit.spawn[IO].use { process =>
      process.stdout
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
        .filter(_.startsWith("PATH="))
        .compile
        .toList
    }

    resultWithoutInherit.map { pathVars =>
      assertEquals(
        pathVars.size,
        0,
        "PATH should be completely absent with inheritEnvironment=false"
      )
    }
