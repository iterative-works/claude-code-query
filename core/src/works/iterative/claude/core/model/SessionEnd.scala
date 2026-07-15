// PURPOSE: The terminal state of a session — how the long-lived CLI process ended
// PURPOSE: Carries the exit code and the error a waiter should fail with, so death never hangs a waiter

package works.iterative.claude.core.model

import works.iterative.claude.core.CLIError

/** How a session ended.
  *
  * `exitCode` is the process exit code when one was observed. `error` is the
  * [[CLIError]] a pending waiter should fail with — present when the process
  * died abnormally, `None` on a clean shutdown.
  */
case class SessionEnd(exitCode: Option[Int], error: Option[CLIError])
