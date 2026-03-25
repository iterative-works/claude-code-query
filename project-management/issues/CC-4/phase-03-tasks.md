# Phase 3 Tasks: Log entry parser

## Setup

- [ ] [setup] Verify all existing tests pass before starting
- [ ] [setup] Create `works/iterative/claude/core/log/parsing/` directory structure

## Tests — Entry Points

- [ ] [test] Write ConversationLogParserTest: parseLogLine with valid JSONL line returns Some(ConversationLogEntry)
- [ ] [test] Write ConversationLogParserTest: parseLogLine with empty/whitespace line returns None
- [ ] [test] Write ConversationLogParserTest: parseLogLine with invalid JSON returns None
- [ ] [test] Write ConversationLogParserTest: parseLogEntry with missing required uuid field returns None
- [ ] [test] Write ConversationLogParserTest: parseLogEntry with missing required sessionId field returns None

## Tests — Envelope Metadata

- [ ] [test] Write ConversationLogParserTest: parses all envelope metadata fields (uuid, parentUuid, timestamp, sessionId, isSidechain, cwd, version)
- [ ] [test] Write ConversationLogParserTest: parses with optional fields absent (parentUuid, timestamp, cwd, version)
- [ ] [test] Write ConversationLogParserTest: parses ISO-8601 timestamp string to Instant
- [ ] [test] Write ConversationLogParserTest: isSidechain defaults to false when absent

## Tests — Payload Types

- [ ] [test] Write ConversationLogParserTest: "human" type with string content produces UserLogEntry with List(TextBlock)
- [ ] [test] Write ConversationLogParserTest: "human" type with array content produces UserLogEntry with parsed content blocks
- [ ] [test] Write ConversationLogParserTest: "assistant" type with content, model, usage, requestId produces AssistantLogEntry
- [ ] [test] Write ConversationLogParserTest: "assistant" type with minimal fields (no usage, no model) produces AssistantLogEntry
- [ ] [test] Write ConversationLogParserTest: "system" type with subtype and data produces SystemLogEntry
- [ ] [test] Write ConversationLogParserTest: "progress" type with data and parentToolUseId produces ProgressLogEntry
- [ ] [test] Write ConversationLogParserTest: "queue_operation" type with operation and content produces QueueOperationLogEntry
- [ ] [test] Write ConversationLogParserTest: "file_history_snapshot" type with data produces FileHistorySnapshotLogEntry
- [ ] [test] Write ConversationLogParserTest: "last_prompt" type with data produces LastPromptLogEntry
- [ ] [test] Write ConversationLogParserTest: unknown type produces RawLogEntry with preserved JSON

## Tests — TokenUsage

- [ ] [test] Write ConversationLogParserTest: parses full token usage (all fields including cache and service_tier)
- [ ] [test] Write ConversationLogParserTest: parses minimal token usage (only input_tokens and output_tokens)

## Implementation — Core Parser

- [ ] [impl] Create ConversationLogParser object with parseLogLine method (string to JSON delegation)
- [ ] [impl] Implement parseLogEntry method (JSON to ConversationLogEntry with envelope extraction and type dispatch)
- [ ] [impl] Implement envelope metadata extraction (uuid, parentUuid, timestamp, sessionId, isSidechain, cwd, version)
- [ ] [impl] Implement ISO-8601 timestamp parsing to Instant

## Implementation — Payload Parsers

- [ ] [impl] Implement "human" payload parser with string-to-TextBlock and array content handling
- [ ] [impl] Implement "assistant" payload parser with content blocks, model, usage, requestId
- [ ] [impl] Implement parseTokenUsage helper for assistant message usage data
- [ ] [impl] Implement "system" payload parser with subtype and data map extraction
- [ ] [impl] Implement "progress" payload parser with data map and parentToolUseId
- [ ] [impl] Implement "queue_operation" payload parser with operation and content
- [ ] [impl] Implement "file_history_snapshot" payload parser with data map
- [ ] [impl] Implement "last_prompt" payload parser with data map
- [ ] [impl] Implement unknown type fallback to RawLogEntry

## Integration

- [ ] [verify] All new ConversationLogParserTest tests pass
- [ ] [verify] All existing tests pass (JsonParser, ContentBlockParser, LogModel, ContentBlock)
- [ ] [verify] No compilation warnings
- [ ] [verify] PURPOSE headers present on new files
