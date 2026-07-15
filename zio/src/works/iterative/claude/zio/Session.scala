// PURPOSE: Trait for an active Claude Code CLI session — a stream of messages plus fire-and-forget input
// PURPOSE: Completion is readable monotone state (drop-immune), interrupt is a real vendor control request

package works.iterative.claude.zio

import zio.*
import zio.stream.ZStream
import works.iterative.claude.core.CLIError
import works.iterative.claude.core.model.{
  InterruptOutcome,
  Message,
  ResultMessage,
  SessionEnd,
  SessionInfo,
  SessionState,
  UserInput
}

/** An active Claude Code session backed by a long-lived CLI process.
  *
  * A session is a stream of messages plus a way to inject user input. Turns are
  * not modelled: the CLI owns turn boundaries, and a message read from `events`
  * is not "the reply to your last send" (a background task finishing emits its
  * own result with no send in flight, and a send issued mid-turn is merged into
  * the running turn). See docs/adr/0001-session-is-a-stream-not-turns.md.
  *
  * Completion is observed as **state**, not as a delivered event: `send` is
  * fire-and-forget and the reliable "the turn finished" signal is the monotone
  * `resultsSeen` counter behind [[state]]. Because the counter never decreases
  * and is readable at any time, waiting on `resultsSeen > n` has no window to
  * lose a race in — [[sendAndAwait]] closes the completion race outright rather
  * than narrowing it.
  *
  * The session is acquired within a `Scope`, whose finalizers shut down the
  * process on both normal exit and error.
  */
trait Session:

  /** The session's identifying information. Blocks until the CLI names the
    * session — via its init message or the first result — rather than returning
    * a sentinel; fails with a [[CLIError]] if the process ends before naming
    * it.
    */
  def info: IO[CLIError, SessionInfo]

  /** Fire-and-forget. Writes the input to the process at the CLI's next
    * opportunity (terminal-style injection) and returns when written, NOT when
    * answered. The CLI decides whether this starts a new turn or merges into a
    * running one; that decision is unobservable, so there is no correlation, no
    * promise, and no turn object. The input is encoded as a content-block
    * array, so verbatim user text never concatenates with context.
    */
  def send(input: UserInput): IO[CLIError, Unit]

  /** Stops the running turn for real, via the vendor control protocol
    * (`control_request`/`control_response` correlated by `request_id`). Unlike
    * abandoning [[events]] — which only stops us reading while the agent keeps
    * working — this stops the agent; the session survives. The interrupted
    * turn's error result still bumps `resultsSeen`, so a waiter wakes with
    * `is_error` rather than hanging.
    */
  def interrupt: IO[CLIError, InterruptOutcome]

  /** The live message view, fanned out from an internal Hub so any number of
    * consumers see the full stream.
    *
    * LOSSY BY CONTRACT — a bounded sliding buffer for RENDERING only. Nothing
    * that must not be lost may come from here: under load the oldest buffered
    * messages are dropped. The completion signal lives in [[state]], not here.
    * The stream completes when the session's process ends.
    */
  def events: ZStream[Any, Nothing, Message]

  /** The session's current activity state — always readable, never delivered,
    * so never dropped. This, not [[events]], is the completion signal.
    */
  def state: UIO[SessionState]

  /** State transitions as they happen. May skip intermediate states under load;
    * [[state]] is the ground truth to read.
    */
  def stateChanges: ZStream[Any, Nothing, SessionState]

  /** Resolves once the session's process has ended. Always eventually
    * completes; the same value is also readable as [[SessionState.ended]].
    */
  def terminated: IO[Nothing, SessionEnd]

  /** Waits for the next real result after the observed `resultsSeen` value.
    *
    * Wakes on the monotone predicate `resultsSeen > seen`. Returns IMMEDIATELY
    * if the counter has already advanced past `seen` — including a result that
    * landed before this call — because the state is read, not awaited. Does not
    * hang on process death: if the process ends before a new result, it fails
    * with the recorded [[CLIError]].
    *
    * Note the envelope caveat: if two results land between observations, this
    * returns the latter — the completion signal is reliable, but a superseded
    * result's envelope is not retained (there is no result journal).
    */
  def awaitResultAfter(seen: Long): IO[CLIError, ResultMessage]

  /** Reads the counter, sends, and waits for the next real result — the whole
    * consumer need in one call. Under merge semantics the returned result may
    * answer more than this one input (probe #7); that is the honest reading of
    * what the CLI did.
    */
  def sendAndAwait(input: UserInput): IO[CLIError, ResultMessage] =
    for
      s0 <- state
      _ <- send(input)
      r <- awaitResultAfter(s0.resultsSeen)
    yield r
