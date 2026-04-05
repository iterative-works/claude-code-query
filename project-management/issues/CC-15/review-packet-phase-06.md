---
generated_from: 6a52a33a8991f1f83a658b9d3ef4a431befafaed
generated_at: 2026-04-05T14:33:05Z
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

# Review Packet: Phase 6 - Error handling - process crash and malformed JSON

## Goals

This phase hardens both the direct (Ox) and effectful (cats-effect/fs2) session APIs against failure conditions: unexpected process death and malformed JSON on stdout. Before this phase, a process crash mid-turn would silently end the stream without signalling an error, and a single malformed JSON line in the effectful API would kill the entire stdout reader fiber.

Key objectives:
- Add `SessionProcessDied` and `SessionClosedError` to the `CLIError` hierarchy so callers receive typed, actionable errors instead of raw `IOException`s or silent stream truncation
- In the direct API: check process liveness before `send`, detect unexpected EOF in `stream` (stdout closed before `ResultMessage`), and make `close()` idempotent
- In the effectful API: check the alive ref before enqueuing to stdin, fix the stdout reader to log-and-skip malformed JSON instead of raising it as a fatal error, and propagate process-death through `pendingErrorRef` so `stream` can raise it after seeing the EOF sentinel
- Add `SessionMockCliScript` helpers for crash and malformed-JSON scenarios and provide comprehensive unit + integration test coverage for all error paths in both APIs

## Scenarios

- [ ] `send` after `close()` throws `SessionClosedError` with a message containing "closed"
- [ ] `close()` is idempotent — calling it twice does not throw
- [ ] `send` to a dead process throws `SessionProcessDied` (direct)
- [ ] `send` to a dead process raises `SessionProcessDied` in IO (effectful)
- [ ] `stream` raises `SessionProcessDied` when the process exits mid-turn before a `ResultMessage` is received (direct)
- [ ] `stream` raises `SessionProcessDied` when the process exits mid-turn before a `ResultMessage` is received (effectful)
- [ ] A malformed JSON line mid-turn is logged and skipped; the stream completes normally with valid messages (direct)
- [ ] A malformed JSON line mid-turn is logged and skipped; valid messages arrive (effectful) — fixes previous behavior where `IO.raiseError` terminated the stdout reader
- [ ] Multiple malformed JSON lines do not accumulate errors; all valid messages arrive (direct)
- [ ] Session remains functional (valid `ResultMessage` received) after a malformed JSON line
- [ ] Process crash mid-turn surfaces `SessionProcessDied` with the correct exit code (integration, direct + effectful)
- [ ] Process crash between turns: second `send` raises `SessionProcessDied` (integration, direct + effectful)
- [ ] Malformed JSON recovery with a mock script: valid messages still arrive (integration, direct + effectful)
- [ ] Resource cleanup completes without hanging after process dies (effectful)

## Entry Points

| File | Method/Class | Why Start Here |
|------|--------------|----------------|
| `core/src/works/iterative/claude/core/CLIError.scala` | `SessionProcessDied`, `SessionClosedError` | New error types added to the sealed hierarchy; understand the typed error surface first |
| `direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala` | `send()`, `stream()`, `close()` | Core direct implementation with liveness check, EOF detection, and idempotent close |
| `effectful/src/works/iterative/claude/effectful/internal/cli/SessionProcess.scala` | `startStdoutReader`, `SessionImpl.send`, `SessionImpl.stream` | Effectful implementation; `startStdoutReader` contains the malformed-JSON fix and exit-code detection |
| `direct/test/src/works/iterative/claude/direct/internal/testing/SessionMockCliScript.scala` | `createCrashMidTurnScript`, `createCrashBetweenTurnsScript`, `createMalformedJsonMidTurnScript`, `createImmediateExitScript` | New mock script factories that back all error-scenario tests |

## Diagrams

### Error propagation — direct API

```
send("prompt")
  │
  ├─ alive.get() == false ──► throw SessionClosedError
  ├─ !process.isAlive ──────► alive.set(false); throw SessionProcessDied(exitCode, stderr)
  └─ write to stdinWriter
       └─ IOException ───────► alive.set(false); throw SessionProcessDied(exitCode, msg)

stream()
  │
  └─ loop: stdoutReader.readLine()
       ├─ null (EOF) and resultSeen == true ──► normal end-of-turn (healthy close)
       ├─ null (EOF) and resultSeen == false ─► alive.set(false);
       │                                        throw SessionProcessDied(exitCode, stderr)
       ├─ Right(Some(ResultMessage)) ──────────► emit; resultSeen = true; done = true
       ├─ Right(Some(msg)) ────────────────────► emit; continue
       ├─ Right(None) ─────────────────────────► continue (empty/filtered line)
       └─ Left(error) ─────────────────────────► logger.error; continue (log and skip)
```

### Error propagation — effectful API

```
SessionImpl.send(prompt)
  │
  ├─ aliveRef.get == false ──► IO.raiseError(pendingError or SessionProcessDied(None,...))
  └─ offer SDKUserMessage to stdinQueue

startStdoutReader (background fiber)
  │
  └─ stdout lines stream
       ├─ Left(err) [malformed JSON] ─────► logger.warn; as(None) ─► filtered out (FIXED)
       ├─ Right(Some(ResultMessage)) ─────► sessionIdRef.set; offer Some(msg)
       ├─ Right(Some(msg)) ───────────────► offer Some(msg)
       └─ stdout EOF (stream complete)
            └─ exitValue attempt
                 ├─ non-zero ─────────────► aliveRef.set(false);
                 │                          pendingErrorRef.set(Some(SessionProcessDied))
                 └─ zero / error ──────────► no-op
            └─ guarantee: messageQueue.offer(None)  [EOF sentinel]

SessionImpl.stream
  │
  └─ fromQueueNoneTerminated(messageQueue)
       ├─ takeThrough(!_.isInstanceOf[ResultMessage])
       └─ onFinalize:
            └─ resultSeenRef.get == false
                 └─ pendingErrorRef.get
                      ├─ Some(err) ──────► IO.raiseError(err)
                      └─ None ───────────► IO.unit
```

