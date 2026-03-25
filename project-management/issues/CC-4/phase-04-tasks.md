# Phase 4 Tasks: Service traits

## Setup

- [ ] [setup] Create source files with package declarations and PURPOSE headers

## Tests

- [ ] [test] Write compilation test for `ConversationLogIndex` with identity `F` (direct style)
- [ ] [test] Write compilation test for `ConversationLogIndex` with `IO` (effectful style)
- [ ] [test] Write compilation test for `ConversationLogReader` with identity `F` and `List` stream type
- [ ] [test] Write compilation test for `ConversationLogReader` with `IO` and `fs2.Stream` stream type

## Implementation

- [ ] [impl] Define `ConversationLogIndex[F[_]]` trait with `listSessions` and `forSession`
- [ ] [impl] Define `ConversationLogReader[F[_]]` trait with `readAll` and `stream` (EntryStream type member)

## Verification

- [ ] [verify] Run all existing tests to confirm no regressions
- [ ] [verify] Confirm no compilation warnings
