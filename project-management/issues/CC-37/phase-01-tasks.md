# Phase 1 Tasks: Domain model and parsing

## Setup

- [ ] [setup] Verify existing tests pass (`./mill core.test`)

## Tests First (TDD)

- [ ] [test] Add parser test: JSONL line with `agentId` field extracts `Some("agent-xxx")`
- [ ] [test] Add parser test: JSONL line without `agentId` field extracts `None`
- [ ] [test] Add parser test: `agentId` excluded from data maps in system/progress entries
- [ ] [test] Add model test: `SubAgentMetadata` construction with all fields
- [ ] [test] Add model test: `SubAgentMetadata` construction with optional fields as `None`
- [ ] [test] Create `SubAgentMetadataParserTest`: valid JSON with all fields → `Some`
- [ ] [test] `SubAgentMetadataParserTest`: missing optional fields → `Some` with `None` fields
- [ ] [test] `SubAgentMetadataParserTest`: missing required `agentId` → `None`
- [ ] [test] `SubAgentMetadataParserTest`: malformed/empty JSON → `None`

## Implementation

- [ ] [impl] Add `agentId: Option[String] = None` to `ConversationLogEntry`
- [ ] [impl] Create `SubAgentMetadata` case class in `core/src/.../log/model/`
- [ ] [impl] Update `ConversationLogParser` to extract `agentId` from envelope, add to `EnvelopeKeys`
- [ ] [impl] Create `SubAgentMetadataParser` object with `parse(json: Json, transcriptPath: os.Path): Option[SubAgentMetadata]`

## Verification

- [ ] [verify] All new tests pass (`./mill core.test`)
- [ ] [verify] No compilation warnings
- [ ] [verify] All existing tests in all modules still pass (`./mill __.test`)
