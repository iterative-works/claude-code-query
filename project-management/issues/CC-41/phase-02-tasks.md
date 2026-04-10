# Phase 02 Tasks: Derive agentId from filename in SubAgentMetadataParser

**Issue:** CC-41
**Phase:** 02 — Derive agentId from filename in SubAgentMetadataParser

## Tasks

- [x] [impl] [x] [reviewed] Write failing test reproducing the defect (parse returns None for real-world .meta.json content without agentId)
- [x] [impl] [x] [reviewed] Investigate root cause — confirm agentId is never in real .meta.json and verify transcriptPath filename pattern from callers
- [x] [impl] [x] [reviewed] Implement fix — derive agentId from transcriptPath filename instead of requiring it in JSON
- [x] [impl] [x] [reviewed] Update existing tests to use realistic fixtures (no agentId in JSON, agent-<id>.jsonl paths)
- [x] [impl] [x] [reviewed] Add new tests: agentId derived from filename, agentId in JSON ignored, empty JSON object with valid path
- [x] [impl] [x] [reviewed] Verify fix passes and no regressions (./mill core.compile, ./mill core.test)
**Phase Status:** Complete
