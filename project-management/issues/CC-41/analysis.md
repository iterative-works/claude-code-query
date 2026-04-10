# Diagnostic Analysis: CC-41

**Parser mismatches: wrong entry type names, SubAgentMetadataParser requires missing field**

## Problem Statement

Three related parsing bugs in the conversation log parsing subsystem cause silent data loss when processing real Claude Code JSONL transcripts. The bugs were discovered during transcript analysis of 18 real sessions (PROC-272).

**Severity: High** — core parsing functionality is broken for real-world data, causing all user entries and all sub-agent metadata to be silently dropped.

## Defects

### Defect 1: Entry type name mismatches in ConversationLogParser

**File:** `core/src/works/iterative/claude/core/log/parsing/ConversationLogParser.scala`, lines 68-78

**Observed behavior:** `parsePayload` matches on type strings that don't match the actual JSONL format produced by Claude Code. Real transcripts use `"user"`, `"file-history-snapshot"`, and `"queue-operation"`, but the parser expects `"human"`, `"file_history_snapshot"`, and `"queue_operation"`.

**Impact:**
- All user entries silently fall through to `RawLogEntry` → `numTurns` always 0, user-side token metrics lost
- `file-history-snapshot` entries fall through to `RawLogEntry` instead of `FileHistorySnapshotLogEntry`
- `queue-operation` entries fall through to `RawLogEntry` instead of `QueueOperationLogEntry`

Additionally, two entry types observed in real transcripts are not handled at all:
- `"permission-mode"` — carries `permissionMode` and `sessionId`
- `"attachment"` — observed in real transcripts

**Evidence from real session (08acd9a6):**
```
107 assistant
 77 user          ← parser expects "human", gets 0 matches
  8 file-history-snapshot
  2 permission-mode
  2 attachment
  2 queue-operation
  1 system
```

#### Reproduction Steps

1. Create a JSONL line with `"type": "user"` (as produced by real Claude Code sessions)
2. Parse it with `ConversationLogParser.parseLogLine`
3. Observe the payload is `RawLogEntry("user", ...)` instead of `UserLogEntry(...)`

Similarly for `"file-history-snapshot"` and `"queue-operation"`.

#### Root Cause Hypothesis

**H1 (Confidence: Very High):** The type name strings in the `parsePayload` match expression were written based on an assumed format rather than the actual JSONL format produced by Claude Code. The parser uses `"human"` (Anthropic API convention) instead of `"user"` (Claude Code CLI convention), and uses underscores instead of hyphens for compound type names.

**Supporting evidence:**
- Line 69: `case "human"` — but real data uses `"user"`
- Line 73: `case "queue_operation"` — but real data uses `"queue-operation"`
- Line 74: `case "file_history_snapshot"` — but real data uses `"file-history-snapshot"`
- The existing test suite uses the wrong type names too (e.g., `"type":"human"` in test fixtures)

#### Fix Strategy

1. Change `"human"` → `"user"` in `parsePayload` match (line 69)
2. Change `"queue_operation"` → `"queue-operation"` in `parsePayload` match (line 73)
3. Change `"file_history_snapshot"` → `"file-history-snapshot"` in `parsePayload` match (line 74)
4. Add match cases for `"permission-mode"` and `"attachment"` entry types (new model types needed)
5. Update all existing tests to use the correct type names
6. Add tests for the new entry types

**Effort:** Small — straightforward string changes plus new match cases and model types.

---

### Defect 2: SubAgentMetadataParser requires `agentId` field absent from `.meta.json`

**File:** `core/src/works/iterative/claude/core/log/parsing/SubAgentMetadataParser.scala`, lines 11-13

**Observed behavior:** `parse` requires `agentId` from the JSON:
```scala
for agentId <- cursor.get[String]("agentId").toOption
```

But actual `.meta.json` files contain only `agentType` and `description`:
```json
{"agentType":"iterative-works:code-reviewer","description":"Review security of phase 07"}
```

The agent ID is encoded in the filename (e.g., `agent-a27a237ab9050d9ef.meta.json`).

**Impact:** `parse` returns `None` for every real sub-agent → empty sub-agent lists → no role classification or attribution in transcript analysis.

#### Reproduction Steps

1. Create a JSON object with only `agentType` and `description` (matching real `.meta.json` format)
2. Call `SubAgentMetadataParser.parse(json, transcriptPath)`
3. Observe it returns `None` because `agentId` is missing from JSON

