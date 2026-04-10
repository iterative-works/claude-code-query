# Phase 02 Tasks: Derive agentId from filename in SubAgentMetadataParser

**Issue:** CC-41
**Phase:** 02 — Derive agentId from filename in SubAgentMetadataParser

## Tasks

- [ ] [impl] [ ] [reviewed] Write failing test reproducing the defect (parse returns None for real-world .meta.json content without agentId)
- [ ] [impl] [ ] [reviewed] Investigate root cause — confirm agentId is never in real .meta.json and verify transcriptPath filename pattern from callers
- [ ] [impl] [ ] [reviewed] Implement fix — derive agentId from transcriptPath filename instead of requiring it in JSON
- [ ] [impl] [ ] [reviewed] Update existing tests to use realistic fixtures (no agentId in JSON, agent-<id>.jsonl paths)
- [ ] [impl] [ ] [reviewed] Add new tests: agentId derived from filename, agentId in JSON ignored, empty JSON object with valid path
- [ ] [impl] [ ] [reviewed] Verify fix passes and no regressions (./mill core.compile, ./mill core.test)
