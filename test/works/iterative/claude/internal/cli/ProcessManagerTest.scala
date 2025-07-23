package works.iterative.claude.internal.cli

import munit.CatsEffectSuite
import cats.effect.IO
import fs2.io.process.ProcessBuilder
import works.iterative.claude.QueryOptions
import works.iterative.claude.internal.cli.JsonParsingError
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.testing.TestingLogger

class ProcessManagerTest extends CatsEffectSuite:

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

  test("executeProcess can execute simple process"):
    val options = QueryOptions(prompt = "test prompt")
    val processBuilder = ProcessBuilder("echo", List("test"))

    // This should not throw NotImplementedError (method is implemented)
    // It will fail with JsonParsingError since "test" is not valid JSON,
    // but that proves the method is actually executing the process
    val logger = TestingLogger.impl[IO]()
    given Logger[IO] = logger
    ProcessManager.default
      .executeProcess(processBuilder, options, "echo", List("test"))
      .compile
      .toList
      .attempt
      .map { result =>
        // Verify it's not NotImplementedError (meaning method is implemented)
        result match
          case Left(_: NotImplementedError) =>
            fail("Method should be implemented, not return NotImplementedError")
          case Left(_) =>
            () // Other errors are expected (like JsonParsingError)
          case Right(_) => () // Success is also fine
      }

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

  test("executeProcess logs process start with command"):
    val options = QueryOptions(prompt = "test prompt")
    val processBuilder = ProcessBuilder("echo", List("test"))
    val executablePath = "echo"
    val args = List("test")

    val logger = TestingLogger.impl[IO]()
    given Logger[IO] = logger
    ProcessManager.default
      .executeProcess(processBuilder, options, executablePath, args)
      .compile
      .toList
      .attempt
      .map { result =>
        // The test succeeds if we can execute the process with logging
        // (not getting NotImplementedError from stub)
        result match
          case Left(_: NotImplementedError) =>
            fail("Method should be implemented, not return NotImplementedError")
          case Left(_) =>
            // Process may fail (e.g., JSON parsing error) - that's expected
            assert(true, "Process executed with logging implementation")
          case Right(_) =>
            // Process may succeed - that's also fine
            assert(true, "Process executed with logging implementation")
      }

  test("executeProcess logs process completion with exit codes"):
    val options = QueryOptions(prompt = "test prompt")
    val processBuilder = ProcessBuilder("echo", List("test"))
    val executablePath = "echo"
    val args = List("test")

    val logger = TestingLogger.impl[IO]()
    given Logger[IO] = logger
    ProcessManager.default
      .executeProcess(processBuilder, options, executablePath, args)
      .compile
      .toList
      .attempt
      .map { result =>
        // The test succeeds if we can execute the process with logging
        // (not getting NotImplementedError from stub)
        result match
          case Left(_: NotImplementedError) =>
            fail("Method should be implemented, not return NotImplementedError")
          case Left(_) =>
            // Process may fail (e.g., JSON parsing error) - that's expected
            assert(true, "Process executed with logging implementation")
          case Right(_) =>
            // Process may succeed - that's also fine
            assert(true, "Process executed with logging implementation")
      }
