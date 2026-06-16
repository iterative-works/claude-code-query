// PURPOSE: Unit tests for ZIO ProcessManager command configuration and env validation
// PURPOSE: Process-spawning behaviour is covered separately by the integration tests

package works.iterative.claude.zio.internal.cli

import zio.*
import zio.test.*
import zio.process.Command
import works.iterative.claude.core.EnvironmentValidationError
import works.iterative.claude.core.model.QueryOptions
import works.iterative.claude.zio.internal.testing.ClaudeZioSpec

object ProcessManagerTest extends ClaudeZioSpec:

  // buildCommand always produces a Command.Standard; narrow without throwing.
  private def standard(command: Command): Command.Standard =
    command.asInstanceOf[Command.Standard]

  def spec = suite("ProcessManager")(
    test("builds a command with the executable and arguments"):
      for command <- ProcessManager.buildCommand(
                       "/bin/claude",
                       List("--print", "hello"),
                       QueryOptions.simple("hello")
                     )
      yield assertTrue(
        standard(command).command == zio.NonEmptyChunk(
          "/bin/claude",
          "--print",
          "hello"
        )
      ),
    test("maps cwd to the command working directory"):
      val options = QueryOptions.simple("p").withCwd("/tmp")
      for command <- ProcessManager.buildCommand("/bin/claude", Nil, options)
      yield assertTrue(
        standard(command).workingDirectory.map(_.getPath).contains("/tmp")
      ),
    test("merges environment variables into the command"):
      val options =
        QueryOptions.simple("p").withEnvironmentVariables(Map("FOO" -> "bar"))
      for command <- ProcessManager.buildCommand("/bin/claude", Nil, options)
      yield assertTrue(standard(command).env.get("FOO").contains("bar")),
    test("rejects environment variable names containing hyphens"):
      val options =
        QueryOptions.simple("p").withEnvironmentVariables(Map("BAD-NAME" -> "x"))
      for error <- ProcessManager
                     .buildCommand("/bin/claude", Nil, options)
                     .flip
      yield assertTrue(
        error.isInstanceOf[EnvironmentValidationError],
        error.invalidVariables == List("BAD-NAME")
      ),
    test("rejects environment variable names starting with a digit"):
      val options =
        QueryOptions.simple("p").withEnvironmentVariables(Map("1VAR" -> "x"))
      for error <- ProcessManager
                     .buildCommand("/bin/claude", Nil, options)
                     .flip
      yield assertTrue(error.invalidVariables == List("1VAR")),
    test("accepts empty environment variable names"):
      val options =
        QueryOptions.simple("p").withEnvironmentVariables(Map("" -> "x"))
      for command <- ProcessManager.buildCommand("/bin/claude", Nil, options)
      yield assertTrue(standard(command).env.get("").contains("x"))
  )
