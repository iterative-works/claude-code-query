---
generated_from: 5258c10f593bb32a32070570ecf1bbc6b8ac2785
generated_at: 2026-04-05T08:58:45Z
branch: CC-15-phase-04
issue_id: CC-15
phase: 4
files_analyzed:
  - direct/test/src/works/iterative/claude/direct/SessionTest.scala
  - direct/test/src/works/iterative/claude/direct/SessionIntegrationTest.scala
  - direct/test/src/works/iterative/claude/direct/SessionE2ETest.scala
---

# Review Packet: Phase 4 - Direct API - Multi-turn conversation

## Goals

This phase proves that multiple sequential `send` calls within a single `Session` work correctly, with isolated message streams per turn and correct session ID propagation.

Key objectives:

- Verify that each `send` call returns only its own turn's messages with no bleed from adjacent turns
- Verify that the `SDKUserMessage` for each subsequent turn carries the session ID from the previous turn's `ResultMessage`
- Verify that `session.sessionId` is updated after each completed turn
- Verify that real CLI context is maintained across turns (the SDK must not break it)
- Fix any bugs in `SessionProcess.send` discovered during multi-turn testing

No new public API surface was added. The mechanical multi-turn infrastructure was already in place from Phase 3; this phase comprehensively tests and validates it.

## Scenarios

- [ ] Two sequential sends each return only their own turn's messages (no message bleed between turns)
- [ ] The second send's `SDKUserMessage` carries the session ID from the first turn's `ResultMessage`
- [ ] `session.sessionId` reflects the most recent `ResultMessage` session ID after each turn
- [ ] Three sequential sends work correctly (tests cycling beyond two turns)
- [ ] A full two-turn session lifecycle via mock CLI produces correct per-turn responses
- [ ] Stdin capture confirms correct session ID progression across turns (init ID for first send, turn-1 result ID for second send)
- [ ] Turns with different message counts each emit exactly the right message count and types
- [ ] Session ID updates across turns when `ResultMessage` carries distinct IDs per turn
- [ ] Real two-turn conversation preserves context (second turn correctly recalls information from first turn)
- [ ] Session ID remains non-empty and non-"pending" across multiple real turns

## Entry Points

| File | Method/Class | Why Start Here |
|------|--------------|----------------|
| `direct/test/src/works/iterative/claude/direct/SessionTest.scala` | T8-T11 (unit tests) | Start here to understand the core multi-turn contract: turn isolation and session ID propagation |
| `direct/test/src/works/iterative/claude/direct/SessionIntegrationTest.scala` | IT6-IT9 (integration tests) | Shows the full mock-CLI-backed lifecycle: turn responses, stdin capture, message counts |
| `direct/test/src/works/iterative/claude/direct/SessionE2ETest.scala` | E2E multi-turn tests | Proves real CLI context retention and session ID validity across turns |

## Diagrams

### Multi-turn session message flow

```
Caller          Session / SessionProcess          Mock/Real CLI
  |                       |                             |
  | session.send(p1)      |                             |
  |---------------------->|  write SDKUserMessage(p1)   |
  |                       |---------------------------->|
  |                       |  <-- AssistantMessage       |
  |                       |  <-- ResultMessage(id1)     |
  |   Flow[Message] t1    |                             |
  |<----------------------|                             |
  |                       |  sessionId := id1           |
  |                       |                             |
  | session.send(p2)      |                             |
  |---------------------->|  write SDKUserMessage(p2,   |
  |                       |    session_id=id1)          |
  |                       |---------------------------->|
  |                       |  <-- AssistantMessage       |
  |                       |  <-- ResultMessage(id2)     |
  |   Flow[Message] t2    |                             |
  |<----------------------|                             |
  |                       |  sessionId := id2           |
```

### Session ID progression

```
Initial state:  sessionId = "pending"
                     |
            SystemMessage(initId) read at startup
                     |
                sessionId = initId
                     |
              send(p1) completes
            ResultMessage(turn1Id)
                     |
                sessionId = turn1Id   <-- carried in next SDKUserMessage
                     |
              send(p2) completes
            ResultMessage(turn2Id)
                     |
                sessionId = turn2Id
```

### Test coverage pyramid

```
       E2E (2 tests)
      Real CLI, context + ID validity

    Integration (4 tests, IT6-IT9)
    Mock CLI: lifecycle, stdin IDs, message counts, ID updates

  Unit (4 tests, T8-T11)
  In-process: isolation, ID propagation, ID updates, 3-turn cycling
```

## Test Summary

### Unit tests (SessionTest.scala) — 4 new tests

