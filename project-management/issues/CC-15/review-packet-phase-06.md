---
generated_from: ca775206d06d33158507ea3203808bd2df83b5b5
generated_at: 2026-04-05T16:33:59Z
branch: CC-15-phase-06
issue_id: CC-15
phase: 6
files_analyzed:
  - core/src/works/iterative/claude/core/CLIError.scala
  - direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala
  - direct/test/src/works/iterative/claude/direct/SessionErrorIntegrationTest.scala
  - direct/test/src/works/iterative/claude/direct/SessionErrorTest.scala
  - direct/test/src/works/iterative/claude/direct/SessionTest.scala
  - direct/test/src/works/iterative/claude/direct/internal/testing/SessionMockCliScript.scala
  - effectful/src/works/iterative/claude/effectful/internal/cli/SessionProcess.scala
  - effectful/test/src/works/iterative/claude/effectful/SessionErrorIntegrationTest.scala
  - effectful/test/src/works/iterative/claude/effectful/SessionErrorTest.scala
---

# Review Packet: Phase 6 - Error Handling (Process Crash and Malformed JSON)

## Goals

This phase hardens the session API by making error conditions surface as typed
`CLIError` values rather than raw exceptions or silent incomplete streams.

Key objectives:

- Add two new error types to the `CLIError` hierarchy: `SessionProcessDied`
  (process crashed) and `SessionClosedError` (send after explicit close)
- Direct API: detect process death before writing to stdin and detect unexpected
  stdout EOF mid-turn (before `ResultMessage`)
- Direct API: make `close()` idempotent
- Effectful API: fix a bug where a single malformed JSON line would kill the
  entire stdout reader fiber; change to log-and-skip to match the direct API
- Effectful API: detect process death in `send` via an `aliveRef`, and surface
  process-death errors from `stream` via a `pendingErrorRef` set by the stdout
  reader fiber
- Ensure all error paths carry the exit code and stderr context where available

## Scenarios

- [ ] `send` after `close()` throws `SessionClosedError` carrying the session ID (direct)
- [ ] `close()` called twice does not throw (idempotent)
- [ ] `send` to a dead process throws `SessionProcessDied` with an exit code (direct)
- [ ] `stream` raises `SessionProcessDied` when the process exits mid-turn before `ResultMessage` (direct)
- [ ] Malformed JSON line mid-turn is skipped; subsequent valid messages and `ResultMessage` arrive normally (direct)
- [ ] `send` to a dead process raises `SessionProcessDied` in IO (effectful)
- [ ] `stream` raises `SessionProcessDied` when the process exits mid-turn before `ResultMessage` (effectful)
- [ ] Malformed JSON line is logged and skipped; valid messages in the same turn arrive (effectful)
- [ ] Process crash between turns: second `send` raises `SessionProcessDied` carrying the exit code (both APIs)
- [ ] Multiple malformed JSON lines do not accumulate errors; all valid messages arrive
- [ ] Resource cleanup completes without hanging after process dies (effectful)

## Entry Points

| File | Method/Class | Why Start Here |
|------|--------------|----------------|
| `core/src/works/iterative/claude/core/CLIError.scala` | `SessionProcessDied`, `SessionClosedError` | New error types; defines what callers catch |
| `direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala` | `send()`, `stream()`, `close()` | All three methods changed; liveness logic is here |
| `effectful/src/works/iterative/claude/effectful/internal/cli/SessionProcess.scala` | `startStdoutReader`, `SessionImpl.send`, `SessionImpl.stream` | Bug fix (malformed JSON) and new liveness/error-propagation machinery |
| `direct/test/src/works/iterative/claude/direct/internal/testing/SessionMockCliScript.scala` | `createCrashMidTurnScript`, `createCrashBetweenTurnsScript`, `createImmediateExitScript` | New script builders used by all error tests |

## Diagrams

### Error Type Hierarchy

```
CLIError (sealed trait)
├── CLINotFoundError
├── ProcessError
├── ProcessExecutionError
├── JsonParsingError
├── ProcessTimeoutError
├── ConfigurationError
├── EnvironmentValidationError
├── SessionProcessDied(exitCode: Option[Int], stderr: String)   ← NEW
└── SessionClosedError(sessionId: String)                        ← NEW
```

### Direct API: Process Liveness Check Flow

```
send(prompt)
  │
  ├─ alive == false ──→ throw SessionClosedError(sessionId)
  │
  ├─ !process.isAlive ──→ alive=false → throw SessionProcessDied(exitCode, stderr)
  │
  └─ write to stdinWriter
       │
       └─ IOException ──→ alive=false → throw SessionProcessDied(exitCode, e.getMessage)

stream()
  │
  ├─ readLine() → line     ──→ parse, emit, check for ResultMessage (resultSeen=true)
  │
  └─ readLine() == null (EOF)
       │
       ├─ resultSeen == true  ──→ normal end (process exited cleanly after turn)
       │
       └─ resultSeen == false ──→ alive=false → throw SessionProcessDied(exitCode, stderr)

close()
  │
  └─ alive.compareAndSet(true→false)
       ├─ true  ──→ close stdinWriter, waitFor(5s), close stdoutReader
       └─ false ──→ log "already closed", return
```

