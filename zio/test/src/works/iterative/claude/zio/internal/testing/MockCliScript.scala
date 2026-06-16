// PURPOSE: Generates temporary executable shell scripts that simulate the Claude Code CLI
// PURPOSE: Used by integration tests to exercise real process spawning without the real CLI

package works.iterative.claude.zio.internal.testing

/** Creates temporary executable bash scripts that mimic the Claude Code CLI for
  * integration tests: query mode emits JSON lines then exits; session mode keeps
  * reading newline-delimited prompts from stdin and echoes a turn for each.
  */
object MockCliScript:

  private def writeScript(content: String): os.Path =
    val dir  = os.temp.dir(prefix = "mock-claude-zio-")
    val path = dir / "mock-claude"
    os.write(path, content)
    os.perms.set(path, "rwxr-xr-x")
    path

  private def escape(line: String): String = line.replace("'", "'\\''")

  /** A query-mode script: emits the given JSON lines (and optional stderr), then
    * exits with the given code.
    */
  def queryScript(
      messages: List[String],
      exitCode: Int = 0,
      stderr: Option[String] = None
  ): os.Path =
    val builder = new StringBuilder("#!/bin/bash\n")
    stderr.foreach(line => builder.append(s"echo '${escape(line)}' >&2\n"))
    messages.foreach(line => builder.append(s"echo '${escape(line)}'\n"))
    if exitCode != 0 then builder.append(s"exit $exitCode\n")
    writeScript(builder.toString)

  /** A script that sleeps without producing output, to exercise timeouts. */
  def hangingScript(sleepSeconds: Int = 30): os.Path =
    writeScript(s"#!/bin/bash\nsleep $sleepSeconds\n")

  /** A session-mode script: emits the init message, then echoes the turn
    * messages for every newline-delimited prompt read from stdin, exiting when
    * stdin closes.
    */
  def sessionScript(
      initMessage: String,
      turnMessages: List[String]
  ): os.Path =
    val builder = new StringBuilder("#!/bin/bash\n")
    builder.append(s"echo '${escape(initMessage)}'\n")
    builder.append("while IFS= read -r line; do\n")
    turnMessages.foreach(line => builder.append(s"  echo '${escape(line)}'\n"))
    builder.append("done\n")
    writeScript(builder.toString)
