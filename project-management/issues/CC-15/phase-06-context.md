# Phase 06: Error handling - process crash and malformed JSON

**Issue:** CC-15 - Support persistent two-way conversations with a single Claude Code session
**Estimated Effort:** 6-8 hours
**Status:** Not started

## Goals

1. Detect when the underlying CLI process crashes or exits unexpectedly during an active session and surface this as a typed `CLIError` through the send/stream API
2. Ensure malformed JSON lines on stdout are handled gracefully in the continuous-reading session context (logged and skipped without killing the session)
3. Mark sessions as invalid after process death so subsequent `send` calls fail with a clear error rather than silently hanging or producing confusing exceptions
4. Apply error handling consistently across both the direct (Ox) and effectful (cats-effect/fs2) Session implementations

## Scope

### In scope

- New `SessionError` case class (or similar) in the `CLIError` hierarchy for session-specific failures (process died, session closed)
- **Direct API:** Process liveness check in `send()` before writing to stdin; stdout EOF detection in `stream()` producing a typed error instead of silent completion; `close()` idempotency
- **Effectful API:** Process liveness detection in `send` (before enqueuing to stdinQueue); stdout reader fiber propagating process death through the messageQueue as an error; stream surfacing the error to the caller
- Malformed JSON resilience: verify that the existing JSON parsing error handling (log + skip in direct; log + skip in effectful) works correctly in the continuous session context where the session must survive bad lines and continue reading
- Session state tracking: an `isAlive` flag (AtomicBoolean / Ref[IO, Boolean]) that is set to false when the process exits, enabling `send` to fail fast

### Out of scope

- Automatic session reconnection or retry after process crash
- Timeout handling for individual turns (callers can compose this via `stream.timeout` / `Flow.timeout`)
- Concurrent send protection (per prior decision: CLI handles queueing)
- Control messages (`control_request` / `control_response`)
- Changes to the query-mode (non-session) error handling

## Dependencies

**Prior phases delivered:**

- **Phase 01:** `SDKUserMessage` encoder, `ResultMessage` as end-of-turn delimiter, `KeepAliveMessage`/`StreamEventMessage` types, core `JsonParser` with `parseJsonLine` and `parseMessage`
- **Phase 02:** `SessionOptions` with fluent builders, `CLIArgumentBuilder.buildSessionArgs`
- **Phase 03:** Direct `Session` trait (`send`/`stream`/`close`/`sessionId`), `SessionProcess` managing process lifecycle, init message reading, `SessionMockCliScript` test infrastructure
- **Phase 04:** Multi-turn tests proving sequential send/stream isolation, session ID propagation across turns
- **Phase 05:** Effectful `Session` trait (`send`/`stream`/`sessionId`), Resource-based `SessionProcess` with background fibers (stdin writer, stdout reader, stderr capture), `Queue`-based message routing, init message extraction

**Key existing code to build on:**

- `CLIError` hierarchy in `core/src/.../core/CLIError.scala`: sealed trait with `CLINotFoundError`, `ProcessError`, `ProcessExecutionError`, `JsonParsingError`, `ProcessTimeoutError`, `ConfigurationError`, `EnvironmentValidationError`
- Direct `SessionProcess.stream()` (lines 50-75): reads `stdoutReader.readLine()` in a loop; on `null` (EOF) sets `done = true` and silently ends the flow; on `Left(error)` from JSON parsing, logs the error but continues reading
- Effectful `SessionProcess.startStdoutReader` (lines 88-113): reads stdout as a stream, parses JSON; on `Left(err)` calls `IO.raiseError(err)` which terminates the stdout reader fiber; guarantees `messageQueue.offer(None)` on completion
- Direct `SessionProcess.send()` (lines 41-48): writes to `stdinWriter` with no process liveness check; will throw `IOException` if the pipe is broken
- Effectful `SessionImpl.send()` (lines 173-185): offers to `stdinQueue` with no process liveness check; will not fail even if process is dead (queue accepts the message, but stdin writer fiber will fail)

