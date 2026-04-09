# Phase 1 Tasks: Domain model and parsing

## Setup

- [x] [setup] Verify existing tests pass (`./mill core.test`)

## Tests First (TDD)

- [x] [test] Add parser test: JSONL line with `agentId` field extracts `Some("agent-xxx")`
- [x] [test] Add parser test: JSONL line without `agentId` field extracts `None`
- [x] [test] Add parser test: `agentId` excluded from data maps in system/progress entries
- [x] [test] Add model test: `SubAgentMetadata` construction with all fields
- [x] [test] Add model test: `SubAgentMetadata` construction with optional fields as `None`
- [x] [test] Create `SubAgentMetadataParserTest`: valid JSON with all fields → `Some`
- [x] [test] `SubAgentMetadataParserTest`: missing optional fields → `Some` with `None` fields
- [x] [test] `SubAgentMetadataParserTest`: missing required `agentId` → `None`
- [x] [test] `SubAgentMetadataParserTest`: malformed/empty JSON → `None`

## Implementation

- [x] [impl] Add `agentId: Option[String] = None` to `ConversationLogEntry`
- [x] [impl] Create `SubAgentMetadata` case class in `core/src/.../log/model/`
- [x] [impl] Update `ConversationLogParser` to extract `agentId` from envelope, add to `EnvelopeKeys`
- [x] [impl] Create `SubAgentMetadataParser` object with `parse(json: Json, transcriptPath: os.Path): Option[SubAgentMetadata]`

## Verification

- [x] [verify] All new tests pass (`./mill core.test`)
- [x] [verify] No compilation warnings
- [x] [verify] All existing tests in all modules still pass (`./mill __.test`)
**Phase Status:** Complete