| Test | Type | Verifies |
|------|------|----------|
| T8: two sequential sends return isolated message streams | Unit | No message bleed between turn 1 and turn 2 |
| T9: session ID propagates from first turn ResultMessage to second send SDKUserMessage | Unit | Stdin capture confirms second message carries turn-1 session ID |
| T10: session ID is updated after each turn to reflect the most recent ResultMessage | Unit | `session.sessionId` correct after turn 1 and turn 2 |
| T11: three sequential sends each return only their own turn's messages | Unit | Cycling/indexing works beyond two turns |

### Integration tests (SessionIntegrationTest.scala) — 4 new tests

| Test | Type | Verifies |
|------|------|----------|
| IT6: full two-turn session lifecycle with mock CLI | Integration | Init ID read at startup; turn 1 and turn 2 return correct distinct content and update sessionId |
| IT7: stdin capture shows correct session ID progression across turns | Integration | First SDKUserMessage carries init ID; second carries turn-1 result ID |
| IT8: turns with different message counts emit exactly the right messages | Integration | Turn 1 (2 msgs: assistant + result) and turn 2 (4 msgs: keepalive + stream_event + assistant + result) are both exact |
| IT9: session ID updates across turns when ResultMessage carries distinct IDs | Integration | `session.sessionId` tracks the most recent ResultMessage ID across turns |

### E2E tests (SessionE2ETest.scala) — 2 new tests

| Test | Type | Verifies |
|------|------|----------|
| E2E: two-turn conversation preserves context across turns | E2E | Real CLI recalls "42" in second turn; SDK does not break conversation context |
| E2E: session ID remains valid and non-pending across multiple turns | E2E | After two real turns, sessionId is non-empty and not "pending" |

All E2E tests are gated on `isClaudeCliInstalled()`, `isNodeJsAvailable()`, and `hasApiKeyOrCredentials()`.

## Files Changed

| File | Change Type | Summary |
|------|-------------|---------|
| `direct/test/src/works/iterative/claude/direct/SessionTest.scala` | Modified | Added 4 unit tests (T8-T11) covering turn isolation, session ID propagation, and 3-turn cycling |
| `direct/test/src/works/iterative/claude/direct/SessionIntegrationTest.scala` | Modified | Added 4 integration tests (IT6-IT9) covering full lifecycle, stdin capture, message count precision, and ID tracking; also moved `import io.circe.parser` to file-level |
| `direct/test/src/works/iterative/claude/direct/SessionE2ETest.scala` | Modified | Added 2 E2E tests for real context retention and session ID validity across turns |
| `project-management/issues/CC-15/phase-04-tasks.md` | Modified | All tasks checked off as complete |
| `project-management/issues/CC-15/review-state.json` | Modified | Status updated to `implementing` |

<details>
<summary>Diff summary: SessionTest.scala additions</summary>

Four tests added in order T8 through T11:

- **T8** uses `SessionMockCliScript.createSessionScript` with two `TurnResponse` entries, calls `send` twice, asserts each result list has exactly 2 messages and that text content is turn-specific.
- **T9** uses `captureStdinFile`, calls `send` twice, then parses the captured JSONL to confirm the second line's `session_id` field matches the first turn's `ResultMessage` session ID.
- **T10** calls `send` twice with distinct session IDs in each `ResultMessage` and asserts `session.sessionId` equals the correct ID after each turn.
- **T11** iterates three turns, asserting each returns 2 messages and a `ResultMessage` with the correct per-turn session ID.

</details>

<details>
<summary>Diff summary: SessionIntegrationTest.scala additions</summary>

Four tests added (IT6-IT9) plus a minor cleanup (moved `import io.circe.parser` from inside a test to file scope):

- **IT6** creates a mock script with an init message and two turn responses containing different text content. Verifies the session starts with the init session ID, each turn's messages match their configured content, and `session.sessionId` tracks the result ID after each turn.
- **IT7** uses `captureStdinFile` and verifies the JSONL written to stdin: first line has the init session ID, second line has the turn-1 result session ID.
- **IT8** configures turn 1 with 2 messages and turn 2 with 4 messages (including `keepAliveMessage` and `streamEventMessage`). Asserts exact counts and presence of specific message types per turn.
- **IT9** configures no init messages and two turns with distinct result session IDs. Asserts `session.sessionId` is correct after each turn.

</details>

<details>
<summary>Diff summary: SessionE2ETest.scala additions</summary>

Two tests added:

- **E2E context retention**: sends "Remember the number 42. Reply only with 'OK'." then "What number did I ask you to remember? Reply with just the number." Asserts the second response contains "42".
- **E2E session ID stability**: sends two simple arithmetic prompts, captures `session.sessionId` after each, and asserts both are non-empty and not "pending".

</details>
