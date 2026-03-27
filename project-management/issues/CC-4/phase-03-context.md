# Phase 3: Log entry parser

## Goals

Build `ConversationLogParser` — a pure parsing object for JSONL conversation log lines. Each line in a log file is a self-contained JSON object (envelope) carrying metadata plus a type-specific payload. The parser converts raw JSON lines into typed `ConversationLogEntry` instances, reusing `ContentBlockParser` for content blocks within messages.

## Scope

### In Scope

1. **Create `ConversationLogParser` object** in `core.log.parsing` package:
   - `parseLogLine(line: String): Option[ConversationLogEntry]` — parse a single JSONL line
   - `parseLogEntry(json: Json): Option[ConversationLogEntry]` — parse a pre-parsed JSON value
   - Internal helpers for envelope metadata and each payload type

2. **Parse envelope metadata** common to all entry types:
   - `uuid: String` (required)
   - `parentUuid: Option[String]`
   - `timestamp: Option[Instant]` — ISO-8601 string parsed to `java.time.Instant`
   - `sessionId: String` (required)
   - `isSidechain: Boolean` (default false)
   - `cwd: Option[String]`
   - `version: Option[String]`

3. **Parse each entry type payload** based on the `type` field:
   - `"human"` → `UserLogEntry(content: List[ContentBlock])` — user message content as array of content blocks; handle both string and array content formats
   - `"assistant"` → `AssistantLogEntry(content, model, usage, requestId)` — assistant messages with content blocks, model name, token usage, and request ID
   - `"system"` → `SystemLogEntry(subtype, data)` — system events with subtype and data map
   - `"progress"` → `ProgressLogEntry(data, parentToolUseId)` — progress updates
   - `"queue_operation"` → `QueueOperationLogEntry(operation, content)` — queue operations
   - `"file_history_snapshot"` → `FileHistorySnapshotLogEntry(data)` — file state snapshots
   - `"last_prompt"` → `LastPromptLogEntry(data)` — last prompt markers
   - Unknown types → `RawLogEntry(entryType, json)` — preserve raw JSON for forward compatibility

4. **Parse `TokenUsage`** from assistant message `message.usage` field:
   - `input_tokens`, `output_tokens` (required)
   - `cache_creation_input_tokens`, `cache_read_input_tokens` (optional)
   - `service_tier` (optional)

5. **Handle user message content formats**:
   - String content → wrap in `List(TextBlock(content))`
   - Array content → parse each element via `ContentBlockParser`

6. **Reuse `ContentBlockParser`** for all content block parsing within log entries

### Out of Scope

- File I/O (Phase 5 — service implementations handle reading JSONL files)
- Service traits (Phase 4)
- Streaming/batched parsing (Phase 5 — readers handle line-by-line streaming)

## Dependencies

### Prior Phases
- **Phase 1**: All domain types (`ConversationLogEntry`, `LogEntryPayload` variants, `TokenUsage`, `ContentBlock` variants)
- **Phase 2**: `ContentBlockParser.parseContentBlock` for content block parsing within log entries

### External Dependencies
- `io.circe.{Json, parser}` — JSON parsing (already used throughout)
- `java.time.Instant` — ISO-8601 timestamp parsing

## Approach

### Implementation Strategy

1. Create `works/iterative/claude/core/log/parsing/ConversationLogParser.scala`
2. Implement `parseLogLine` as entry point (string → parse JSON → delegate to `parseLogEntry`)
3. Implement `parseLogEntry` — extract envelope metadata, then dispatch on `type` field to payload parsers
4. Implement private payload parsers for each entry type
5. Implement `parseTokenUsage` helper for assistant message usage data
6. Implement content parsing helper that handles both string and array content formats
7. Write comprehensive tests covering all entry types, edge cases, and error handling

### Key Design Decisions

- **Same patterns as `JsonParser`**: Use `object` with public entry points and private helpers, `Option`-based error handling, circe `HCursor` for field extraction
- **Forward compatibility via `RawLogEntry`**: Unknown entry types are captured with their raw JSON rather than silently dropped
- **Graceful degradation**: Malformed lines return `None`; individual optional fields use `Option` to avoid dropping entries with partial data
- **Content format normalization**: User messages in logs can be either a string or an array of content blocks; the parser normalizes both to `List[ContentBlock]`
- **Reuse over duplication**: Delegate to `ContentBlockParser` for content blocks; use similar `extractJsonValue` pattern from `JsonParser` for untyped data maps

## Files to Create/Modify

### New Files
- `works/iterative/claude/core/log/parsing/ConversationLogParser.scala` — pure log entry parser
- `test/works/iterative/claude/core/log/parsing/ConversationLogParserTest.scala` — comprehensive unit tests

### Modified Files
- None expected

## Testing Strategy

### Unit Tests (ConversationLogParserTest)

**Entry point tests:**
- Parse valid JSONL line → `Some(ConversationLogEntry)`
- Parse empty/whitespace line → `None`
- Parse invalid JSON → `None`
- Parse JSON missing required envelope fields (uuid, sessionId) → `None`

**Envelope metadata tests:**
- Parse all envelope fields present
- Parse with optional fields missing (parentUuid, timestamp, cwd, version)
- Parse ISO-8601 timestamp to `Instant`
- Parse `isSidechain` defaulting to `false` when absent

**Payload type tests (one per entry type):**
- `"human"` with string content → `UserLogEntry` with `List(TextBlock(...))`
- `"human"` with array content → `UserLogEntry` with parsed content blocks
- `"assistant"` with content blocks, model, usage, requestId → `AssistantLogEntry`
- `"assistant"` with minimal fields (no usage, no model) → `AssistantLogEntry`
- `"system"` with subtype and data → `SystemLogEntry`
- `"progress"` with data and parentToolUseId → `ProgressLogEntry`
- `"queue_operation"` with operation and content → `QueueOperationLogEntry`
- `"file_history_snapshot"` with data → `FileHistorySnapshotLogEntry`
- `"last_prompt"` with data → `LastPromptLogEntry`
- Unknown type → `RawLogEntry` with preserved JSON

**TokenUsage tests:**
- Parse full usage data (all fields)
- Parse minimal usage data (only required fields)

**Regression:**
- Existing `ContentBlockParser` and `JsonParser` tests still pass

## Acceptance Criteria

1. `ConversationLogParser` object exists in `works.iterative.claude.core.log.parsing`
2. `parseLogLine` and `parseLogEntry` are public entry points
3. All 8 payload types are parsed correctly (7 known + RawLogEntry for unknown)
4. User message content handles both string and array formats
5. `TokenUsage` is parsed from assistant message usage data
6. Envelope metadata (uuid, parentUuid, timestamp, sessionId, isSidechain, cwd, version) is correctly extracted
7. Unknown entry types produce `RawLogEntry` with preserved JSON
8. Malformed/empty lines return `None`
9. All new and existing tests pass
10. New files have PURPOSE headers
11. No compilation warnings
