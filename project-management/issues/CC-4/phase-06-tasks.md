# Phase 6 Tasks: Re-exports and documentation

## Setup

- [x] [setup] Read current `direct/package.scala` and verify existing re-export pattern
- [x] [setup] Inventory all public types from Phases 1-5 that need re-exporting

## Tests

- [x] [test] Write compilation test verifying `import works.iterative.claude.direct.*` provides access to all new types (ThinkingBlock, RedactedThinkingBlock, ConversationLogEntry, LogEntryPayload variants, TokenUsage, LogFileMetadata, service traits, direct implementations)
- [x] [test] Write compilation test verifying `import works.iterative.claude.effectful.*` provides access to all types and effectful implementations
- [x] [test] Verify all existing tests still pass after re-export changes

## Implementation

- [x] [impl] Add ContentBlock variant re-exports to `direct/package.scala` (ThinkingBlock, RedactedThinkingBlock)
- [x] [impl] Add log model type re-exports to `direct/package.scala` (ConversationLogEntry, LogEntryPayload, all payload variants, TokenUsage, LogFileMetadata)
- [x] [impl] Add service trait and direct implementation re-exports to `direct/package.scala` (ConversationLogIndex, ConversationLogReader, DirectConversationLogIndex, DirectConversationLogReader, ProjectPathDecoder)
- [x] [impl] Create `effectful/package.scala` with existing type re-exports (QueryOptions, Message types, ContentBlock types, PermissionMode) mirroring direct pattern
- [x] [impl] Add all log-related re-exports to `effectful/package.scala` (same types as direct plus EffectfulConversationLogIndex, EffectfulConversationLogReader)

## Documentation

- [x] [docs] Update ARCHITECTURE.md with conversation log parsing section (domain model, parsing layer, service layer, dual implementations)
- [x] [docs] Update ContentBlock hierarchy diagram in ARCHITECTURE.md to include ThinkingBlock and RedactedThinkingBlock
- [x] [docs] Update README.md with conversation log parsing usage examples (listing sessions, reading entries, direct and effectful examples)

## Integration

- [x] [integration] Run full test suite to verify no regressions
- [x] [integration] Verify compilation succeeds with no new warnings
**Phase Status:** Complete
