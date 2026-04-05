# Phase 06 Tasks: Error handling - process crash and malformed JSON

## Setup

- [x] [setup] Add `SessionProcessDied(exitCode: Option[Int], stderr: String)` to `core/src/.../core/CLIError.scala`
- [x] [setup] Add `SessionClosedError(sessionId: String)` to `core/src/.../core/CLIError.scala`
- [x] [setup] Verify the new error types compile and integrate cleanly into the sealed `CLIError` hierarchy

## Tests

### Mock script enhancements

- [x] [test] Add `crashMidTurn(exitCode: Int)` script builder to `SessionMockCliScript` — emits a partial assistant message then exits with the given code (no ResultMessage)
- [x] [test] Add `crashBetweenTurns(exitCode: Int)` script builder — completes one full turn (with ResultMessage), then exits before reading the second prompt
- [x] [test] Add `malformedJsonMidTurn()` script builder — emits a valid assistant message, then an invalid JSON line, then a valid ResultMessage

### Direct API unit tests (`SessionErrorTest.scala`)

- [x] [test] `send` after `close()` throws `SessionClosedError`
- [x] [test] `close()` is idempotent — calling it twice does not throw
- [x] [test] `send` to a dead process throws `SessionProcessDied`
- [x] [test] `stream` raises `SessionProcessDied` when process exits mid-turn (before ResultMessage)
- [x] [test] Malformed JSON line mid-turn is skipped and stream completes normally with the valid messages
- [x] [test] Session remains functional after a malformed JSON line — valid assistant + ResultMessage arrive after the bad line

### Direct API integration tests (`SessionErrorIntegrationTest.scala`)

- [x] [test] Process crash mid-turn with mock script — script emits partial output then exits with code 1; verify `stream()` raises `SessionProcessDied` carrying the exit code
- [x] [test] Process crash between turns with mock script — first turn completes, process exits; verify second `send` or `stream` raises `SessionProcessDied`
- [x] [test] Malformed JSON recovery with mock script — bad JSON line followed by valid assistant + ResultMessage; verify valid messages arrive and stream completes normally
- [x] [test] Multiple malformed lines do not accumulate errors — several bad lines interspersed with valid messages; verify all valid messages arrive

### Effectful API unit tests (`SessionErrorTest.scala`)

- [x] [test] `send` to a dead process raises `SessionProcessDied` in IO
- [x] [test] `stream` raises `SessionProcessDied` when process exits mid-turn (before ResultMessage)
- [x] [test] Malformed JSON line is logged and skipped; valid messages in the same turn arrive normally
- [x] [test] Session remains functional after a malformed JSON line — next message in the stream arrives correctly

### Effectful API integration tests (`SessionErrorIntegrationTest.scala`)

- [x] [test] Process crash mid-turn — script exits with code 1 after partial output; verify stream raises `SessionProcessDied`
- [x] [test] Process crash between turns — script completes turn 1 then exits; verify second send/stream raises `SessionProcessDied`
- [x] [test] Malformed JSON recovery — bad JSON line followed by valid assistant + ResultMessage; verify valid messages arrive
- [x] [test] Resource cleanup after process crash — verify Resource finalizer completes without hanging when process dies

## Implementation

### Core

- [x] [impl] `CLIError.scala`: implement `SessionProcessDied.message` (with/without exit code) and `SessionClosedError.message`

### Direct API (`direct/src/.../internal/cli/SessionProcess.scala`)

- [x] [impl] Add `AtomicBoolean alive` flag to `SessionProcess`; set to `false` in `close()`
- [x] [impl] Make `close()` idempotent by guarding on the `alive` flag before closing streams/process
- [x] [impl] In `send()`, check `alive` flag (closed) and `process.isAlive` (crashed); throw `SessionClosedError` or `SessionProcessDied` accordingly; keep `IOException` catch as fallback that also produces `SessionProcessDied`
- [x] [impl] In `stream()`, track whether a `ResultMessage` was emitted during the current call; on `readLine() == null` (stdout EOF), if no `ResultMessage` was seen, throw `SessionProcessDied` with process exit code and captured stderr; if `ResultMessage` was seen, end normally

### Effectful API (`effectful/src/.../internal/cli/SessionProcess.scala`)

- [x] [impl] Add `Ref[IO, Boolean] alive` initialized to `true`
- [x] [impl] In `startStdoutReader`, change `case Left(err) => IO.raiseError(err)` to log the error and return `None` (filter it out) so malformed JSON lines are skipped rather than terminating the reader fiber
- [x] [impl] Add a `Ref[IO, Option[CLIError]] pendingError` to carry process-death errors out of the stdout reader fiber
- [x] [impl] In `startStdoutReader` completion handler: check process exit code; on non-zero, set `alive` to `false` and set `pendingError` to `SessionProcessDied`; always offer `None` to `messageQueue` as the termination sentinel
- [x] [impl] In `SessionImpl.send()`, check the `alive` ref before offering to `stdinQueue`; if dead, raise `SessionProcessDied`
- [x] [impl] In `SessionImpl.stream`, after receiving `None` from the queue without a `ResultMessage`, check `pendingError` ref and raise the error if present

## Integration

- [x] [integration] Verify all new direct error tests pass (`./mill direct.test`)
- [x] [integration] Verify all new effectful error tests pass (`./mill effectful.test`)
- [x] [integration] Verify no regressions in existing direct session and query tests
- [x] [integration] Verify no regressions in existing effectful session and query tests
- [x] [integration] Verify no compilation warnings across all modules (`./mill __.compile`)