## Approach

### 1. Extend CLIError hierarchy with session-specific errors

Add to `core/src/works/iterative/claude/core/CLIError.scala`:

```scala
case class SessionProcessDied(
    exitCode: Option[Int],
    stderr: String
) extends CLIError:
  val message = exitCode match
    case Some(code) => s"Session process exited unexpectedly with code $code. $stderr"
    case None       => s"Session process is not alive. $stderr"

case class SessionClosedError(sessionId: String) extends CLIError:
  val message = s"Cannot send to closed session '$sessionId'"
```

`SessionProcessDied` covers the case where the CLI process crashes mid-session. `SessionClosedError` covers calling `send` after `close()` (direct) or after the Resource is released (effectful -- though this is typically caught by the Resource lifecycle).

### 2. Direct API error handling

**`SessionProcess` changes:**

a) Add a process liveness flag:
```scala
private val alive = new AtomicBoolean(true)
```

b) In `send()`, check `process.isAlive` before writing. If dead, throw `SessionProcessDied` with the exit code and any captured stderr. Also check the `alive` flag for the post-`close()` case.

c) In `stream()`, when `readLine()` returns `null` (stdout EOF), check if this was expected (session was closed by user) vs. unexpected (process crashed). If the process exited with a non-zero exit code mid-turn (before a `ResultMessage` was seen), emit or throw `SessionProcessDied`. If `close()` was called, treat EOF as normal termination.

d) For malformed JSON: the current behavior in `stream()` (lines 73-74) logs the error and continues the loop. This is already correct for session resilience. Verify with a test that a malformed JSON line mid-turn does not break the session.

e) Make `close()` idempotent by checking and setting the `alive` flag.

**Error propagation detail for `stream()`:**

Currently, when stdout returns `null`, the direct `stream()` just sets `done = true` and the Flow completes. This means if the process crashes mid-turn, the caller gets an incomplete message stream with no error signal. The fix is:
- Track whether a `ResultMessage` was emitted during the current `stream()` call
- If `readLine()` returns `null` and no `ResultMessage` was emitted, the turn ended abnormally
- In that case, throw `SessionProcessDied` so the caller sees a typed error

### 3. Effectful API error handling

**`SessionProcess` / `SessionImpl` changes:**

a) Add a process alive `Ref[IO, Boolean]` initialized to `true`.

b) In `SessionImpl.send()`, check the alive ref before offering to the stdinQueue. If dead, raise `SessionProcessDied`.

c) In `startStdoutReader`, the current behavior on `Left(err)` is `IO.raiseError(err)` which terminates the fiber and guarantees `messageQueue.offer(None)`. This means a JSON parsing error kills the entire stdout reader. **This needs fixing for session resilience:** malformed JSON should be logged and skipped (matching the direct API behavior), not raised as a fatal error. Change:
   - `case Left(err) => IO.raiseError(err)` to `case Left(err) => logger.error(s"JSON parsing error: ${err.getMessage}").as(None: Option[Message])`
   - This way, malformed lines are logged and filtered out, and the stdout reader continues

d) When the stdout stream completes (process exits), before offering `None` to the messageQueue, check the process exit code. If non-zero, set alive ref to `false` and offer an error marker. The simplest approach: change the messageQueue type to `Queue[IO, Option[Either[CLIError, Message]]]`, or use a separate error Ref that `stream` checks after receiving `None`.

A simpler approach: keep the current `Queue[IO, Option[Message]]` but add a `Ref[IO, Option[CLIError]]` for the process error. When the stdout reader finishes, it checks the exit code and sets the error ref if non-zero. When `stream` sees `None` from the queue (no `ResultMessage` received for the current turn), it checks the error ref and raises the error if present.

e) After process death is detected, set `alive` ref to `false` so subsequent `send` calls fail immediately.

