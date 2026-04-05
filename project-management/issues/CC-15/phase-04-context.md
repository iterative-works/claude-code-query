# Phase 04: Direct API - Multi-turn conversation

**Issue:** CC-15 - Support persistent two-way conversations with a single Claude Code session
**Estimated Effort:** 4-6 hours
**Status:** Not started

## Goals

1. Verify that multiple sequential `send` calls within a single `Session` produce correct, isolated responses per turn
2. Verify that response messages from one turn do not bleed into the next turn's `Flow`
3. Verify that the session ID remains consistent (or updates correctly) across turns
4. Verify that context is maintained across turns (the CLI handles this internally; we verify the SDK does not break it)
5. Fix any issues discovered during multi-turn testing

## Scope

### In scope

- Multi-turn unit tests: two or more sequential `send` calls on a single session, verifying each returns its own complete message stream
- Multi-turn integration tests: mock CLI scripts that respond differently per turn, verifying correct turn-to-response mapping
- Multi-turn E2E test: real CLI session with a follow-up question that requires context from the first answer
- Verifying the `SDKUserMessage` sent for the second turn includes the session ID from the first turn's `ResultMessage`
- Verifying that `session.sessionId` is updated after each completed turn
- Any bug fixes in `SessionProcess.send` discovered during multi-turn testing (e.g., boundary detection issues, stale state)

### Out of scope

- Concurrent `send` calls (per RESOLVED decision: no client-side enforcement, CLI handles queueing)
- Turn-level timeouts
- Error handling for process crashes mid-conversation (Phase 06)
- Effectful API sessions (Phase 05)
- Any new public API surface (Phase 03 already defined everything needed)

## Dependencies

**Phase 03 delivered:**
- `Session` trait at `direct/src/works/iterative/claude/direct/Session.scala` with `send(prompt: String): Flow[Message]`, `close(): Unit`, `sessionId: String`
- `SessionProcess` at `direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala` — already supports multiple sequential `send` calls mechanically (stdin stays open, stdout reader persists across calls)
- `ClaudeCode.session(SessionOptions): Session` factory on both class and companion object
- `SessionMockCliScript` at `direct/test/src/works/iterative/claude/direct/internal/testing/SessionMockCliScript.scala` — already supports multi-turn via `turnResponses: List[TurnResponse]` with index cycling
- Session ID extracted from init `SystemMessage`, updated from each `ResultMessage`
- Existing single-turn tests (unit, integration, E2E) all passing

**Key insight:** The mechanical infrastructure for multi-turn already exists. Phase 4 is primarily about comprehensive testing to prove it works correctly and fixing any issues found.

## Approach

### 1. Add multi-turn unit tests to `SessionTest.scala`

Add tests that call `send` twice on the same session and verify:
- Each `send` returns only messages for that turn (no bleeding)
- The second `send`'s `SDKUserMessage` uses the session ID from the first turn's `ResultMessage`
- `session.sessionId` is updated after each turn

### 2. Add multi-turn integration tests to `SessionIntegrationTest.scala`

Use `SessionMockCliScript.createSessionScript` with multiple `TurnResponse` entries. Tests should:
- Send two prompts sequentially, collecting each turn's messages independently
- Verify each turn gets exactly the messages configured for that turn index
- Verify stdin capture shows two `SDKUserMessage` JSON lines with correct session IDs
- Verify that different response content per turn maps correctly (turn 1 gets response 1, turn 2 gets response 2)

### 3. Add multi-turn E2E test to `SessionE2ETest.scala`

A single test that sends two messages to the real CLI where the second depends on context from the first (e.g., "Remember the number 42" then "What number did I ask you to remember?"). Verify the second response references the first turn's context.

### 4. Fix any issues discovered

If multi-turn testing reveals issues (e.g., session ID not propagating correctly, stdout reader state problems between turns), fix them in `SessionProcess`.

## Files to Modify/Create

### New files

None expected. All tests go into existing test files.

### Files to modify

- `direct/test/src/works/iterative/claude/direct/SessionTest.scala` — add multi-turn unit tests
- `direct/test/src/works/iterative/claude/direct/SessionIntegrationTest.scala` — add multi-turn integration tests
- `direct/test/src/works/iterative/claude/direct/SessionE2ETest.scala` — add multi-turn E2E test
- `direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala` — only if bugs are found during testing

### Reference files (no changes)

- `direct/src/works/iterative/claude/direct/Session.scala` — Session trait (no changes expected)
- `direct/src/works/iterative/claude/direct/ClaudeCode.scala` — factory methods (no changes expected)
- `direct/test/src/works/iterative/claude/direct/internal/testing/SessionMockCliScript.scala` — mock script generator (already supports multi-turn)
- `direct/test/src/works/iterative/claude/direct/internal/testing/MockLogger.scala` — test logger

## Testing Strategy

### Unit tests (additions to `SessionTest.scala`)

1. **Two sequential sends return isolated message streams** — configure a mock script with two turn responses, call `send` twice, verify each returns exactly the messages for its turn index and no more
2. **Session ID propagates from first turn to second send** — after the first `send` completes, verify `session.sessionId` is updated, then use stdin capture to verify the second `SDKUserMessage` contains the updated session ID
3. **Three turns work correctly** — extend to three sequential sends to test the cycling/indexing beyond two turns

### Integration tests (additions to `SessionIntegrationTest.scala`)

1. **Full two-turn session lifecycle** — start session with init message, send two prompts with different expected responses per turn, verify each `send` returns the correct response content, verify session ID consistency throughout
2. **Stdin capture shows correct session ID progression** — use `captureStdinFile`, send two prompts, verify first `SDKUserMessage` has the init session ID and second has the session ID from the first `ResultMessage`
3. **Turn responses with different message counts** — configure turn 1 with 2 messages (assistant + result) and turn 2 with 4 messages (keepalive + stream_event + assistant + result), verify each turn's `Flow` emits exactly the right number and type of messages
4. **Session ID updates across turns when ResultMessage has different IDs** — configure turn 1 and turn 2 with different session IDs in their `ResultMessage`, verify `session.sessionId` reflects the most recent one after each turn

### E2E tests (additions to `SessionE2ETest.scala`)

1. **Two-turn conversation with context dependency** — gated on CLI availability; send "Remember the number 42. Reply only with 'OK'." then send "What number did I ask you to remember? Reply with just the number." Verify the second response contains "42". This confirms the CLI maintains conversation context across turns and the SDK does not break it.
2. **Session ID remains valid across multiple turns** — after two turns with the real CLI, verify `session.sessionId` is non-empty and not "pending"

## Acceptance Criteria

1. Two or more sequential `send` calls within one session each return a complete `Flow[Message]` ending with a `ResultMessage`
2. Messages from one turn do not appear in another turn's `Flow` (no bleeding)
3. The `SDKUserMessage` for the second turn includes the session ID obtained from the first turn's `ResultMessage`
4. `session.sessionId` is updated after each completed turn
5. Context is maintained across turns (verified via E2E test with context-dependent follow-up)
6. Both turns originate from the same underlying CLI process (verified by the session staying open and functional)
7. All new tests pass with no compilation warnings
8. All existing Phase 03 tests continue to pass (no regressions)