#### Root Cause Hypothesis

**H1 (Confidence: Very High):** The parser was written assuming `agentId` would be in the JSON, but the actual `.meta.json` format doesn't include it. The callers (`DirectConversationLogIndex`, `EffectfulConversationLogIndex`) already pass the `transcriptPath` which contains the agent ID in its filename (e.g., `agent-a27a237ab9050d9ef.jsonl`).

**Supporting evidence:**
- `DirectConversationLogIndex.scala:46-54`: iterates files matching `agent-*.jsonl`, passes `jsonlPath` to parser
- `EffectfulConversationLogIndex.scala:74`: same pattern
- The `transcriptPath` parameter is already available — the agent ID just needs to be extracted from the filename

#### Fix Strategy

1. Derive `agentId` from `transcriptPath` filename instead of requiring it in JSON:
   - Extract from filename pattern `agent-<id>.jsonl` → `agent-<id>`
   - Or strip `.jsonl` suffix from filename: `agent-a27a237ab9050d9ef.jsonl` → `agent-a27a237ab9050d9ef`
2. Make `agentId` no longer required from JSON (remove from for-comprehension guard)
3. Update tests to reflect new behavior (parse succeeds without `agentId` in JSON)

**Effort:** Small — extract ID from path, adjust for-comprehension.

---

### Defect 3: Entries without `uuid` are silently dropped

**File:** `core/src/works/iterative/claude/core/log/parsing/ConversationLogParser.scala`, lines 36-37

**Observed behavior:** `parseLogEntry` requires `uuid` in its for-comprehension:
```scala
for
  uuid <- cursor.get[String]("uuid").toOption
```

Some entry types (`permission-mode`, `file-history-snapshot`, possibly others) don't carry a `uuid` field. These entries are silently dropped before payload parsing is even attempted.

**Impact:** Entry types without `uuid` can never be parsed, regardless of whether their type name is correctly matched. This compounds with Defect 1 for `file-history-snapshot` entries.

#### Reproduction Steps

1. Create a JSONL line with `"type": "file-history-snapshot"` but no `"uuid"` field
2. Parse with `ConversationLogParser.parseLogEntry`
3. Observe it returns `None` due to missing `uuid`

#### Root Cause Hypothesis

**H1 (Confidence: High):** The parser was written assuming all entries have a `uuid`, but some entry types in the Claude Code format don't include one. The `uuid` field should be optional in `ConversationLogEntry`, or entries without `uuid` should receive a synthetic one.

**Supporting evidence:**
- `ConversationLogEntry` stores `uuid: String` (non-optional)
- Real transcripts show entry types without `uuid` field
- The `uuid` is used for parent-child linking — entry types that don't participate in threading may legitimately lack it

#### Fix Strategy

Two options:

**Option A (Minimal):** Make `uuid` optional (`Option[String]`) in `ConversationLogEntry`. Change the for-comprehension to use `= cursor.get[String]("uuid").toOption` instead of `<-`. This preserves all entries but requires downstream consumers to handle `None` uuid.

**Option B (Synthetic UUID):** Generate a synthetic UUID for entries that lack one (e.g., using a hash of the entry content or a random UUID). This preserves the non-optional `uuid: String` type but introduces synthetic data.

**Recommended: Option A** — it's more honest about what the data contains and avoids masking missing data.

**Effort:** Small-Medium — changing `uuid` from `String` to `Option[String]` in `ConversationLogEntry` will ripple to all pattern matches and consumers.

## Estimates

| Defect | Effort | Risk |
|--------|--------|------|
| 1: Entry type name mismatches | 1-2h | Low — straightforward string fixes |
| 2: SubAgentMetadataParser agentId | 1h | Low — extract from existing path |
| 3: UUID-less entries | 1-2h | Medium — type change ripples through consumers |
| **Total** | **3-6h** | |

## Testing Strategy

- **Defect 1:** Update existing test fixtures to use real type names (`"user"`, `"file-history-snapshot"`, `"queue-operation"`). Add tests for `"permission-mode"` and `"attachment"` types.
- **Defect 2:** Update test to verify parsing succeeds with only `agentType` and `description` in JSON, with `agentId` derived from transcript path.
- **Defect 3:** Add tests for entries without `uuid` field. Verify they parse successfully with `None` uuid.
- **Integration:** If real JSONL fixtures are available, add a smoke test that parses a representative sample and verifies non-zero counts for user entries and sub-agents.