### 4. Malformed JSON in session context -- verify existing behavior

**Direct API:** The `stream()` method at line 73-74 handles `Left(error)` by logging and continuing the loop. This is already correct. Write a test to confirm.

**Effectful API:** The stdout reader at line 101 handles `Left(err)` with `IO.raiseError(err)`, which terminates the reader fiber. **This is a bug for session mode** -- a single malformed line kills the entire session. Fix this to log and skip (see 3c above). Write a test to confirm the fix.

### 5. Mock script enhancements for error testing

Extend `SessionMockCliScript` with:
- A script that crashes (exits with non-zero code) after emitting partial output for a turn
- A script that emits malformed JSON lines interspersed with valid messages
- A script that crashes between turns (after completing one turn, before reading the next prompt)

## Files to Modify/Create

### Files to modify

- `core/src/works/iterative/claude/core/CLIError.scala` -- add `SessionProcessDied` and `SessionClosedError`
- `direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala` -- add liveness checking in `send()`, error detection on EOF in `stream()`, idempotent `close()`
- `effectful/src/works/iterative/claude/effectful/internal/cli/SessionProcess.scala` -- add liveness checking in `send`, fix malformed JSON handling in stdout reader, process death detection, alive ref
- `direct/test/src/works/iterative/claude/direct/internal/testing/SessionMockCliScript.scala` -- add crash/malformed-JSON script generators

### New files

- `direct/test/src/works/iterative/claude/direct/SessionErrorTest.scala` -- unit tests for error scenarios (direct)
- `direct/test/src/works/iterative/claude/direct/SessionErrorIntegrationTest.scala` -- integration tests with crash/malformed scripts (direct)
- `effectful/test/src/works/iterative/claude/effectful/SessionErrorTest.scala` -- unit tests for error scenarios (effectful)
- `effectful/test/src/works/iterative/claude/effectful/SessionErrorIntegrationTest.scala` -- integration tests with crash/malformed scripts (effectful)

### Reference files (no changes expected)

- `core/src/works/iterative/claude/core/parsing/JsonParser.scala` -- core pure parsing (already handles errors correctly)
- `effectful/src/works/iterative/claude/effectful/internal/parsing/JsonParser.scala` -- effectful parsing wrapper (returns `Either`, does not throw)
- `direct/src/works/iterative/claude/direct/internal/parsing/JsonParser.scala` -- direct parsing (returns `Either`, does not throw)
- `direct/src/works/iterative/claude/direct/Session.scala` -- trait definition (no API changes)
- `effectful/src/works/iterative/claude/effectful/Session.scala` -- trait definition (no API changes)

## Testing Strategy

### Unit tests -- Direct (`SessionErrorTest.scala`)

1. **`send` after `close()` throws `SessionClosedError`** -- close session, call send, expect typed error
2. **`send` to dead process throws `SessionProcessDied`** -- use a script that exits immediately, attempt send, expect typed error
3. **`stream` on dead process surfaces `SessionProcessDied`** -- use a script that crashes mid-turn (emits partial output then exits), stream should raise typed error
4. **Malformed JSON line is skipped in `stream()`** -- use a script that emits invalid JSON between valid messages, verify valid messages are received and the malformed line is logged
5. **`close()` is idempotent** -- calling close() twice does not throw
6. **Session remains functional after malformed JSON** -- emit malformed line, then valid assistant + result; verify stream completes normally

### Integration tests -- Direct (`SessionErrorIntegrationTest.scala`)

1. **Process crash mid-turn with mock script** -- script emits assistant message then exits with code 1; verify `stream()` raises `SessionProcessDied` with exit code
2. **Process crash between turns** -- script completes turn 1, then exits before turn 2; verify second `send` or `stream` raises `SessionProcessDied`
3. **Malformed JSON recovery with mock script** -- script emits `{bad json}` followed by valid assistant + result; verify all valid messages arrive and session completes normally
4. **Multiple malformed lines do not accumulate errors** -- script emits several bad lines interspersed with valid messages; verify the session handles all of them

