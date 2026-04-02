# Phase 2: Content block parsing extraction

## Goals

Extract shared content block parsing logic from `JsonParser.parseContentBlock` into a standalone `ContentBlockParser` object, add parsing for `thinking` and `redacted_thinking` block types, and update `JsonParser` to delegate to the new parser. All existing tests must continue to pass.

## Scope

### In Scope

1. **Create `ContentBlockParser` object** in `core.parsing` package:
   - Extract the content block parsing logic currently in `JsonParser.parseContentBlock` (lines 47-67)
   - Public method `parseContentBlock(json: Json): Option[ContentBlock]`
   - Handles: `text`, `tool_use`, `tool_result`, `thinking`, `redacted_thinking`
   - Unknown types return `None` (same behavior as current code)

2. **Add thinking block parsing**:
   - `"thinking"` type → `ThinkingBlock(thinking, signature)` — both fields required
   - `"redacted_thinking"` type → `RedactedThinkingBlock(data)`

3. **Update `JsonParser`** to delegate to `ContentBlockParser`:
   - Remove `private def parseContentBlock` from `JsonParser`
   - Call `ContentBlockParser.parseContentBlock` instead

4. **Regression verification**:
   - All existing `JsonParserTest` tests (core and direct) must pass unchanged
   - Property-based round-trip tests must still work (the direct test already serializes `ThinkingBlock` and `RedactedThinkingBlock`)

### Out of Scope

- Log entry parsing (Phase 3)
- Any changes to `Message` types
- Changes to direct/effectful `JsonParser` wrappers (they delegate to core `JsonParser` which delegates to `ContentBlockParser`)

## Dependencies

### Prior Phases
- **Phase 1**: `ThinkingBlock` and `RedactedThinkingBlock` already exist in `ContentBlock.scala`

### External Dependencies
- `io.circe.Json` — for JSON parsing (already used)

## Approach

### Implementation Strategy

1. Create `ContentBlockParser.scala` in `works/iterative/claude/core/parsing/`
2. Move the content block parsing logic from `JsonParser.parseContentBlock` into `ContentBlockParser.parseContentBlock`
3. Add `thinking` and `redacted_thinking` cases
4. Update `JsonParser.parseAssistantMessage` to call `ContentBlockParser.parseContentBlock` instead of the private method
5. Remove the private `parseContentBlock` from `JsonParser`
6. Write unit tests for `ContentBlockParser` covering all 5 block types plus unknown types
7. Verify all existing tests pass

### Key Design Decisions

- **Standalone object, not trait**: `ContentBlockParser` is a pure parsing utility with no state, so an `object` is appropriate (matches `JsonParser` pattern)
- **Same signature**: `parseContentBlock(json: Json): Option[ContentBlock]` — identical to the current private method for seamless delegation
- **Reusable by Phase 3**: `ConversationLogParser` (Phase 3) will call `ContentBlockParser.parseContentBlock` for content blocks within log entries

## Files to Create/Modify

### New Files
- `works/iterative/claude/core/parsing/ContentBlockParser.scala` — extracted content block parsing with thinking support
- `test/works/iterative/claude/core/parsing/ContentBlockParserTest.scala` — unit tests for all block types

### Modified Files
- `works/iterative/claude/core/parsing/JsonParser.scala` — remove private `parseContentBlock`, delegate to `ContentBlockParser`

## Testing Strategy

### Unit Tests (ContentBlockParserTest)
- Parse `text` block → `TextBlock`
- Parse `tool_use` block → `ToolUseBlock`
- Parse `tool_result` block → `ToolResultBlock`
- Parse `thinking` block → `ThinkingBlock`
- Parse `redacted_thinking` block → `RedactedThinkingBlock`
- Parse unknown type → `None`
- Parse JSON without `type` field → `None`
- Parse `tool_result` with optional fields (content, isError)

### Regression Tests
- All 7 tests in `core/parsing/JsonParserTest.scala` pass
- All tests in `direct/internal/parsing/JsonParserTest.scala` pass (including property-based round-trip tests that already serialize ThinkingBlock and RedactedThinkingBlock)

## Acceptance Criteria

1. `ContentBlockParser` object exists in `works.iterative.claude.core.parsing`
2. `ContentBlockParser.parseContentBlock` handles all 5 content block types
3. `JsonParser` no longer has a private `parseContentBlock` method
4. `JsonParser` delegates to `ContentBlockParser` for content block parsing
5. All existing tests pass without modification
6. New `ContentBlockParserTest` covers all block types
7. New file has PURPOSE headers
8. No compilation warnings
