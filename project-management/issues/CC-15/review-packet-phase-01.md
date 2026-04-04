---
generated_from: 73dd140feaa23c6ac94eaec24eec4540d90f1013
generated_at: 2026-04-04T18:20:03Z
branch: CC-15-phase-01
issue_id: CC-15
phase: 1
files_analyzed:
  - works/iterative/claude/core/model/SDKUserMessage.scala
  - works/iterative/claude/core/model/Message.scala
  - works/iterative/claude/core/parsing/JsonParser.scala
  - works/iterative/claude/direct/internal/parsing/JsonParser.scala
  - test/works/iterative/claude/core/model/SDKUserMessageTest.scala
  - test/works/iterative/claude/core/model/SDKUserMessageRoundTripTest.scala
  - test/works/iterative/claude/core/model/SDKUserMessageE2ETest.scala
  - test/works/iterative/claude/core/parsing/JsonParserTest.scala
  - test/works/iterative/claude/direct/internal/parsing/JsonParserTest.scala
---

# Review Packet: Phase 1 - Stdin Message Format and Response Delimiting

## Goals

This phase establishes the stream-json protocol foundation needed for all subsequent session phases. It defines how the SDK writes messages to the Claude Code CLI's stdin, and ensures the parser handles all message types the CLI emits in session mode.

Key objectives:

- Define `SDKUserMessage` — the Scala representation of the JSON object written to CLI stdin when using `--input-format stream-json`
- Provide a circe `Encoder[SDKUserMessage]` that produces the exact protocol shape the CLI expects
- Confirm `ResultMessage` (type `"result"`) correctly signals end-of-turn in stream-json protocol
- Add `KeepAliveMessage` and `StreamEventMessage` to the `Message` sealed hierarchy so downstream phases can pattern-match on them rather than silently losing them

## Scenarios

- [ ] `SDKUserMessage` case class exists with `content`, `sessionId`, and `parentToolUseId` fields
- [ ] Encoder produces `{"type":"user","message":{"role":"user","content":"..."},"parent_tool_use_id":null,"session_id":"..."}` for a normal user message
- [ ] Encoder renders `parent_tool_use_id` as a string when `parentToolUseId` is `Some`, and as JSON `null` when `None`
- [ ] `"pending"` is a valid `sessionId` value (used for the first message before the session ID is known)
- [ ] Encoded JSON is a single line with no embedded newlines (required for newline-delimited protocol)
- [ ] `ResultMessage` correctly parses a realistic stream-json end-of-turn result, including `session_id`, `num_turns`, `total_cost_usd`, and `result` fields
- [ ] `KeepAliveMessage` is parsed from `{"type":"keep_alive"}` messages
- [ ] `StreamEventMessage` is parsed from `{"type":"stream_event",...}` messages, carrying all non-type fields as data
- [ ] Round-trip integration: encode `SDKUserMessage`, pipe through mock CLI bash script, parse the stdout response correctly
- [ ] Mock session protocol: mock CLI emits init system message, SDK reads it, writes `SDKUserMessage`, parses assistant + result response

## Entry Points

| File | Method/Class | Why Start Here |
|------|--------------|----------------|
| `works/iterative/claude/core/model/SDKUserMessage.scala` | `SDKUserMessage` / `Encoder[SDKUserMessage]` | New type: the central deliverable of this phase |
| `works/iterative/claude/core/model/Message.scala` | `KeepAliveMessage`, `StreamEventMessage` | New Message subtypes added to the sealed hierarchy |
| `works/iterative/claude/core/parsing/JsonParser.scala` | `parseMessage` | Parser dispatch extended with two new branches |
| `works/iterative/claude/direct/internal/parsing/JsonParser.scala` | `extractMessageType` | Pattern match updated to cover new message types |

## Diagrams

### Message Hierarchy (after this phase)

```
Message (sealed)
├── UserMessage
├── AssistantMessage
├── SystemMessage
├── ResultMessage          ← end-of-turn delimiter in stream-json protocol
├── KeepAliveMessage       ← NEW: heartbeat from CLI in session mode
└── StreamEventMessage     ← NEW: streaming content delta events
```

