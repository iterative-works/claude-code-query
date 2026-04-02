# Phase 6: Re-exports and documentation

## Goals

Complete the public API surface for conversation log parsing by:
1. Adding re-exports for new log-related types to the `direct` package object
2. Creating a new `effectful` package object with re-exports for both existing and new types
3. Updating ARCHITECTURE.md with the conversation log parsing architecture
4. Updating README.md with log parsing usage examples

## Scope

### In Scope
- Add `ThinkingBlock`, `RedactedThinkingBlock` re-exports to `direct/package.scala`
- Add log model type re-exports (`ConversationLogEntry`, `LogEntryPayload` variants, `TokenUsage`, `LogFileMetadata`) to `direct/package.scala`
- Add log service re-exports (`ConversationLogIndex`, `ConversationLogReader`) and direct implementations to `direct/package.scala`
- Create `effectful/package.scala` mirroring direct's pattern, including both existing types and new log types, plus effectful implementations
- Update ARCHITECTURE.md with a new section covering log parsing architecture (domain types, parsing, service traits, implementations)
- Update README.md with log file reading examples for both direct and effectful APIs

### Out of Scope
- No new functionality — only re-exporting and documenting what was built in Phases 1-5
- No changes to implementation code

## Dependencies on Prior Phases

- **Phase 1** (Domain types): `ThinkingBlock`, `RedactedThinkingBlock`, `ConversationLogEntry`, `LogEntryPayload` variants, `TokenUsage`, `LogFileMetadata`
- **Phase 2** (Content block parsing): `ContentBlockParser` (internal, not re-exported)
- **Phase 3** (Log parser): `ConversationLogParser` (internal, not re-exported)
- **Phase 4** (Service traits): `ConversationLogIndex`, `ConversationLogReader`
- **Phase 5** (Implementations): `DirectConversationLogIndex`, `DirectConversationLogReader`, `EffectfulConversationLogIndex`, `EffectfulConversationLogReader`, `ProjectPathDecoder`

## Approach

### Re-exports Strategy

Follow the existing pattern in `direct/package.scala`: type alias + val re-export for case classes/objects, type alias only for traits/sealed traits.

**Types to add to `direct/package.scala`:**
- ContentBlock variants: `ThinkingBlock`, `RedactedThinkingBlock`
- Log model: `ConversationLogEntry`, `LogEntryPayload`, `UserLogEntry`, `AssistantLogEntry`, `SystemLogEntry`, `ProgressLogEntry`, `QueueOperationLogEntry`, `FileHistorySnapshotLogEntry`, `LastPromptLogEntry`, `RawLogEntry`, `TokenUsage`, `LogFileMetadata`
- Service traits: `ConversationLogIndex`, `ConversationLogReader`
- Direct implementations: `DirectConversationLogIndex`, `DirectConversationLogReader`
- Utility: `ProjectPathDecoder`

**Create `effectful/package.scala`:**
- Mirror all existing direct re-exports (QueryOptions, Message types, ContentBlock types, PermissionMode)
- Add all log-related re-exports (same as direct)
- Add effectful implementations: `EffectfulConversationLogIndex`, `EffectfulConversationLogReader`

### Documentation Strategy

**ARCHITECTURE.md additions:**
- New "Conversation Log Parsing" section covering:
  - Log file format overview (JSONL envelope + typed payloads)
  - Domain model (`ConversationLogEntry`, `LogEntryPayload` hierarchy)
  - Parsing layer (`ContentBlockParser`, `ConversationLogParser`)
  - Service layer (traits + dual implementations)
  - `ProjectPathDecoder` for path-encoded directory names
- Update ContentBlock hierarchy diagram to include `ThinkingBlock` and `RedactedThinkingBlock`

**README.md additions:**
- New section "Conversation Log Parsing" with usage examples:
  - Listing available sessions
  - Reading log entries from a session
  - Accessing thinking blocks
  - Both direct and effectful examples

## Files to Modify

1. `works/iterative/claude/direct/package.scala` — add re-exports
2. `works/iterative/claude/effectful/package.scala` — create with full re-exports (NEW)
3. `ARCHITECTURE.md` — add log parsing architecture section
4. `README.md` — add log parsing usage examples

## Testing Strategy

- **Compilation test**: Verify that importing `works.iterative.claude.direct.*` and `works.iterative.claude.effectful.*` provides access to all new types
- **Existing tests**: All existing tests must continue to pass
- No new runtime tests needed — this phase is purely re-exports and documentation

## Acceptance Criteria

- [ ] `import works.iterative.claude.direct.*` gives access to all log-related types and direct implementations
- [ ] `import works.iterative.claude.effectful.*` gives access to all types and effectful implementations
- [ ] ARCHITECTURE.md documents the conversation log parsing architecture
- [ ] README.md includes usage examples for log parsing
- [ ] All existing tests pass
- [ ] Compilation succeeds with no new warnings
