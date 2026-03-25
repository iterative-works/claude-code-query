# Phase 4 Tasks: Service traits

## Setup

- [x] [setup] Create source files with package declarations and PURPOSE headers

## Tests

- [x] [test] Write compilation test for `ConversationLogIndex` with identity `F` (direct style)
- [x] [test] Write compilation test for `ConversationLogIndex` with `IO` (effectful style)
- [x] [test] Write compilation test for `ConversationLogReader` with identity `F` and `List` stream type
- [x] [test] Write compilation test for `ConversationLogReader` with `IO` and `fs2.Stream` stream type

## Implementation

- [x] [impl] Define `ConversationLogIndex[F[_]]` trait with `listSessions` and `forSession`
- [x] [impl] Define `ConversationLogReader[F[_]]` trait with `readAll` and `stream` (EntryStream type member)

## Verification

- [x] [verify] Run all existing tests to confirm no regressions
- [x] [verify] Confirm no compilation warnings

**Phase Status:** Complete