### stream-json Protocol Flow (established by this phase)

```
SDK stdin                        Claude Code CLI stdout
─────────────────────────────    ──────────────────────────────────────
                                 SystemMessage(subtype="init", session_id=...)
SDKUserMessage (JSON line) ───►
                                 AssistantMessage(...)        ─┐
                                 StreamEventMessage(...)       │ one turn
                                 KeepAliveMessage              │
                                 ResultMessage(type="result") ─┘ ← end-of-turn
SDKUserMessage (next turn) ───►
                                 ...
```

### Encoder Output Shape

```
SDKUserMessage(content, sessionId, parentToolUseId)
        │
        ▼  Encoder[SDKUserMessage]
{
  "type": "user",
  "message": { "role": "user", "content": "<content>" },
  "parent_tool_use_id": null | "<id>",
  "session_id": "<sessionId>"
}
        │
        ▼  + "\n"  (appended by stdin writing layer in Phase 03)
newline-delimited JSON on stdin
```

## Test Summary

| Test File | Type | Tests |
|-----------|------|-------|
| `test/.../core/model/SDKUserMessageTest.scala` | Unit | Encoder produces correct JSON structure; `Some(parentToolUseId)` renders as string; `None` renders as null; `"pending"` is valid session ID; no embedded newlines |
| `test/.../core/model/SDKUserMessageRoundTripTest.scala` | Integration | Round-trip encode through mock CLI bash script and parse response; full mock session protocol (init + stdin write + assistant + result) |
| `test/.../core/model/SDKUserMessageE2ETest.scala` | E2E | Real CLI accepts SDKUserMessage JSON via stream-json stdin (marked `.ignore` — requires live CLI and credentials) |
| `test/.../core/parsing/JsonParserTest.scala` | Unit | `keep_alive` parses to `KeepAliveMessage`; `stream_event` parses to `StreamEventMessage` with data; realistic stream-json `ResultMessage` parses all fields correctly |
| `test/.../direct/internal/parsing/JsonParserTest.scala` | Unit | Property-based: `extractMessageType` covers `KeepAliveMessage` and `StreamEventMessage` (existing round-trip generator extended) |

**Coverage notes:**
- Unit tests verify the exact JSON shape field-by-field, not just structural equality
- Integration tests use real `ProcessBuilder` with bash scripts to validate stdin piping works at the OS level
- E2E test is intentionally `.ignore` to avoid CI failures; can be run manually

## Files Changed

| File | Change |
|------|--------|
| `works/iterative/claude/core/model/SDKUserMessage.scala` | New — `SDKUserMessage` case class and `Encoder[SDKUserMessage]` |
| `works/iterative/claude/core/model/Message.scala` | Modified — added `KeepAliveMessage` (case object) and `StreamEventMessage(data)` |
| `works/iterative/claude/core/parsing/JsonParser.scala` | Modified — added `keep_alive` and `stream_event` branches in `parseMessage`; new private `parseStreamEventMessage` |
| `works/iterative/claude/direct/internal/parsing/JsonParser.scala` | Modified — imports and `extractMessageType` pattern match extended for new types |
| `test/works/iterative/claude/core/model/SDKUserMessageTest.scala` | New — unit tests for `Encoder[SDKUserMessage]` |
| `test/works/iterative/claude/core/model/SDKUserMessageRoundTripTest.scala` | New — integration tests with mock CLI bash scripts |
| `test/works/iterative/claude/core/model/SDKUserMessageE2ETest.scala` | New — E2E test against real CLI (`.ignore` by default) |
| `test/works/iterative/claude/core/parsing/JsonParserTest.scala` | Modified — tests for `keep_alive`, `stream_event`, and end-of-turn `ResultMessage` parsing |
| `test/works/iterative/claude/direct/internal/parsing/JsonParserTest.scala` | Modified — property-based round-trip extended for new message types |
| `project-management/issues/CC-15/phase-01-tasks.md` | Workflow — task tracking for this phase |
| `project-management/issues/CC-15/review-state.json` | Workflow — review state metadata |
