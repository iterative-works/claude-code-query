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

  /** A session-mode script that emits init, then for the FIRST prompt only
    * emits the turn messages once and afterwards drains stdin silently — so two
    * sends produce a single result (the CLI's mid-turn merge, probe #7).
    */
  def mergedTurnScript(
      initMessage: String,
      turnMessages: List[String]
  ): os.Path =
    val builder = new StringBuilder("#!/bin/bash\n")
    builder.append(s"echo '${escape(initMessage)}'\n")
    builder.append("read -r _first\n")
    turnMessages.foreach(line => builder.append(s"echo '${escape(line)}'\n"))
    builder.append("while IFS= read -r _line; do :; done\n")
    writeScript(builder.toString)

  /** A session-mode script that, for each prompt, sleeps before emitting the
    * turn messages — so `send` is observably fire-and-forget (it returns before
    * the result lands).
    */
  def delayedTurnScript(
      initMessage: String,
      turnMessages: List[String],
      delaySeconds: Int
  ): os.Path =
    val builder = new StringBuilder("#!/bin/bash\n")
    builder.append(s"echo '${escape(initMessage)}'\n")
    builder.append("while IFS= read -r _line; do\n")
    builder.append(s"  sleep $delaySeconds\n")
    turnMessages.foreach(line => builder.append(s"  echo '${escape(line)}'\n"))
    builder.append("done\n")
    writeScript(builder.toString)

  /** A session-mode script that appends every stdin line to `capturePath` and
    * echoes the turn messages for each prompt — so a test can inspect the exact
    * bytes written to stdin.
    */
  def stdinCaptureScript(
      initMessage: String,
      capturePath: os.Path,
      turnMessages: List[String]
  ): os.Path =
    val builder = new StringBuilder("#!/bin/bash\n")
    builder.append(s"echo '${escape(initMessage)}'\n")
    builder.append("while IFS= read -r line; do\n")
    builder.append(s"  printf '%s\\n' \"$$line\" >> '${capturePath.toString}'\n")
    turnMessages.foreach(line => builder.append(s"  echo '${escape(line)}'\n"))
    builder.append("done\n")
    writeScript(builder.toString)

  /** A session-mode script that answers a control_request by echoing a
    * control_response correlated to the request's `request_id` plus the given
    * interrupted-turn error result, and answers a normal prompt with the turn
    * messages — so the session survives an interrupt and later turns work.
    */
  def interruptSessionScript(
      initMessage: String,
      errorResult: String,
      turnMessages: List[String]
  ): os.Path =
    val builder = new StringBuilder("#!/bin/bash\n")
    builder.append(s"echo '${escape(initMessage)}'\n")
    builder.append("while IFS= read -r line; do\n")
    builder.append("  case \"$line\" in\n")
    builder.append("    *control_request*)\n")
    builder.append(
      "      rid=$(printf '%s' \"$line\" | sed -n 's/.*\"request_id\":\"\\([^\"]*\\)\".*/\\1/p')\n"
    )
    builder.append(
      "      printf '{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":\"%s\",\"response\":{\"still_queued\":[]}}}\\n' \"$rid\"\n"
    )
    builder.append(s"      echo '${escape(errorResult)}'\n")
    builder.append("      ;;\n")
    builder.append("    *)\n")
    turnMessages.foreach(line => builder.append(s"      echo '${escape(line)}'\n"))
    builder.append("      ;;\n")
    builder.append("  esac\n")
    builder.append("done\n")
    writeScript(builder.toString)

  /** A session-mode script that emits init, reads one prompt, emits a partial
    * message, then exits non-zero before any ResultMessage — simulating a
    * process that dies mid-turn.
    */
  def crashMidTurnScript(
      initMessage: String,
      partialMessage: String,
      exitCode: Int = 1,
      stderr: Option[String] = None
  ): os.Path =
    val builder = new StringBuilder("#!/bin/bash\n")
    builder.append(s"echo '${escape(initMessage)}'\n")
    builder.append("read -r _line\n")
    builder.append(s"echo '${escape(partialMessage)}'\n")
    stderr.foreach(line => builder.append(s"echo '${escape(line)}' >&2\n"))
    builder.append(s"exit $exitCode\n")
    writeScript(builder.toString)
