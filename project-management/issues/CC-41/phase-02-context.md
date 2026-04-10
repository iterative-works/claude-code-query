# Phase 02: Derive agentId from filename in SubAgentMetadataParser

## Defect Description

`SubAgentMetadataParser.parse` requires `agentId` from the JSON payload:
```scala
for agentId <- cursor.get[String]("agentId").toOption
```

But actual `.meta.json` files produced by Claude Code CLI contain only `agentType` and `description`:
```json
{"agentType":"iterative-works:code-reviewer","description":"Review security of phase 07"}
```

The agent ID is encoded in the filename (e.g., `agent-a27a237ab9050d9ef.meta.json`), not in the JSON content.

**Impact:** `parse` returns `None` for every real sub-agent metadata file, producing empty sub-agent lists. This means no role classification or attribution in transcript analysis.

## Reproduction Steps

1. Create a `.meta.json` file with real-world content (no `agentId` field):
   ```json
   {"agentType":"iterative-works:code-reviewer","description":"Review security of phase 07"}
   ```
2. Create a corresponding transcript path: `/tmp/subagents/agent-a27a237ab9050d9ef.jsonl`
3. Call `SubAgentMetadataParser.parse(json, transcriptPath)`
4. Observe result is `None` — the for-comprehension fails because `agentId` is not in JSON

## Root Cause Hypotheses

**H1 (Confidence: Very High):** The parser was written assuming `agentId` would be present in the JSON payload, but the actual Claude Code CLI `.meta.json` format does not include it. The agent ID is only available from the filename of the transcript (and meta) files, which already follows the pattern `agent-<id>.jsonl`. The `transcriptPath` parameter already carries this information.

Supporting evidence:
- Real `.meta.json` files contain only `agentType` and `description`
- The filename pattern `agent-<id>.jsonl` is enforced by both callers (`DirectConversationLogIndex` and `EffectfulConversationLogIndex`) via `startsWith("agent-")` and `endsWith(".jsonl")` filters
- The existing test that expects `None` for JSON missing `agentId` actually represents the real-world scenario (test titled "JSON missing required agentId returns None")

## Investigation Plan

1. Confirm `agentId` is never present in real `.meta.json` files
2. Verify that the `transcriptPath` parameter always follows the `agent-<id>.jsonl` naming pattern (check both callers)
3. Determine the correct extraction logic: strip `.jsonl` suffix from filename to get `agent-<id>`
4. Review `SubAgentMetadata` model to confirm `agentId` field type and usage downstream

## Fix Strategy

1. **Derive `agentId` from `transcriptPath` filename** instead of requiring it in JSON:
   - Extract from `transcriptPath.last.stripSuffix(".jsonl")` to get e.g. `agent-a27a237ab9050d9ef`
   - The `agentId` is always available since callers filter for `agent-*.jsonl` files

2. **Remove `agentId` from the for-comprehension guard** — the parser should always succeed when given valid JSON (even empty `{}`), since `agentType` and `description` are already optional

3. **Decide on parse behavior for edge cases:**
   - Empty JSON object `{}`: should now succeed (agentId from filename, optional fields as None)
   - JSON null: should still return None (cannot extract cursor)
   - Consider whether to validate the filename pattern or trust the caller

4. **Update tests** to reflect that:
   - `agentId` comes from the transcript path filename, not JSON
   - JSON without `agentId` is the normal case (not an error)
   - Test fixtures should use realistic `.meta.json` content
   - The transcript path in test fixtures should use realistic filenames like `agent-abc123.jsonl`

## Files to Modify

- `core/src/works/iterative/claude/core/log/parsing/SubAgentMetadataParser.scala` — derive `agentId` from `transcriptPath.last.stripSuffix(".jsonl")` instead of reading from JSON
- `core/test/src/works/iterative/claude/core/log/parsing/SubAgentMetadataParserTest.scala` — update test fixtures and expectations:
  - Use realistic transcript paths (`agent-<id>.jsonl`)
  - Use realistic JSON content (no `agentId` field)
  - Fix expectations for what returns `Some` vs `None`

## What Was Fixed in Phase 01

Phase 01 fixed entry type name mismatches in `ConversationLogParser.parsePayload`:
- Corrected 3 type name strings (`"human"` → `"user"`, underscores → hyphens)
- Added `PermissionModeLogEntry` and `AttachmentLogEntry` model types and match cases
- Updated all test fixtures to use correct type names
- All tests pass after fixes

## Testing Requirements

**Existing tests to update:**
- `"valid JSON with all fields returns Some(SubAgentMetadata)"` — remove `agentId` from JSON fixture; use a realistic transcript path like `agent-abc123.jsonl`; assert `agentId` equals `"agent-abc123"` (derived from path)
- `"JSON with only required agentId returns Some with None optional fields"` — rename and update: JSON should be `{}` or contain only non-id fields; `agentId` still derived from path
- `"JSON missing required agentId returns None"` — this should now return `Some` since agentId comes from filename; update test accordingly
- `"empty JSON object returns None"` — should now return `Some` with agentId from filename and None optional fields
- `"JSON null returns None"` — should still return None (or reconsider based on implementation)
- `"transcriptPath is stored in parsed SubAgentMetadata"` — update to use realistic path; remove `agentId` from JSON

**New tests to add:**
- Verify `agentId` is derived from transcript filename, not from JSON
- Verify `agentId` in JSON is ignored (if present) in favor of filename-derived value
- Verify realistic `.meta.json` content (only `agentType` and `description`) parses successfully

## Acceptance Criteria

1. `SubAgentMetadataParser.parse` returns `Some(SubAgentMetadata)` for real-world `.meta.json` content that lacks `agentId`
2. `agentId` is derived from the transcript path filename (e.g., `agent-a27a237ab9050d9ef.jsonl` → `"agent-a27a237ab9050d9ef"`)
3. `agentType` and `description` are still parsed from JSON as optional fields
4. JSON without any fields (empty object) still produces a valid `SubAgentMetadata` with agentId from filename
5. All updated and new tests pass
6. `./mill core.compile` succeeds with no warnings
7. `./mill core.test` passes all tests
8. No changes to `SubAgentMetadata` model class (field types remain the same)
9. No changes to caller code in `DirectConversationLogIndex` or `EffectfulConversationLogIndex`