### `pendingErrorRef` — bridge between stdout reader fiber and stream consumer

The stdout reader fiber writes a `CLIError` into `pendingErrorRef` when the process exits with a non-zero code. The `stream` consumer reads it in the `onFinalize` hook when no `ResultMessage` was seen before the EOF sentinel. This avoids changing the queue element type (keeping `Queue[IO, Option[Message]]`) while still surfacing process-death errors to the caller.

## Test Summary

### Unit tests — Direct (`SessionErrorTest`)

| # | Test | Type |
|---|------|------|
| E1 | `send after close() throws SessionClosedError` | Unit |
| E2 | `close() is idempotent - calling it twice does not throw` | Unit |
| E3 | `send to a dead process throws SessionProcessDied` | Unit |
| E4 | `stream raises SessionProcessDied when process exits mid-turn` | Unit |
| E5 | `malformed JSON line mid-turn is skipped and stream completes normally` | Unit |
| E6 | `session remains functional after a malformed JSON line` | Unit |

### Integration tests — Direct (`SessionErrorIntegrationTest`)

| # | Test | Type |
|---|------|------|
| I1 | `process crash mid-turn: stream() raises SessionProcessDied with exit code` | Integration |
| I2 | `process crash between turns: second send raises SessionProcessDied` | Integration |
| I3 | `malformed JSON recovery: bad JSON line followed by valid messages; valid messages arrive` | Integration |
| I4 | `multiple malformed JSON lines do not accumulate errors; all valid messages arrive` | Integration |

### Unit tests — Effectful (`SessionErrorTest`)

| # | Test | Type |
|---|------|------|
| E1 | `send to a dead process raises SessionProcessDied in IO` | Unit |
| E2 | `stream raises SessionProcessDied when process exits mid-turn` | Unit |
| E3 | `malformed JSON line is logged and skipped; valid messages arrive` | Unit |
| E4 | `session remains functional after a malformed JSON line - ResultMessage arrives` | Unit |

### Integration tests — Effectful (`SessionErrorIntegrationTest`)

| # | Test | Type |
|---|------|------|
| I1 | `process crash mid-turn: stream raises SessionProcessDied` | Integration |
| I2 | `process crash between turns: second send raises SessionProcessDied` | Integration |
| I3 | `malformed JSON recovery: bad JSON line followed by valid messages; valid messages arrive` | Integration |
| I4 | `Resource cleanup completes without hanging after process dies` | Integration |

### Existing tests (regression coverage)

`SessionTest` (direct) exercises the full happy-path session lifecycle including multi-turn conversation and session ID propagation; these tests continue to pass with no changes to the test file beyond minor cleanup.

## Files Changed

| File | Change |
|------|--------|
| `core/src/works/iterative/claude/core/CLIError.scala` | Added `SessionProcessDied(exitCode: Option[Int], stderr: String)` and `SessionClosedError(sessionId: String)` to the sealed `CLIError` hierarchy |
| `direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala` | Added `alive: AtomicBoolean`; `send()` checks both `alive` flag and `process.isAlive`, wraps `IOException` as `SessionProcessDied`; `stream()` tracks `resultSeen` and throws `SessionProcessDied` on unexpected EOF; `close()` made idempotent via `compareAndSet`; added `captureRemainingStderr()` helper |
| `effectful/src/works/iterative/claude/effectful/internal/cli/SessionProcess.scala` | Added `aliveRef: Ref[IO, Boolean]` and `pendingErrorRef: Ref[IO, Option[CLIError]]`; `startStdoutReader` changed from `IO.raiseError` on parse error to log-and-skip; stdout completion checks exit code and writes to `pendingErrorRef`; `SessionImpl.send` checks `aliveRef` before enqueuing; `SessionImpl.stream` checks `pendingErrorRef` in `onFinalize` when no `ResultMessage` was seen |
| `direct/test/src/works/iterative/claude/direct/internal/testing/SessionMockCliScript.scala` | Added `createImmediateExitScript`, `createCrashMidTurnScript`, `createCrashBetweenTurnsScript`, `createMalformedJsonMidTurnScript`; added private helpers `generateCrashMidTurnScript` and `generateCrashBetweenTurnsScript` |
| `direct/test/src/works/iterative/claude/direct/SessionErrorTest.scala` | New file: 6 unit tests for direct API error scenarios |
| `direct/test/src/works/iterative/claude/direct/SessionErrorIntegrationTest.scala` | New file: 4 integration tests for direct API error scenarios |
| `direct/test/src/works/iterative/claude/direct/SessionTest.scala` | Minor cleanup only (no logic changes) |
| `effectful/test/src/works/iterative/claude/effectful/SessionErrorTest.scala` | New file: 4 unit tests for effectful API error scenarios |
| `effectful/test/src/works/iterative/claude/effectful/SessionErrorIntegrationTest.scala` | New file: 4 integration tests for effectful API error scenarios |
