# Phase 5 Tasks: Service implementations

## Setup

- [x] [setup] Create directory structure for `direct/log/` and `effectful/log/` packages
- [x] [setup] Create directory structure for test files under `test/works/iterative/claude/direct/log/` and `test/works/iterative/claude/effectful/log/`

## Tests First

- [x] [test] Write `ProjectPathDecoderTest` — test decoding of encoded project directory names to filesystem paths (various patterns, edge cases, empty/root)
- [x] [test] Write `DirectConversationLogIndexTest` — integration tests with temp directory structure containing `.jsonl` files; test `listSessions` and `forSession`
- [x] [test] Write `DirectConversationLogReaderTest` — integration tests with temp `.jsonl` files containing representative log entries; test `readAll` and `stream`
- [x] [test] Write `EffectfulConversationLogIndexTest` — same scenarios as direct index but using `munit-cats-effect` for IO-based assertions
- [x] [test] Write `EffectfulConversationLogReaderTest` — same scenarios as direct reader but using `munit-cats-effect` for IO-based and stream-based assertions

## Implementation

- [x] [impl] Implement `ProjectPathDecoder` in `core.log` — pure function to decode project directory names to best-effort filesystem path strings
- [x] [impl] Implement `DirectConversationLogIndex` in `direct.log` — os-lib file discovery implementing `ConversationLogIndex[[A] =>> A]`
- [x] [impl] Implement `DirectConversationLogReader` in `direct.log` — os-lib + Ox Flow implementing `ConversationLogReader[[A] =>> A]` with `EntryStream = ox.flow.Flow[ConversationLogEntry]`
- [x] [impl] Implement `EffectfulConversationLogIndex` in `effectful.log` — fs2.io.file implementing `ConversationLogIndex[IO]`
- [x] [impl] Implement `EffectfulConversationLogReader` in `effectful.log` — fs2.Stream implementing `ConversationLogReader[IO]` with `EntryStream = fs2.Stream[IO, ConversationLogEntry]`

## Integration

- [x] [integration] Verify all existing tests still pass after adding new implementations
- [x] [integration] Verify no compilation warnings
**Phase Status:** Complete
