# Phase 3 Tasks: Log entry parser

## Setup

- [x] [setup] Verify all existing tests pass before starting
- [x] [setup] Create `works/iterative/claude/core/log/parsing/` directory structure

## Tests — Entry Points

- [x] [test] Write ConversationLogParserTest: parseLogLine with valid JSONL line returns Some(ConversationLogEntry)
- [x] [test] Write ConversationLogParserTest: parseLogLine with empty/whitespace line returns None
- [x] [test] Write ConversationLogParserTest: parseLogLine with invalid JSON returns None
- [x] [test] Write ConversationLogParserTest: parseLogEntry with missing required uuid field returns None
- [x] [test] Write ConversationLogParserTest: parseLogEntry with missing required sessionId field returns None

## Tests — Envelope Metadata

- [x] [test] Write ConversationLogParserTest: parses all envelope metadata fields (uuid, parentUuid, timestamp, sessionId, isSidechain, cwd, version)
- [x] [test] Write ConversationLogParserTest: parses with optional fields absent (parentUuid, timestamp, cwd, version)
- [x] [test] Write ConversationLogParserTest: parses ISO-8601 timestamp string to Instant
- [x] [test] Write ConversationLogParserTest: isSidechain defaults to false when absent

## Tests — Payload Types

- [x] [test] Write ConversationLogParserTest: "human" type with string content produces UserLogEntry with List(TextBlock)
- [x] [test] Write ConversationLogParserTest: "human" type with array content produces UserLogEntry with parsed content blocks
- [x] [test] Write ConversationLogParserTest: "assistant" type with content, model, usage, requestId produces AssistantLogEntry
- [x] [test] Write ConversationLogParserTest: "assistant" type with minimal fields (no usage, no model) produces AssistantLogEntry
- [x] [test] Write ConversationLogParserTest: "system" type with subtype and data produces SystemLogEntry
- [x] [test] Write ConversationLogParserTest: "progress" type with data and parentToolUseId produces ProgressLogEntry
- [x] [test] Write ConversationLogParserTest: "queue_operation" type with operation and content produces QueueOperationLogEntry
- [x] [test] Write ConversationLogParserTest: "file_history_snapshot" type with data produces FileHistorySnapshotLogEntry
- [x] [test] Write ConversationLogParserTest: "last_prompt" type with data produces LastPromptLogEntry
- [x] [test] Write ConversationLogParserTest: unknown type produces RawLogEntry with preserved JSON

## Tests — TokenUsage

- [x] [test] Write ConversationLogParserTest: parses full token usage (all fields including cache and service_tier)
- [x] [test] Write ConversationLogParserTest: parses minimal token usage (only input_tokens and output_tokens)

## Implementation — Core Parser

- [x] [impl] Create ConversationLogParser object with parseLogLine method (string to JSON delegation)
- [x] [impl] Implement parseLogEntry method (JSON to ConversationLogEntry with envelope extraction and type dispatch)
- [x] [impl] Implement envelope metadata extraction (uuid, parentUuid, timestamp, sessionId, isSidechain, cwd, version)
- [x] [impl] Implement ISO-8601 timestamp parsing to Instant

## Implementation — Payload Parsers

- [x] [impl] Implement "human" payload parser with string-to-TextBlock and array content handling
- [x] [impl] Implement "assistant" payload parser with content blocks, model, usage, requestId
- [x] [impl] Implement parseTokenUsage helper for assistant message usage data
- [x] [impl] Implement "system" payload parser with subtype and data map extraction
- [x] [impl] Implement "progress" payload parser with data map and parentToolUseId
- [x] [impl] Implement "queue_operation" payload parser with operation and content
- [x] [impl] Implement "file_history_snapshot" payload parser with data map
- [x] [impl] Implement "last_prompt" payload parser with data map
- [x] [impl] Implement unknown type fallback to RawLogEntry

## Integration

- [x] [verify] All new ConversationLogParserTest tests pass
- [x] [verify] All existing tests pass (JsonParser, ContentBlockParser, LogModel, ContentBlock)
- [x] [verify] No compilation warnings
- [x] [verify] PURPOSE headers present on new files
**Phase Status:** Complete
