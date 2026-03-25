# Implementation Tasks: Add conversation log parsing support

**Issue:** CC-4
**Created:** 2026-03-25
**Status:** 0/6 phases complete (0%)

## Phase Index

- [x] Phase 1: Domain types (Est: 1-3h) → `phase-01-context.md`
- [x] Phase 2: Content block parsing extraction (Est: 1-2h) → `phase-02-context.md`
- [ ] Phase 3: Log entry parser (Est: 2-3h) → `phase-03-context.md`
- [ ] Phase 4: Service traits (Est: 1h) → `phase-04-context.md`
- [ ] Phase 5: Service implementations (Est: 2-3h) → `phase-05-context.md`
- [ ] Phase 6: Re-exports and documentation (Est: 1h) → `phase-06-context.md`

## Phase Details

### Phase 1: Domain types
**Package:** `core.log.model` + `core.model`
- Add `ThinkingBlock(thinking: String, signature: String)` to `ContentBlock` sealed trait
- Add `RedactedThinkingBlock(data: String)` to `ContentBlock` sealed trait
- Fix exhaustiveness warnings in existing code
- Define `ConversationLogEntry` envelope (uuid, parentUuid, timestamp, sessionId, isSidechain, metadata)
- Define `LogEntryPayload` sealed trait with all payload variants (UserLogEntry, AssistantLogEntry, SystemLogEntry, ProgressLogEntry, QueueOperationLogEntry, FileHistorySnapshotLogEntry, LastPromptLogEntry, RawLogEntry)
- Define `TokenUsage` case class
- Define `LogFileMetadata` case class (sessionId, summary, lastModified, fileSize, cwd, gitBranch, createdAt)

### Phase 2: Content block parsing extraction
**Package:** `core.parsing`
- Extract shared content block parsing from `JsonParser.parseContentBlock` (currently private) into `ContentBlockParser`
- Add `thinking` and `redacted_thinking` block parsing
- Update `JsonParser` to delegate to `ContentBlockParser`
- Verify all existing JsonParser tests still pass

### Phase 3: Log entry parser
**Package:** `core.log.parsing`
- Build `ConversationLogParser` — pure parsing for JSONL log lines
- Parse envelope metadata (uuid, parentUuid, timestamp, sessionId, etc.)
- Parse each entry type payload (user, assistant, system, progress, queue-operation, file-history-snapshot, last-prompt)
- Capture unknown entry types as `RawLogEntry`
- Reuse `ContentBlockParser` for content blocks within messages
- Handle user message content as `List[ContentBlock]` (string or array)
- Parse `TokenUsage` from assistant message usage data

### Phase 4: Service traits
**Package:** `core.log`
- Define `ConversationLogIndex` trait (list all sessions, list by project, get by session ID)
- Define `ConversationLogReader` trait (stream entries, read all entries)
- Support full discovery: all projects, per-project, by session ID

### Phase 5: Service implementations
**Packages:** `direct.log` + `effectful.log`
- Direct `ConversationLogIndex` using os-lib for file discovery and path decoding
- Direct `ConversationLogReader` using os-lib + Ox Flow for streaming
- Effectful `ConversationLogIndex` using fs2.io.file
- Effectful `ConversationLogReader` using fs2.Stream
- Path decoding for `~/.claude/projects/` directory structure

### Phase 6: Re-exports and documentation
- Update `direct.package` and `effectful.package` to re-export new types
- Update ARCHITECTURE.md with log parsing section
- Update README.md with usage examples

## Progress Tracker

**Completed:** 0/6 phases
**Estimated Total:** 8-13 hours
**Time Spent:** 0 hours

## Notes

- Phase context files generated just-in-time during implementation
- Use wf-implement to start next phase automatically
- Estimates are rough and will be refined during implementation
- Phases follow layer dependency order from analysis
- All CLARIFYs resolved: separate type hierarchies, full discovery, required ThinkingBlock fields, Option[Instant] timestamps
