# Phase 01 Tasks: Fix entry type name mismatches in ConversationLogParser

## Tasks

- [x] [impl] [x] [reviewed] Write failing tests reproducing the type name mismatches (`"user"` → `RawLogEntry` instead of `UserLogEntry`, `"queue-operation"` → `RawLogEntry` instead of `QueueOperationLogEntry`, `"file-history-snapshot"` → `RawLogEntry` instead of `FileHistorySnapshotLogEntry`)
- [x] [impl] [x] [reviewed] Write failing tests for unhandled entry types (`"permission-mode"` and `"attachment"` fall through to `RawLogEntry`)
- [x] [impl] [x] [reviewed] Fix type name strings in `ConversationLogParser.parsePayload`: `"human"` → `"user"`, `"queue_operation"` → `"queue-operation"`, `"file_history_snapshot"` → `"file-history-snapshot"`
- [x] [impl] [x] [reviewed] Add model types for `PermissionModeLogEntry` and `AttachmentLogEntry` in `LogEntryPayload.scala`
- [x] [impl] [x] [reviewed] Add match cases for `"permission-mode"` and `"attachment"` in `parsePayload`
- [x] [impl] [x] [reviewed] Update all existing test fixtures to use correct type names (`"user"`, `"queue-operation"`, `"file-history-snapshot"`) and update test descriptions
- [x] [impl] [x] [reviewed] Verify all tests pass and no compilation warnings (`./mill core.compile` and `./mill core.test`)
**Phase Status:** Complete