### Effectful API: Error Propagation via Refs

```
SessionProcess Resource contains:
  aliveRef:       Ref[IO, Boolean]           (true while process is running)
  pendingErrorRef: Ref[IO, Option[CLIError]] (set by stdout reader on bad exit)
  messageQueue:   Queue[IO, Option[Message]] (None = EOF sentinel)

startStdoutReader (background fiber):
  ┌──────────────────────────────────────────────────────┐
  │ for each stdout line:                                │
  │   Left(parseError) ──→ log.warn + skip (was raiseError, now fixed) │
  │   Right(Some(msg)) ──→ queue.offer(Some(msg))        │
  │   Right(None)      ──→ discard                       │
  │                                                      │
  │ on stream complete (.flatMap after .drain):          │
  │   aliveRef.set(false)                                │
  │   exitValue.attempt:                                 │
  │     Right(code != 0) ──→ pendingErrorRef.set(Some(SessionProcessDied(code,""))) │
  │     _               ──→ IO.unit                     │
  │                                                      │
  │ .guarantee: queue.offer(None)  ← always signals EOF │
  └──────────────────────────────────────────────────────┘

SessionImpl.send:
  aliveRef.get
    false ──→ pendingErrorRef.get
               Some(err) ──→ IO.raiseError(err)
               None      ──→ IO.raiseError(SessionProcessDied(None, ""))
    true  ──→ offer to stdinQueue (normal path)

SessionImpl.stream:
  resultSeenRef = Ref(false)
  fromQueueNoneTerminated(messageQueue)
    .evalTap { ResultMessage ──→ resultSeenRef.set(true) }
    .takeThrough(!_.isResultMessage)
    .onFinalize:
      resultSeenRef.get
        true  ──→ IO.unit (normal completion)
        false ──→ pendingErrorRef.get
                   Some(err) ──→ IO.raiseError(err)
                   None      ──→ IO.unit
```

## Test Summary

### New Tests

| Test File | Type | Scenarios Covered |
|-----------|------|-------------------|
| `direct/test/.../SessionErrorTest.scala` | Unit | `send` after `close()` → `SessionClosedError`; idempotent `close()`; `send` to dead process; `stream` mid-turn crash; malformed JSON skipped; session functional after bad JSON |
| `direct/test/.../SessionErrorIntegrationTest.scala` | Integration | crash mid-turn with exit code 42 (verified in error); crash between turns; malformed JSON recovery; multiple bad JSON lines |
| `effectful/test/.../SessionErrorTest.scala` | Unit | `send` to dead process; `stream` mid-turn crash; malformed JSON skipped; dead-process with exit code 0 (pendingError=None branch) |
| `effectful/test/.../SessionErrorIntegrationTest.scala` | Integration | crash mid-turn; crash between turns with exit code; malformed JSON recovery; Resource cleanup without hanging |

### Mock Script Builders Added

| Builder | Simulates |
|---------|-----------|
| `createImmediateExitScript(exitCode)` | Process exits before reading any stdin |
| `createCrashMidTurnScript(exitCode)` | Reads one stdin line, emits partial assistant message, exits without `ResultMessage` |
| `createCrashBetweenTurnsScript(exitCode)` | Completes turn 1 with `ResultMessage`, exits before turn 2 |
| `createMalformedJsonMidTurnScript()` | Valid assistant message, then `{bad json: not valid}`, then valid `ResultMessage` |

### Existing Tests

The existing `SessionTest.scala` was updated to narrow the caught exception type
from `Exception` to `SessionClosedError` in the post-`close()` test, confirming
the typed error replaces the previous untyped exception.

No other existing tests were modified. All query-mode and session-mode tests are
expected to continue passing.

## Files Changed

| File | Change |
|------|--------|
| `core/src/works/iterative/claude/core/CLIError.scala` | Added `SessionProcessDied` and `SessionClosedError` case classes |
| `direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala` | Liveness flag, guarded `send`/`close`, mid-turn EOF detection in `stream`, `captureRemainingStderr`, `underlyingProcess` accessor for tests |
| `effectful/src/works/iterative/claude/effectful/internal/cli/SessionProcess.scala` | `aliveRef`, `pendingErrorRef`, malformed JSON fix in `startStdoutReader`, liveness check in `send`, error check in `stream.onFinalize` |
| `direct/test/src/works/iterative/claude/direct/internal/testing/SessionMockCliScript.scala` | Four new script builders for error scenarios |
| `direct/test/src/works/iterative/claude/direct/SessionErrorTest.scala` | New — direct unit tests |
| `direct/test/src/works/iterative/claude/direct/SessionErrorIntegrationTest.scala` | New — direct integration tests |
| `direct/test/src/works/iterative/claude/direct/SessionTest.scala` | Narrowed caught exception type in post-close test |
| `effectful/test/src/works/iterative/claude/effectful/SessionErrorTest.scala` | New — effectful unit tests |
| `effectful/test/src/works/iterative/claude/effectful/SessionErrorIntegrationTest.scala` | New — effectful integration tests |
| `project-management/issues/CC-15/phase-06-tasks.md` | All tasks marked complete |
| `project-management/issues/CC-15/review-state.json` | Updated |
