# Phase 01 Tasks: Stdin message format and response delimiting

## Setup
- [x] [setup] Read existing `Message.scala`, `JsonParser.scala`, `JsonParserTest.scala`, and `project.scala` to confirm circe encoder support is available (circe-core includes `Encoder`) and understand current patterns
- [x] [setup] Create empty test file `test/works/iterative/claude/core/model/SDKUserMessageTest.scala` with package declaration, imports, and PURPOSE comments

## Tests (Red)

### SDKUserMessage Encoder tests
- [x] [test] Write test "Encoder produces correct JSON structure" in `SDKUserMessageTest.scala` -- encode `SDKUserMessage("hello", "session-1", None)` and verify top-level keys `type`, `message`, `parent_tool_use_id`, `session_id` with correct values; `type` is `"user"`, `message` is `{"role":"user","content":"hello"}`, `parent_tool_use_id` is JSON null, `session_id` is `"session-1"`
- [x] [test] Write test "Encoder produces string value for Some(parentToolUseId)" -- encode `SDKUserMessage("hello", "s1", Some("tool-123"))` and verify `parent_tool_use_id` is the string `"tool-123"` (not null)
- [x] [test] Write test "Encoder accepts pending as session_id" -- encode with `sessionId = "pending"` and verify `session_id` field is `"pending"`
- [x] [test] Write test "Encoded JSON contains no embedded newlines" -- encode an SDKUserMessage, call `.noSpaces` on the result, and assert the string contains no `\n` characters

### New message type parsing tests
- [x] [test] Write test "parseMessage parses keep_alive into KeepAliveMessage" in `JsonParserTest.scala` -- parse `{"type":"keep_alive"}` and assert result is `Some(KeepAliveMessage)`
- [x] [test] Write test "parseMessage parses stream_event into StreamEventMessage" in `JsonParserTest.scala` -- parse a stream_event JSON with `event` and `data` fields, assert result is `Some(StreamEventMessage(...))` carrying the raw data map

### ResultMessage end-of-turn verification
- [x] [test] Write test "ResultMessage parses realistic stream-json end-of-turn" in `JsonParserTest.scala` -- use a full result JSON matching real CLI output (subtype `"success"`, sessionId, duration fields, cost, usage, result text) and verify all fields parse correctly; this confirms ResultMessage works as end-of-turn delimiter in the session protocol

## Implementation (Green)

### SDKUserMessage
- [x] [impl] Create `works/iterative/claude/core/model/SDKUserMessage.scala` with case class `SDKUserMessage(content: String, sessionId: String, parentToolUseId: Option[String] = None)` and a `given Encoder[SDKUserMessage]` in the companion object that produces the exact protocol JSON shape; include PURPOSE comments
- [x] [impl] Run SDKUserMessage tests and verify they pass

### Message hierarchy additions
- [x] [impl] Add `case object KeepAliveMessage extends Message` to `Message.scala`
- [x] [impl] Add `case class StreamEventMessage(data: Map[String, Any]) extends Message` to `Message.scala`
- [x] [impl] Add `"keep_alive"` and `"stream_event"` branches to `JsonParser.parseMessage` dispatch, with corresponding private parser methods
- [x] [impl] Run all JsonParser tests and verify they pass (new + existing)

## Integration
- [x] [integration] Create `test/works/iterative/claude/core/model/SDKUserMessageRoundTripTest.scala` -- write a mock CLI bash script (inline heredoc) that reads a JSON line from stdin and echoes back an assistant message + result message to stdout; test encodes an SDKUserMessage, feeds it to the script via `ProcessBuilder`, reads stdout lines, parses them with `JsonParser`, and verifies the round-trip produces `AssistantMessage` and `ResultMessage`
- [x] [integration] Create `test/works/iterative/claude/core/model/SDKUserMessageE2ETest.scala` -- gated on `claude` CLI availability via `TestAssumptions.assumeCommand("claude")`; starts `claude --print --input-format stream-json --output-format stream-json`, writes a properly formatted SDKUserMessage JSON line to stdin, and verifies the CLI does not error (exit code 0 or produces parseable output)
- [x] [integration] Run full test suite (`scala-cli test .`) to confirm no regressions
