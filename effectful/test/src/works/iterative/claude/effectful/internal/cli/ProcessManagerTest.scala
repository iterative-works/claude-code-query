// PURPOSE: Unit tests for ProcessManager pure configuration logic.
// PURPOSE: Tests configureProcessBuilder behavior without spawning real processes.
package works.iterative.claude.effectful.internal.cli

import works.iterative.claude.core.model.QueryOptions
import works.iterative.claude.effectful.internal.cli.ProcessManager

class ProcessManagerTest extends munit.FunSuite:

  test("configureProcessBuilder creates ProcessBuilder successfully"):
    val options = QueryOptions(prompt = "test prompt")
    val executablePath = "claude"
    val args = List("--print", "--verbose")

    val processBuilder = ProcessManager.default.configureProcessBuilder(
      executablePath,
      args,
      options
    )

    // Verify ProcessBuilder is created (not null/None)
    assert(processBuilder != null, "ProcessBuilder should be created")

  test("configureProcessBuilder sets working directory when provided"):
    val testCwd = "/tmp/test"
    val options = QueryOptions(
      prompt = "test prompt",
      cwd = Some(testCwd)
    )

    val processBuilder = ProcessManager.default.configureProcessBuilder(
      "claude",
      List("--print"),
      options
    )

    // Verify working directory is set
    assertEquals(processBuilder.workingDirectory.map(_.toString), Some(testCwd))

  test("configureProcessBuilder uses no working directory when not specified"):
    val options = QueryOptions(
      prompt = "test prompt",
      cwd = None
    )

    val processBuilder = ProcessManager.default.configureProcessBuilder(
      "claude",
      List("--print"),
      options
    )

    // Verify no working directory is set
    assertEquals(processBuilder.workingDirectory, None)

  test("configureProcessBuilder sets environment variables when specified"):
    val envVars = Map("API_KEY" -> "test-key", "DEBUG" -> "true")
    val options = QueryOptions(
      prompt = "Test prompt",
      inheritEnvironment = Some(false),
      environmentVariables = Some(envVars)
    )

    val pb = ProcessManager.default.configureProcessBuilder(
      "/bin/echo",
      List("test"),
      options
    )

    // Check environment configuration using fs2 ProcessBuilder API
    assertEquals(pb.inheritEnv, false, "Should not inherit environment")
    assertEquals(
      pb.extraEnv.get("API_KEY"),
      Some("test-key"),
      "Should have API_KEY"
    )
    assertEquals(pb.extraEnv.get("DEBUG"), Some("true"), "Should have DEBUG")

  test(
    "configureProcessBuilder inherits environment when inheritEnvironment is true"
  ):
    val envVars = Map("CUSTOM_VAR" -> "custom-value")
    val options = QueryOptions(
      prompt = "Test prompt",
      inheritEnvironment = Some(true),
      environmentVariables = Some(envVars)
    )

    val pb = ProcessManager.default.configureProcessBuilder(
      "/bin/echo",
      List("test"),
      options
    )

    // Should inherit environment and add custom vars
    assertEquals(pb.inheritEnv, true, "Should inherit environment")
    assertEquals(
      pb.extraEnv.get("CUSTOM_VAR"),
      Some("custom-value"),
      "Should have custom variable"
    )

  test("configureProcessBuilder sets inheritEnv to false when specified"):
    val options = QueryOptions(
      prompt = "Test prompt",
      inheritEnvironment = Some(false)
    )

    val pb = ProcessManager.default.configureProcessBuilder(
      "/bin/echo",
      List("test"),
      options
    )

    assertEquals(pb.inheritEnv, false, "Should not inherit environment")

  test(
    "configureProcessBuilder defaults to inheritEnv true when not specified"
  ):
    val options = QueryOptions(prompt = "Test prompt")

    val pb = ProcessManager.default.configureProcessBuilder(
      "/bin/echo",
      List("test"),
      options
    )

    assertEquals(pb.inheritEnv, true, "Should inherit environment by default")
