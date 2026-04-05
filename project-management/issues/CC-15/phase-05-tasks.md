# Phase 05 Tasks: Effectful API - Session lifecycle with Resource

## Refactoring

- [x] [impl] Refactoring R1: Split direct Session.send into send + stream (CQS) — see `refactor-phase-05-R1.md`

## Setup

- [x] [impl] Create effectful `Session` trait in `effectful/src/.../effectful/Session.scala` with `send(prompt: String): IO[Unit]`, `stream: Stream[IO, Message]`, and `sessionId: IO[String]`
- [x] [impl] Add `SessionOptions` re-export to `effectful/src/.../effectful/package.scala` (type alias + value alias, matching existing re-export pattern)

## Tests

### Unit tests (SessionTest.scala)

- [x] [test] SDKUserMessage encoding for send — verify the JSON written to a mock stdin pipe matches the protocol format (`type`, `message.role`, `message.content`, `session_id`)
- [x] [test] Session ID defaults to "pending" — verify `sessionId` returns IO("pending") before any send
- [x] [test] Session ID extracted from init SystemMessage — verify sessionId updates from init message during Resource acquire
- [x] [test] Session ID updates from ResultMessage — verify sessionId reflects the session_id from each ResultMessage after stream completes
- [x] [test] stream returns messages up to and including ResultMessage — verify the stream terminates correctly at end-of-turn
- [x] [test] KeepAlive and StreamEvent pass through — verify non-terminal message types are emitted in the stream
- [x] [test] Two sequential sends return isolated streams — configure mock with two turns, verify each stream returns only its own turn's messages
- [x] [test] Session ID propagates from first turn to second send — verify the second SDKUserMessage uses the updated session ID from the first turn's ResultMessage
- [x] [test] Three-turn cycling — verify three sequential send/stream cycles work correctly

### Integration tests (SessionIntegrationTest.scala)

- [x] [test] Full session lifecycle with Resource — acquire session, send one message, consume stream, verify response, release (process cleanup)
- [x] [test] Resource cleanup on normal exit — verify process is terminated after Resource.use completes
- [x] [test] Resource cleanup on error — verify process is terminated when an exception occurs inside Resource.use
- [x] [test] Stdin JSON verification — use captureStdinFile in mock script to verify SDKUserMessage JSON format
- [x] [test] Session ID from init message — verify session ID is set from the mock init message during acquire
- [x] [test] Two-turn lifecycle — send two prompts with different configured responses, verify correct mapping
- [x] [test] Session ID progression across turns — verify stdin capture shows correct session ID in each SDKUserMessage
- [x] [test] Variable message counts per turn — turn 1 with 2 messages, turn 2 with 4 messages (including keepalive/stream_event)
- [x] [test] ClaudeCode.session factory — verify the factory method produces a working session Resource

### E2E tests (SessionE2ETest.scala)

- [x] [test] Single-turn session with real CLI — gated on CLI availability; open session Resource, send one prompt, verify AssistantMessage and ResultMessage in stream, verify Resource cleanup
- [x] [test] Multi-turn with context dependency — send "Remember the number 42" then "What number did I ask you to remember?", verify second response references 42
- [x] [test] Session ID is valid after real CLI interaction — verify session ID is non-empty and not "pending" after a send

## Implementation

- [x] [impl] Create `SessionProcess` in `effectful/src/.../internal/cli/SessionProcess.scala` — Resource-based implementation using `ProcessBuilder.spawn[IO]`, background stderr fiber, shared stdout stream, stdin writing via process.stdin pipe
- [x] [impl] Add `ClaudeCode.session(SessionOptions)(using Logger[IO]): Resource[IO, Session]` factory method to `effectful/src/.../effectful/ClaudeCode.scala`
- [x] [impl] Create mock script infrastructure for effectful tests — `SessionMockCliScript.scala` in effectful test internal/testing (Resource[IO, Path] wrapper or shared from direct module)

## Integration

- [x] [integration] Verify all new effectful session tests pass (`./mill effectful.test`)
- [x] [integration] Verify no regressions in existing effectful query tests
- [x] [integration] Verify no regressions in direct module tests (`./mill direct.test`)
- [x] [integration] Verify no compilation warnings across all modules (`./mill __.compile`)
**Phase Status:** Complete