### Unit tests -- Effectful (`SessionErrorTest.scala`)

1. **`send` to dead process raises `SessionProcessDied`** -- script exits immediately, send should fail with typed error
2. **`stream` on dead process surfaces `SessionProcessDied`** -- script crashes mid-turn, stream should raise typed error
3. **Malformed JSON line is skipped in stream** -- verify malformed line is logged and skipped, valid messages arrive
4. **Session remains functional after malformed JSON** -- verify multi-turn works even with bad JSON lines in the output

### Integration tests -- Effectful (`SessionErrorIntegrationTest.scala`)

1. **Process crash mid-turn** -- script exits with code 1 after partial output; verify stream raises `SessionProcessDied`
2. **Process crash between turns** -- script completes turn 1, crashes before turn 2; verify second send/stream raises `SessionProcessDied`
3. **Malformed JSON recovery** -- script emits bad JSON + valid messages; verify valid messages arrive
4. **Resource cleanup after process crash** -- verify Resource finalizer runs without hanging after process dies

### E2E tests

No new E2E tests needed. Process crashes and malformed JSON are not reproducible with the real CLI in a controlled way. The existing E2E tests validate normal session operation.

## Acceptance Criteria

1. New `SessionProcessDied` error type exists in `CLIError` hierarchy, carrying optional exit code and stderr content
2. New `SessionClosedError` error type exists for post-close `send` attempts (direct API)
3. **Direct API:** `send()` on a dead/closed session throws a typed `CLIError` (not `IOException` or `NullPointerException`)
4. **Direct API:** `stream()` raises `SessionProcessDied` when the process exits mid-turn (before `ResultMessage`)
5. **Direct API:** `stream()` gracefully handles malformed JSON lines (logs and skips them, session continues)
6. **Direct API:** `close()` is idempotent
7. **Effectful API:** `send` on a dead session raises `SessionProcessDied` in the IO
8. **Effectful API:** `stream` raises `SessionProcessDied` when the process exits mid-turn
9. **Effectful API:** Malformed JSON lines are logged and skipped (fixing the current behavior where they terminate the stdout reader fiber)
10. **Effectful API:** Resource cleanup completes without hanging after process crash
11. All new unit and integration tests pass with no compilation warnings
12. All existing session tests (direct + effectful) continue to pass (no regressions)
13. All existing query tests (direct + effectful) continue to pass (no regressions)

## Key Implementation Notes

### Effectful malformed JSON bug

The current effectful `SessionProcess.startStdoutReader` (line 101) handles JSON parsing errors with `IO.raiseError(err)`, which terminates the stdout reader fiber. This is correct for query mode (where a malformed line is a fatal error for a single invocation) but wrong for session mode (where the session should survive and continue). This must be fixed to log and skip, matching the direct API behavior.

### Direct API stdout EOF semantics

The current direct `SessionProcess.stream()` treats `readLine() == null` as normal completion (line 57-58). In a healthy session, stdout EOF means the process exited. If this happens mid-turn (no `ResultMessage` seen), it is an error condition that must surface to the caller rather than silently ending the stream.

### Process exit code availability

For the direct API, `process.exitValue()` returns the exit code (blocking if not yet exited) and `process.isAlive` checks liveness. For the effectful API, `process.exitValue: IO[Int]` semantically waits for exit. The liveness check approach differs: direct can poll `process.isAlive`; effectful should use the alive Ref set by the stdout reader fiber completion handler.

### Stdin write failure vs. liveness check

When the process is dead, writing to stdin throws `IOException` (broken pipe). The liveness check in `send` provides a better error message than catching the IOException. However, both paths should result in a `SessionProcessDied` error -- the liveness check is the fast path, and the IOException catch is the fallback.
