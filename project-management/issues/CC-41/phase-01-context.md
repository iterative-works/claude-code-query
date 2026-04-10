# Phase 01: Fix entry type name mismatches in ConversationLogParser

## Defect Description

The `ConversationLogParser.parsePayload` match expression uses type name strings that don't match the actual JSONL format produced by Claude Code CLI. Three type names are wrong and two observed entry types are entirely unhandled:

**Wrong type names (silently fall through to `RawLogEntry`):**
- Line 69: matches `"human"` but real data uses `"user"`
- Line 73: matches `"queue_operation"` but real data uses `"queue-operation"`
- Line 74: matches `"file_history_snapshot"` but real data uses `"file-history-snapshot"`

**Missing entry types (no match case at all):**
- `"permission-mode"` — carries `permissionMode` and `sessionId`
- `"attachment"` — observed in real transcripts

The most impactful bug is `"human"` vs `"user"`: all user entries become `RawLogEntry`, so `numTurns` is always 0 and user-side token metrics are lost.

## Reproduction Steps

1. Create a JSONL line with `"type": "user"` (as produced by real Claude Code sessions):
   ```json
   {"type":"user","uuid":"u1","sessionId":"s1","message":{"content":"hello"}}
   ```
2. Parse it with `ConversationLogParser.parseLogLine`
3. Observe payload is `RawLogEntry("user", ...)` instead of `UserLogEntry(...)`

Same pattern for `"queue-operation"` and `"file-history-snapshot"`.

## Root Cause Hypotheses

**H1 (Confidence: Very High):** The type name strings were written based on assumed format (Anthropic API convention: `"human"`, underscores) rather than the actual Claude Code CLI format (`"user"`, hyphens). The existing test suite also uses the wrong type names, so tests pass but don't reflect real data.

Supporting evidence:
- Real session (08acd9a6) shows 77 `user` entries, 0 `human` entries
- Real data uses hyphens (`file-history-snapshot`) not underscores (`file_history_snapshot`)
- Test fixtures match the buggy code, not real data

## Investigation Plan

1. Confirm the three wrong type name strings in `parsePayload` match expression
2. Identify all test fixtures using the wrong type names
3. Check if any downstream consumers depend on the wrong names (grep for `"human"`, `"queue_operation"`, `"file_history_snapshot"`)
4. Determine required model types for `permission-mode` and `attachment`

## Fix Strategy

1. **Fix type name strings in `parsePayload`:**
   - `"human"` -> `"user"` (line 69)
   - `"queue_operation"` -> `"queue-operation"` (line 73)
   - `"file_history_snapshot"` -> `"file-history-snapshot"` (line 74)

2. **Add model types for new entry types** in `LogEntryPayload.scala`:
   - `PermissionModeLogEntry(permissionMode: String, data: Map[String, Any])` — or use `parseDataOnlyPayload` pattern
   - `AttachmentLogEntry(data: Map[String, Any])` — use `parseDataOnlyPayload` pattern

3. **Add match cases** in `parsePayload` for `"permission-mode"` and `"attachment"`

4. **Update all existing tests** to use correct type names (`"user"` instead of `"human"`, etc.)

5. **Add new tests** for `"permission-mode"` and `"attachment"` entry types

6. **Update test descriptions** that reference old type names (e.g., `"human" type with string content...`)

## Files to Modify

- `core/src/works/iterative/claude/core/log/parsing/ConversationLogParser.scala` — fix 3 type name strings, add 2 new match cases
- `core/src/works/iterative/claude/core/log/model/LogEntryPayload.scala` — add `PermissionModeLogEntry` and `AttachmentLogEntry` types
- `core/test/src/works/iterative/claude/core/log/parsing/ConversationLogParserTest.scala` — update all `"human"` fixtures to `"user"`, `"queue_operation"` to `"queue-operation"`, `"file_history_snapshot"` to `"file-history-snapshot"`; add tests for new types; update test descriptions

## Testing Requirements

**Existing tests to update (type name in fixture JSON):**
- `parseLogLine with valid JSONL line` — uses `"human"`
- `parseLogEntry with missing required uuid field` — uses `"human"`
- `parseLogEntry with missing required sessionId field` — uses `"human"`
- `parses all envelope metadata fields` — uses `"human"`
- `parses with optional fields absent` — uses `"human"`
- `parses ISO-8601 timestamp string to Instant` — uses `"human"`
- `isSidechain defaults to false when absent` — uses `"human"`
- `"human" type with string content` — uses `"human"` in fixture and description
- `"human" type with array content` — uses `"human"` in fixture and description
- `"queue_operation" type with operation and content` — uses `"queue_operation"` in fixture and description
- `"file_history_snapshot" type with data` — uses `"file_history_snapshot"` in fixture and description
- `"queue_operation" type without operation returns None` — uses `"queue_operation"` in fixture and description
- `"human" type without message field returns None` — uses `"human"` in fixture and description
- `JSONL line with agentId field` — uses `"human"`
- `JSONL line without agentId field` — uses `"human"`
- `agentId is excluded from data maps in system entries` — no change needed
- `malformed timestamp string results in None timestamp` — uses `"human"`

**New tests to add:**
- `"permission-mode"` type produces correct payload with `permissionMode` field
- `"attachment"` type produces correct payload
- Verify `"user"` type is parsed as `UserLogEntry` (not just updating old test — confirm the fix works)

## Acceptance Criteria

1. All three wrong type name strings are fixed in `parsePayload`
2. `ConversationLogParser.parseLogLine` correctly produces `UserLogEntry` for `"type": "user"` entries
3. `ConversationLogParser.parseLogLine` correctly produces `QueueOperationLogEntry` for `"type": "queue-operation"` entries
4. `ConversationLogParser.parseLogLine` correctly produces `FileHistorySnapshotLogEntry` for `"type": "file-history-snapshot"` entries
5. New model types exist for `permission-mode` and `attachment` entry types
6. `parsePayload` handles `"permission-mode"` and `"attachment"` without falling through to `RawLogEntry`
7. All existing tests pass with updated type names
8. New tests exist and pass for `permission-mode` and `attachment` types
9. `./mill core.compile` succeeds with no warnings
10. `./mill core.test` passes all tests
