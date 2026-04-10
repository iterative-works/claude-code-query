# Implementation Log: Parser mismatches

Issue: CC-41

This log tracks the evolution of investigation and fixes across phases.

---

## Phase 1: Fix entry type name mismatches in ConversationLogParser (2026-04-10)

**Root cause:** The type name strings in `parsePayload` match expression were written using assumed Anthropic API conventions (`"human"`, underscores) rather than actual Claude Code CLI format (`"user"`, hyphens). Test fixtures used the same wrong names, so tests passed but didn't reflect real data.

**Fix applied:**
- `ConversationLogParser.scala` — fixed 3 type name strings (`"human"` → `"user"`, `"queue_operation"` → `"queue-operation"`, `"file_history_snapshot"` → `"file-history-snapshot"`), added 2 new match cases for `"permission-mode"` and `"attachment"` using existing `parseDataOnlyPayload` helper
- `LogEntryPayload.scala` — added `PermissionModeLogEntry` and `AttachmentLogEntry` case classes with `data: Map[String, Any]`
- `ConversationLogParserTest.scala` — updated all fixtures to correct type names, added tests for new types with data assertions and error path coverage
- `DirectConversationLogReaderTest.scala` — updated fixture from `"human"` to `"user"`
- `EffectfulConversationLogReaderTest.scala` — updated fixture from `"human"` to `"user"`

**Regression tests added:**
- 4 new tests (2 happy path with data assertions, 2 error path for missing uuid)

**Code review:**
- Iterations: 1 (critical issues fixed: removed duplicate tests, added data assertions, added error path tests)
- Review file: review-phase-01-20260410-205956.md

**Notes:**
- `last_prompt` match case still uses underscore — reviewers flagged as potential same-class bug but out of scope for this phase (needs verification against real data)

**Files changed:**
```
M	core/src/works/iterative/claude/core/log/model/LogEntryPayload.scala
M	core/src/works/iterative/claude/core/log/parsing/ConversationLogParser.scala
M	core/test/src/works/iterative/claude/core/log/parsing/ConversationLogParserTest.scala
M	direct/test/src/works/iterative/claude/direct/log/DirectConversationLogReaderTest.scala
M	effectful/test/src/works/iterative/claude/effectful/log/EffectfulConversationLogReaderTest.scala
```

---

## Phase 2: Derive agentId from filename in SubAgentMetadataParser (2026-04-10)

**Root cause:** `SubAgentMetadataParser.parse` required `agentId` from JSON via `cursor.get[String]("agentId").toOption` in a for-comprehension guard. Real `.meta.json` files produced by Claude Code CLI contain only `agentType` and `description` — `agentId` is encoded in the transcript filename (`agent-<id>.jsonl`). The for-comprehension always yielded `None` for real data.

**Fix applied:**
- `SubAgentMetadataParser.scala` — replaced for-comprehension with `Option.when(!json.isNull)`, deriving `agentId` from `transcriptPath.last.stripSuffix(".jsonl")` instead of reading from JSON
- `SubAgentMetadataParserTest.scala` — updated all test fixtures to use realistic transcript paths (`agent-<id>.jsonl`) and removed `agentId` from JSON; removed duplicate test; renamed tests for clarity; added 3 new tests
- `DirectConversationLogIndexTest.scala` — updated agentId expectations from `"abc"` to `"agent-abc"` (filename-derived)
- `EffectfulConversationLogIndexTest.scala` — same agentId expectation updates

**Regression tests added:**
- 3 new tests: agentId derived from filename, agentId in JSON ignored, real-world `.meta.json` content

**Code review:**
- Iterations: 1 (no critical issues; warnings fixed: removed duplicate test, inlined cursor variable, improved test names and PURPOSE comment)
- Review file: review-phase-02-20260410-211739.md

**Files changed:**
```
M	core/src/works/iterative/claude/core/log/parsing/SubAgentMetadataParser.scala
M	core/test/src/works/iterative/claude/core/log/parsing/SubAgentMetadataParserTest.scala
M	direct/test/src/works/iterative/claude/direct/log/DirectConversationLogIndexTest.scala
M	effectful/test/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndexTest.scala
```

---

## Phase 3: Support entries without uuid field (2026-04-10)

**Root cause:** `ConversationLogParser.parseLogEntry` used monadic bind (`uuid <- cursor.get[String]("uuid").toOption`) in its for-comprehension. When `uuid` is absent from the JSON (as with `permission-mode`, `file-history-snapshot` entries), `.toOption` yields `None` and the entire for-comprehension short-circuits, silently dropping the entry before payload parsing is attempted.

**Fix applied:**
- `ConversationLogEntry.scala` — changed `uuid: String` to `uuid: Option[String]`
- `ConversationLogParser.scala` — changed `uuid <- cursor.get[String]("uuid").toOption` to `uuid = cursor.get[String]("uuid").toOption` (plain assignment instead of monadic bind), reordered for-comprehension to start with `sessionId <-` as the first required binding
- `ConversationLogParserTest.scala` — updated existing uuid assertions to expect `Some(...)`, added 4 new regression tests covering uuid-absent, uuid-present, file-history-snapshot without uuid, and mixed-entry transcript
- `LogModelTest.scala` — updated all `ConversationLogEntry` constructions to use `Some("...")` for uuid field

**Regression tests added:**
- 4 new tests for uuid optional behavior

**Code review:**
- Iterations: 1 (no critical issues)
- Review file: review-phase-03-20260410-233419.md

**Files changed:**
```
M	core/src/works/iterative/claude/core/log/model/ConversationLogEntry.scala
M	core/src/works/iterative/claude/core/log/parsing/ConversationLogParser.scala
M	core/test/src/works/iterative/claude/core/log/model/LogModelTest.scala
M	core/test/src/works/iterative/claude/core/log/parsing/ConversationLogParserTest.scala
```

---
