# Phase 1 Tasks: Domain types

## Setup

- [ ] [setup] Create `works/iterative/claude/core/log/model/` directory structure

## Tests

- [ ] [test] Write tests for `ThinkingBlock` and `RedactedThinkingBlock` as ContentBlock variants
- [ ] [test] Write tests for `TokenUsage` construction and field access
- [ ] [test] Write tests for `LogEntryPayload` sealed trait — construct each variant and verify fields
- [ ] [test] Write tests for `ConversationLogEntry` envelope holding each payload variant
- [ ] [test] Write tests for `LogFileMetadata` construction

## Implementation

- [ ] [impl] Add `ThinkingBlock(thinking: String, signature: String)` to `ContentBlock` sealed trait
- [ ] [impl] Add `RedactedThinkingBlock(data: String)` to `ContentBlock` sealed trait
- [ ] [impl] Verify no exhaustiveness warnings in existing code (compile check)
- [ ] [impl] Create `TokenUsage` case class in `core.log.model`
- [ ] [impl] Create `LogEntryPayload` sealed trait with all 8 payload variants in `core.log.model`
- [ ] [impl] Create `ConversationLogEntry` envelope case class in `core.log.model`
- [ ] [impl] Create `LogFileMetadata` case class in `core.log.model`

## Integration

- [ ] [integration] Run full test suite to verify no regressions from ContentBlock changes
- [ ] [integration] Verify all existing tests pass
