# Phase 1: Domain types

## Goals

Define all new domain types needed for conversation log parsing support. This phase establishes the type foundation that all subsequent phases depend on.

## Scope

### In Scope

1. **Extend `ContentBlock` sealed trait** (`core.model.ContentBlock`):
   - Add `ThinkingBlock(thinking: String, signature: String)` — extended thinking content from API
   - Add `RedactedThinkingBlock(data: String)` — redacted thinking for safety-filtered content

2. **Fix exhaustiveness warnings** in existing codebase:
   - Any pattern matches on `ContentBlock` must handle the new variants
   - Check `core.parsing.JsonParser.parseContentBlock` (already has `case _ => None`)
   - Check direct and effectful wrappers

3. **New log model types** in `core.log.model` package:
   - `ConversationLogEntry` — envelope type with common metadata:
     - `uuid: String`
     - `parentUuid: Option[String]`
     - `timestamp: Option[java.time.Instant]`
     - `sessionId: String`
     - `isSidechain: Boolean`
     - `cwd: Option[String]`
     - `version: Option[String]`
     - `payload: LogEntryPayload`
   - `LogEntryPayload` — sealed trait with variants:
     - `UserLogEntry(content: List[ContentBlock])` — user messages (content block arrays in logs)
     - `AssistantLogEntry(content: List[ContentBlock], model: Option[String], usage: Option[TokenUsage], requestId: Option[String])` — assistant messages with metadata
     - `SystemLogEntry(subtype: String, data: Map[String, Any])` — system events
     - `ProgressLogEntry(data: Map[String, Any], parentToolUseId: Option[String])` — progress updates
     - `QueueOperationLogEntry(operation: String, content: Option[String])` — queue operations
     - `FileHistorySnapshotLogEntry(data: Map[String, Any])` — file state snapshots
     - `LastPromptLogEntry(data: Map[String, Any])` — last prompt markers
     - `RawLogEntry(entryType: String, json: io.circe.Json)` — unknown/future entry types
   - `TokenUsage` — structured token usage:
     - `inputTokens: Int`
     - `outputTokens: Int`
     - `cacheCreationInputTokens: Option[Int]`
     - `cacheReadInputTokens: Option[Int]`
     - `serviceTier: Option[String]`
   - `LogFileMetadata` — metadata about a log file for the index service:
     - `path: os.Path`
     - `sessionId: String`
     - `summary: Option[String]`
     - `lastModified: java.time.Instant`
     - `fileSize: Long`
     - `cwd: Option[String]`
     - `gitBranch: Option[String]`
     - `createdAt: Option[java.time.Instant]`

### Out of Scope

- Parsing logic (Phase 2 and 3)
- Service traits (Phase 4)
- Service implementations (Phase 5)
- Re-exports to direct/effectful packages (Phase 6)

## Dependencies

### Prior Phases
None — this is the first phase.

### External Dependencies
- `io.circe.Json` — used in `RawLogEntry` for preserving raw JSON of unknown entry types
- `os.Path` — used in `LogFileMetadata` (already a project dependency)
- `java.time.Instant` — for timestamp fields

## Approach

### Implementation Strategy

1. Add `ThinkingBlock` and `RedactedThinkingBlock` to existing `ContentBlock.scala`
2. Verify no exhaustiveness warnings are introduced (check existing pattern matches)
3. Create new `core/log/model/` package directory
4. Define `TokenUsage` case class (no dependencies on other new types)
5. Define `LogEntryPayload` sealed trait and all variants
6. Define `ConversationLogEntry` envelope
7. Define `LogFileMetadata` case class
8. All files must have PURPOSE headers per project conventions

### Key Design Decisions

- **Separate type hierarchies**: Log types (`LogEntryPayload`) are completely separate from stream types (`Message`). Shared parts are at the `ContentBlock` level only.
- **`RawLogEntry` for forward compatibility**: Unknown entry types are captured with their raw JSON rather than silently dropped.
- **`Option[Instant]` for timestamps**: The TS SDK marks timestamp as optional; we follow suit.
- **`List[ContentBlock]` for user content**: Log user messages contain content block arrays (including `tool_result` blocks), unlike stream `UserMessage(String)`.

## Files to Create/Modify

### New Files
- `works/iterative/claude/core/log/model/ConversationLogEntry.scala` — envelope type
- `works/iterative/claude/core/log/model/LogEntryPayload.scala` — sealed trait + all payload variants
- `works/iterative/claude/core/log/model/TokenUsage.scala` — token usage case class
- `works/iterative/claude/core/log/model/LogFileMetadata.scala` — file metadata case class

### Modified Files
- `works/iterative/claude/core/model/ContentBlock.scala` — add ThinkingBlock and RedactedThinkingBlock

### Files to Check for Exhaustiveness
- `works/iterative/claude/core/parsing/JsonParser.scala` — `parseContentBlock` already has `case _ => None`
- `works/iterative/claude/direct/internal/parsing/JsonParser.scala` — wraps core parser
- `works/iterative/claude/effectful/internal/parsing/JsonParser.scala` — wraps core parser
- Any tests matching on ContentBlock

## Testing Strategy

### Unit Tests
- Verify all new types can be constructed with required fields
- Verify `ConversationLogEntry` correctly holds each `LogEntryPayload` variant
- Verify `TokenUsage` field access and defaults
- Verify `LogFileMetadata` construction
- Verify `ThinkingBlock` and `RedactedThinkingBlock` are valid `ContentBlock` instances

### Compilation Checks
- Ensure no new exhaustiveness warnings after adding ContentBlock variants
- Ensure all existing tests still compile and pass

## Acceptance Criteria

1. `ThinkingBlock` and `RedactedThinkingBlock` exist as `ContentBlock` variants
2. All log domain types compile with correct field types
3. No exhaustiveness warnings introduced in existing code
4. All existing tests pass unchanged
5. All new files have PURPOSE headers
6. New types are in `works.iterative.claude.core.log.model` package
