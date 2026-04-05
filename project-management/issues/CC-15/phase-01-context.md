# Phase 01: Stdin message format and response delimiting

**Issue:** CC-15 - Support persistent two-way conversations with a single Claude Code session
**Estimated Effort:** 6-8 hours
**Status:** Not started

## Goals

1. Define `SDKUserMessage` case class representing JSON messages written to CLI stdin in `stream-json` mode
2. Provide a circe `Encoder[SDKUserMessage]` that produces the exact JSON shape the CLI expects
3. Verify that `ResultMessage` (type `"result"`) correctly signals end-of-turn in the stream-json protocol
4. Decide on and optionally add parsing support for `stream_event` and `keep_alive` message types that appear in the stream-json protocol but are not yet in the model

## Scope

### In scope

- `SDKUserMessage` case class and its circe Encoder
- Unit tests for the Encoder output shape
- Unit tests confirming `ResultMessage` is parsed from end-of-turn JSON
- Decision (with code if adding) on `stream_event` and `keep_alive` message types
- Integration test: round-trip stdin write -> mock CLI parse -> stdout response -> SDK parse

### Out of scope

- `SessionOptions` configuration (Phase 02)
- Process management, stdin writing, stdout reading infrastructure (Phase 03)
- Multi-turn sequencing (Phase 04)
- Effectful API (Phase 05)
- Error handling hardening (Phase 06)
- `control_request` / `control_response` message types (deferred)

## Dependencies

None. This is the first phase and has no prerequisites beyond the existing codebase.

## Approach

### 1. Define `SDKUserMessage`

Note: The analysis document refers to this as `StdinMessage`, but `SDKUserMessage` matches the TypeScript Agent SDK's naming and accurately describes what this type represents in the Claude Code protocol. Using the protocol's own terminology avoids ambiguity.

Create a new case class in `works/iterative/claude/core/model/`. The required JSON shape is:

```json
{"type": "user", "message": {"role": "user", "content": "..."}, "parent_tool_use_id": null, "session_id": "..."}
```

The case class should hold:
- `content: String` -- the user's message text
- `sessionId: String` -- the session ID from the `system`/`init` message (or `"pending"` for first message)
- `parentToolUseId: Option[String]` -- for tool result responses (null in normal user messages)

Note: `type` and `message.role` are constants (`"user"`) and should be produced by the Encoder, not stored as fields.

### 2. Create circe Encoder

This is the first Encoder in the project (all existing circe usage is parsing/decoding). Create an `Encoder[SDKUserMessage]` instance. It can live as a given in the `SDKUserMessage` companion object or in a dedicated codec file. Companion object is simpler and follows circe conventions.

The Encoder must produce exactly:
```json
{
  "type": "user",
  "message": {"role": "user", "content": "<content>"},
  "parent_tool_use_id": null,
  "session_id": "<sessionId>"
}
```

When `parentToolUseId` is `Some(id)`, the `parent_tool_use_id` field should contain the string value instead of null.

**Note on line delimiting:** The stdin protocol uses newline-delimited JSON (one JSON object per line, terminated by `\n`). The Encoder itself produces only the JSON object; appending the newline is the responsibility of the stdin writing layer in Phase 03. However, tests should verify the encoded JSON contains no embedded newlines.

### 3. Verify ResultMessage as end-of-turn delimiter

The existing `JsonParser.parseMessage` already handles `type: "result"` and produces `ResultMessage`. Write a focused test that uses a realistic end-of-turn result JSON (matching what stream-json actually emits) and confirms it parses to `ResultMessage` with the expected fields. This validates the existing code works for the session protocol, not just one-shot queries.

### 4. Handle `stream_event` and `keep_alive` types

The stream-json protocol emits these types that the current parser silently drops (returns `None` for unknown types). Two options:

**Decision: Add minimal types now (Option A).** Adding `KeepAliveMessage` and `StreamEventMessage` to the sealed trait ensures downstream phases can pattern-match on them rather than losing them as `None`. `keep_alive` is trivial (no meaningful fields). `stream_event` should carry only the raw JSON data for now -- a `Map[String, Any]` like `SystemMessage` uses -- since the full content delta structure can be refined when Phase 03 consumes it.

## Files to Modify/Create

### New files

- `works/iterative/claude/core/model/SDKUserMessage.scala` -- case class + circe Encoder
- `test/works/iterative/claude/core/model/SDKUserMessageTest.scala` -- Encoder unit tests

### Files to modify

- `works/iterative/claude/core/model/Message.scala` -- add `KeepAliveMessage` and `StreamEventMessage` subtypes
- `works/iterative/claude/core/parsing/JsonParser.scala` -- add parsing branches for `keep_alive` and `stream_event` types
- `test/works/iterative/claude/core/parsing/JsonParserTest.scala` -- tests for new message type parsing

### Files to read (reference, no changes)

- `works/iterative/claude/core/model/Message.scala` -- existing Message hierarchy
- `works/iterative/claude/core/model/ContentBlock.scala` -- content block types
- `works/iterative/claude/core/parsing/JsonParser.scala` -- existing parser dispatch
- `test/works/iterative/claude/core/parsing/JsonParserTest.scala` -- test patterns
- `project.scala` -- circe dependencies (core + parser already present; no additional deps needed for Encoder)

## Testing Strategy

### Unit tests

1. **SDKUserMessage Encoder output shape** -- encode an SDKUserMessage and verify the JSON structure matches the expected protocol format exactly (field names, nesting, null handling)
2. **SDKUserMessage Encoder with parentToolUseId** -- verify `Some(id)` produces a string value, `None` produces JSON null
3. **SDKUserMessage Encoder session_id** -- verify `"pending"` is valid and normal session IDs encode correctly
4. **ResultMessage end-of-turn parsing** -- parse a realistic stream-json result message and verify all fields
5. **stream_event parsing** -- verify parsing of content delta events into `StreamEventMessage`
6. **keep_alive parsing** -- verify heartbeat messages parse into `KeepAliveMessage`
7. **Encoded JSON has no embedded newlines** -- verify SDKUserMessage encoder output is a single line

### Integration tests

1. **Round-trip encode-decode** -- encode an SDKUserMessage to JSON string, feed it to a mock CLI script (bash) that echoes it back as a response, parse the response. This validates the JSON is well-formed and the format is correct end-to-end.
2. **Mock session protocol** -- mock CLI script that:
   - Writes a `system`/`init` message to stdout
   - Reads a JSON line from stdin (the SDKUserMessage)
   - Writes an assistant message + result message to stdout
   - Verify SDK can produce the stdin line and parse the stdout response

### E2E tests

1. **Real CLI stdin format validation** -- if Claude Code CLI is available, start a process with `--print --input-format stream-json --output-format stream-json`, write a properly formatted SDKUserMessage to stdin, and verify the CLI accepts it (does not error). This test should be gated on CLI availability (following existing `TestAssumptions` patterns).

## Acceptance Criteria

1. `SDKUserMessage` case class exists with `content`, `sessionId`, and `parentToolUseId` fields
2. `Encoder[SDKUserMessage]` produces JSON matching the exact protocol format (verified by tests)
3. `ResultMessage` parsing is confirmed to work with stream-json end-of-turn messages (verified by test)
4. `KeepAliveMessage` and `StreamEventMessage` types added to Message hierarchy and parsed correctly
5. All new code has PURPOSE comments per project conventions
6. All existing tests continue to pass (no regressions)
7. Integration test demonstrates the stdin JSON format works with a mock CLI
