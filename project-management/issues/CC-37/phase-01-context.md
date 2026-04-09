# Phase 1: Domain model and parsing

## Goals

Add sub-agent awareness to the core domain model and parsing layer. After this phase, the parser can extract `agentId` from JSONL envelope lines, and a new `SubAgentMetadata` model captures `.meta.json` sidecar content. A pure `SubAgentMetadataParser` converts JSON into the domain type.

## Scope

### In Scope

- Add `agentId: Option[String]` field to `ConversationLogEntry`
- Create `SubAgentMetadata` case class in core log model
- Update `ConversationLogParser` to extract `agentId` from envelope JSON
- Create `SubAgentMetadataParser` as a pure `Json => Option[SubAgentMetadata]` function
- Unit tests for all new/changed parsing and model code

### Out of Scope

- `ConversationLogIndex` trait changes (Phase 2)
- Direct/Effectful implementation of sub-agent discovery (Phase 2)
- Filesystem traversal of `subagents/` directories (Phase 2)

## Dependencies

- No dependencies on other phases — this is the foundation phase.

## Approach

### 1. ConversationLogEntry — add agentId

Add `agentId: Option[String] = None` to the case class. This is backward-compatible since the default is `None`.

**File:** `core/src/works/iterative/claude/core/log/model/ConversationLogEntry.scala`

### 2. SubAgentMetadata — new model

Create a case class representing the content of a `.meta.json` sidecar file plus the path to the sub-agent's JSONL transcript:

```scala
case class SubAgentMetadata(
    agentId: String,
    agentType: Option[String],
    description: Option[String],
    path: os.Path
)
```

**File:** `core/src/works/iterative/claude/core/log/model/SubAgentMetadata.scala`

### 3. ConversationLogParser — extract agentId

In `parseLogEntry`, extract `agentId` from the envelope JSON alongside existing fields (`uuid`, `parentUuid`, `timestamp`, etc.). Add `"agentId"` to the `EnvelopeKeys` set so it is excluded from data maps.

**File:** `core/src/works/iterative/claude/core/log/parsing/ConversationLogParser.scala`

### 4. SubAgentMetadataParser — new parser

A pure object with a `parse(json: Json, transcriptPath: os.Path): Option[SubAgentMetadata]` method. Extracts `agentId` (required — return `None` if missing), `agentType` and `description` (optional). The `transcriptPath` is passed in by the caller (the index implementation in Phase 2).

**File:** `core/src/works/iterative/claude/core/log/parsing/SubAgentMetadataParser.scala`

## Files to Modify

| File | Change |
|------|--------|
| `core/src/.../log/model/ConversationLogEntry.scala` | Add `agentId: Option[String] = None` |
| `core/src/.../log/model/SubAgentMetadata.scala` | **New file** — case class |
| `core/src/.../log/parsing/ConversationLogParser.scala` | Extract `agentId`, add to EnvelopeKeys |
| `core/src/.../log/parsing/SubAgentMetadataParser.scala` | **New file** — pure JSON parser |

## Files to Create (Tests)

| File | Purpose |
|------|---------|
| `core/test/src/.../log/parsing/SubAgentMetadataParserTest.scala` | Unit tests for `.meta.json` parsing |

## Existing Tests to Verify

| File | Why |
|------|-----|
| `core/test/src/.../log/parsing/ConversationLogParserTest.scala` | Must still pass — `agentId` is additive |
| `core/test/src/.../log/model/LogModelTest.scala` | Must still pass — new field has default |

## Testing Strategy

### New Tests

1. **ConversationLogParser — agentId extraction**
   - JSONL line with `agentId` field → entry has `Some("agent-xxx")`
   - JSONL line without `agentId` → entry has `None` (backward compat)
   - `agentId` is excluded from data maps in system/progress entries

2. **SubAgentMetadataParser**
   - Valid `.meta.json` with all fields → `Some(SubAgentMetadata(...))`
   - Missing optional fields (`agentType`, `description`) → `Some` with `None` fields
   - Missing required `agentId` → `None`
   - Malformed JSON → `None`
   - Empty JSON object → `None`

3. **SubAgentMetadata model**
   - Construction with all fields
   - Construction with optional fields as `None`

### Regression

- All existing `ConversationLogParserTest` tests pass unchanged
- All existing `LogModelTest` tests pass unchanged

## Acceptance Criteria

- [ ] `ConversationLogEntry` has `agentId: Option[String]` field with default `None`
- [ ] `SubAgentMetadata` case class exists with `agentId`, `agentType`, `description`, `path`
- [ ] `ConversationLogParser.parseLogEntry` extracts `agentId` from envelope
- [ ] `SubAgentMetadataParser.parse` converts JSON to `SubAgentMetadata`
- [ ] All new code has unit tests
- [ ] All existing tests pass without modification
- [ ] `./mill core.test` passes
