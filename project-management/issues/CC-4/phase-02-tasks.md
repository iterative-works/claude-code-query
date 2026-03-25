# Phase 2 Tasks: Content block parsing extraction

## Setup

- [ ] [setup] Verify all existing tests pass before making changes

## Tests

- [ ] [test] Write ContentBlockParserTest: parse text block returns TextBlock
- [ ] [test] Write ContentBlockParserTest: parse tool_use block returns ToolUseBlock
- [ ] [test] Write ContentBlockParserTest: parse tool_result block returns ToolResultBlock
- [ ] [test] Write ContentBlockParserTest: parse thinking block returns ThinkingBlock
- [ ] [test] Write ContentBlockParserTest: parse redacted_thinking block returns RedactedThinkingBlock
- [ ] [test] Write ContentBlockParserTest: parse unknown type returns None
- [ ] [test] Write ContentBlockParserTest: parse JSON without type field returns None

## Implementation

- [ ] [impl] Create ContentBlockParser object with parseContentBlock method (all 5 block types)
- [ ] [impl] Update JsonParser to delegate to ContentBlockParser, remove private parseContentBlock

## Integration

- [ ] [integration] Verify all existing JsonParser tests pass (core + direct)
- [ ] [integration] Verify no compilation warnings
