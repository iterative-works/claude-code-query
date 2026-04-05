# Phase 04 Tasks: Direct API - Multi-turn conversation

## Tests

### Unit tests (SessionTest.scala)

- [x] [test] Two sequential sends return isolated message streams — configure mock with two TurnResponses, call send twice, verify each returns only its own turn's messages (no bleeding)
- [x] [test] Session ID propagates from first turn's ResultMessage to second send's SDKUserMessage — after first send completes, verify session.sessionId is updated, then verify second SDKUserMessage (via stdin capture) contains the updated session ID
- [x] [test] Session ID updates after each turn — send twice with different session IDs in each ResultMessage, verify session.sessionId reflects the most recent one after each turn
- [x] [test] Three sequential sends work correctly — extend to three turns to verify indexing/cycling beyond two turns

### Integration tests (SessionIntegrationTest.scala)

- [x] [test] Full two-turn session lifecycle — start session with init message, send two prompts with different response content per turn, verify each send returns the correct response content
- [x] [test] Stdin capture shows correct session ID progression — use captureStdinFile, send two prompts, verify first SDKUserMessage has init session ID and second has the session ID from first turn's ResultMessage
- [x] [test] Turn responses with different message counts — configure turn 1 with 2 messages (assistant + result) and turn 2 with 4 messages (keepalive + stream_event + assistant + result), verify each turn's Flow emits exactly the right count and types
- [x] [test] Session ID updates across turns when ResultMessage has different IDs — configure turn 1 and turn 2 with distinct session IDs in their ResultMessage, verify session.sessionId reflects the most recent after each turn

### E2E tests (SessionE2ETest.scala)

- [x] [test] Two-turn conversation with context dependency — gated on CLI availability; send "Remember the number 42. Reply only with 'OK'." then "What number did I ask you to remember? Reply with just the number." Verify second response contains "42"
- [x] [test] Session ID remains valid across multiple turns — after two turns with real CLI, verify session.sessionId is non-empty and not "pending"

## Implementation

- [x] [impl] Fix any bugs discovered during multi-turn testing in SessionProcess.send (e.g., boundary detection issues, stale state, session ID propagation)

## Integration

- [x] [integration] Verify all new multi-turn tests pass alongside existing Phase 03 tests with no regressions (./mill direct.test)
- [x] [integration] Verify no compilation warnings in direct module
