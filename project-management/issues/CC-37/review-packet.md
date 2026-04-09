---
generated_from: 4dca815800babffa8ec3ad985e7c231099241441
generated_at: 2026-04-09T09:30:16Z
branch: CC-37-phase-02
issue_id: CC-37
phase: "1-2"
files_analyzed:
  - core/src/works/iterative/claude/core/log/ConversationLogIndex.scala
  - core/src/works/iterative/claude/core/log/model/ConversationLogEntry.scala
  - core/src/works/iterative/claude/core/log/model/SubAgentMetadata.scala
  - core/src/works/iterative/claude/core/log/parsing/ConversationLogParser.scala
  - core/src/works/iterative/claude/core/log/parsing/SubAgentMetadataParser.scala
  - core/test/src/works/iterative/claude/core/log/ServiceTraitTest.scala
  - core/test/src/works/iterative/claude/core/log/parsing/ConversationLogParserTest.scala
  - core/test/src/works/iterative/claude/core/log/parsing/SubAgentMetadataParserTest.scala
  - direct/src/works/iterative/claude/direct/log/DirectConversationLogIndex.scala
  - direct/test/src/works/iterative/claude/direct/log/DirectConversationLogIndexTest.scala
  - effectful/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndex.scala
  - effectful/test/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndexTest.scala
---

# Review Packet: CC-37 — Sub-Agent Discovery

## Goals

This feature adds sub-agent awareness to the Scala Claude Code SDK, enabling callers to discover and enumerate sub-agent transcripts spawned by the Claude Code `Agent` tool.

Key objectives:

- Add `agentId: Option[String]` to `ConversationLogEntry` so the parser captures the self-identification present in sub-agent JSONL lines.
- Introduce `SubAgentMetadata` as a domain model representing the content of `.meta.json` sidecar files alongside the path to the sub-agent's JSONL transcript.
- Provide a pure `SubAgentMetadataParser` that converts `.meta.json` JSON into `SubAgentMetadata`.
- Extend `ConversationLogIndex[F[_]]` with `listSubAgents(projectPath, sessionId)` and implement it in both `DirectConversationLogIndex` and `EffectfulConversationLogIndex`.
- Handle all edge cases gracefully: missing `subagents/` directory, missing or malformed `.meta.json` sidecar files.

The primary downstream consumer is `transcript-analyze` in kanon, which needs to reconstruct the full development timeline including implementation agents, code reviewers, and other delegated sub-agents.

## Scenarios

- [x] `ConversationLogParser` extracts `agentId` from JSONL envelope lines that contain it
- [x] `ConversationLogParser` returns `None` for `agentId` when the field is absent (backward compatibility)
- [x] `agentId` is excluded from the data maps in `system` and `progress` entries
- [x] `SubAgentMetadataParser` parses a valid `.meta.json` with all fields into `SubAgentMetadata`
- [x] `SubAgentMetadataParser` returns `Some` with `None` optional fields when only `agentId` is present
- [x] `SubAgentMetadataParser` returns `None` when the required `agentId` field is missing
- [x] `SubAgentMetadataParser` returns `None` for empty JSON objects and `null`
- [x] `listSubAgents` returns an empty `Seq` when the `subagents/` directory does not exist
- [x] `listSubAgents` returns an empty `Seq` when the `subagents/` directory is empty
- [x] `listSubAgents` discovers a sub-agent with a valid `.meta.json` sidecar
- [x] `listSubAgents` populates all metadata fields (`agentId`, `agentType`, `description`, `transcriptPath`)
- [x] `listSubAgents` sets `transcriptPath` to the `.jsonl` file path
- [x] `listSubAgents` skips a sub-agent when `.meta.json` is missing
- [x] `listSubAgents` skips a sub-agent when `.meta.json` is malformed
- [x] `listSubAgents` discovers multiple sub-agents in the same directory
- [x] `listSubAgents` ignores non-`agent-*.jsonl` files in the `subagents/` directory
- [x] Convenience `listSubAgentsFor(cwd, sessionId)` resolves project dir from cwd (both direct and effectful)

## Entry Points

| File | Method/Class | Why Start Here |
|------|--------------|----------------|
| `core/src/works/iterative/claude/core/log/ConversationLogIndex.scala` | `ConversationLogIndex[F[_]]` | Trait contract — shows the complete public API including the new `listSubAgents` method |
| `core/src/works/iterative/claude/core/log/model/SubAgentMetadata.scala` | `SubAgentMetadata` | New domain model — the shape of data returned by discovery |
| `core/src/works/iterative/claude/core/log/parsing/SubAgentMetadataParser.scala` | `SubAgentMetadataParser.parse` | Pure parser for `.meta.json` sidecars; entry point for understanding metadata extraction |
| `core/src/works/iterative/claude/core/log/parsing/ConversationLogParser.scala` | `parseLogEntry` | Shows where `agentId` is extracted from the JSONL envelope |
| `direct/src/works/iterative/claude/direct/log/DirectConversationLogIndex.scala` | `listSubAgents` / `listSubAgentsFor` | Synchronous implementation — easier to follow than the effectful version |
| `effectful/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndex.scala` | `listSubAgents` / `listSubAgentsFor` | IO-wrapped mirror of the direct implementation using fs2 streams |

## Diagrams

### Module Boundaries

```
┌─────────────────────────────────────────────────────┐
│  core                                                │
│                                                      │
│  model:                                              │
│    ConversationLogEntry  (+ agentId: Option[String]) │
│    SubAgentMetadata      (new)                       │
│                                                      │
│  parsing:                                            │
│    ConversationLogParser (+ agentId extraction)      │
│    SubAgentMetadataParser (new)                      │
│                                                      │
│  log:                                                │
│    ConversationLogIndex[F[_]]                        │
│      listSessions(projectPath)                       │
│      forSession(projectPath, sessionId)              │
│      listSubAgents(projectPath, sessionId)  (new)    │
└───────────┬─────────────────────────────────────────┘
            │ depends on core
    ┌───────┴───────┐
    │               │
┌───▼───┐     ┌─────▼──────┐
│direct │     │ effectful  │
│       │     │            │
│ Direct│     │ Effectful  │
│ Conver│     │ Conversa   │
│ sation│     │ tionLog    │
│ LogIn │     │ Index[IO]  │
│ dex   │     │            │
│ [Id]  │     │            │
└───────┘     └────────────┘
```

### On-Disk Layout for Sub-Agent Discovery

```
<projectPath>/
  <sessionId>.jsonl              # parent session transcript
  <sessionId>/
    subagents/
      agent-<id>.jsonl           # sub-agent transcript (same format as parent)
      agent-<id>.meta.json       # sidecar: { agentId, agentType?, description? }
      agent-<id2>.jsonl
      agent-<id2>.meta.json
```

### listSubAgents Flow

```
listSubAgents(projectPath, sessionId)
  │
  ├─ compute subagentsDir = projectPath / sessionId / "subagents"
  │
  ├─ [dir missing or not a dir?] → return Seq.empty
  │
  └─ list files matching agent-*.jsonl
       │
       └─ for each .jsonl:
            │
            ├─ look for <basename>.meta.json
            │
            ├─ [meta missing] → skip (agentId required)
            │
            ├─ [meta malformed JSON] → skip
            │
            ├─ [meta missing agentId] → skip (SubAgentMetadataParser returns None)
            │
            └─ [valid] → SubAgentMetadata(agentId, agentType?, description?, transcriptPath)
```

## Test Summary

All 397 tests pass (`./mill __.test`). 30 new tests were added across this feature.

### Phase 1: Domain model and parsing (12 new tests)

| File | Type | Tests |
|------|------|-------|
| `core/test/src/.../log/parsing/SubAgentMetadataParserTest.scala` | Unit | 6 — valid JSON all fields, only agentId, missing agentId, empty object, null, transcriptPath stored |
| `core/test/src/.../log/parsing/ConversationLogParserTest.scala` | Unit | 4 added — agentId extraction present, agentId absent, excluded from system data map, excluded from progress data map |
| `core/test/src/.../log/model/LogModelTest.scala` | Unit | — (existing, regression) |

### Phase 2: Trait extension and implementations (18 new tests)

| File | Type | Tests |
|------|------|-------|
| `core/test/src/.../log/ServiceTraitTest.scala` | Compilation | 2 — `ConversationLogIndex` compiles with identity F and with IO (including `listSubAgents`) |
| `direct/test/src/.../log/DirectConversationLogIndexTest.scala` | Integration | 9 — missing dir, empty dir, single agent, all fields, transcriptPath, missing meta, malformed meta, multiple agents, ignores non-agent files |
| `effectful/test/src/.../log/EffectfulConversationLogIndexTest.scala` | Integration | 9 — mirrors the direct suite wrapped in IO assertions |

### Regression Coverage

All pre-existing tests in the following suites pass without modification:

- `ConversationLogParserTest` (pre-existing cases)
- `LogModelTest`
- `DirectConversationLogIndexTest` (listSessions / forSession cases)
- `DirectConversationLogIndexCwdTest`
- `EffectfulConversationLogIndexTest` (listSessions / forSession cases)
- `EffectfulConversationLogIndexCwdTest`
- `SubAgentMetadataParserTest`

## Files Changed

### New Files

| File | Purpose |
|------|---------|
| `core/src/works/iterative/claude/core/log/model/SubAgentMetadata.scala` | Domain model for sub-agent metadata from `.meta.json` sidecars |
| `core/src/works/iterative/claude/core/log/parsing/SubAgentMetadataParser.scala` | Pure parser: `Json + os.Path => Option[SubAgentMetadata]` |
| `core/test/src/works/iterative/claude/core/log/parsing/SubAgentMetadataParserTest.scala` | Unit tests for `SubAgentMetadataParser` |

### Modified Files

| File | Change Summary |
|------|----------------|
| `core/src/works/iterative/claude/core/log/model/ConversationLogEntry.scala` | Added `agentId: Option[String] = None` field (backward-compatible default) |
| `core/src/works/iterative/claude/core/log/parsing/ConversationLogParser.scala` | Added `"agentId"` to `EnvelopeKeys` set; extracts `agentId` in `parseLogEntry` |
| `core/src/works/iterative/claude/core/log/ConversationLogIndex.scala` | Added `listSubAgents(projectPath, sessionId): F[Seq[SubAgentMetadata]]` abstract method and `SubAgentMetadata` import |
| `direct/src/works/iterative/claude/direct/log/DirectConversationLogIndex.scala` | Implemented `listSubAgents` and `listSubAgentsFor`; filesystem traversal via os-lib |
| `effectful/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndex.scala` | Implemented `listSubAgents` and `listSubAgentsFor`; traversal via fs2 `Files[IO].list` |
| `core/test/src/works/iterative/claude/core/log/ServiceTraitTest.scala` | Added compilation tests for `listSubAgents` on both effect types |
| `core/test/src/works/iterative/claude/core/log/parsing/ConversationLogParserTest.scala` | Added 4 tests for `agentId` extraction and envelope exclusion |
| `direct/test/src/works/iterative/claude/direct/log/DirectConversationLogIndexTest.scala` | Added 9 `listSubAgents` integration tests |
| `effectful/test/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndexTest.scala` | Added 9 `listSubAgents` integration tests (IO-wrapped) |

<details>
<summary>Project management files changed</summary>

- `project-management/issues/CC-37/analysis.md`
- `project-management/issues/CC-37/implementation-log.md`
- `project-management/issues/CC-37/phase-01-context.md`
- `project-management/issues/CC-37/phase-01-tasks.md`
- `project-management/issues/CC-37/phase-02-context.md`
- `project-management/issues/CC-37/phase-02-tasks.md`
- `project-management/issues/CC-37/review-phase-01-20260409-104722.md`
- `project-management/issues/CC-37/review-phase-02-20260409-110558.md`
- `project-management/issues/CC-37/review-state.json`
- `project-management/issues/CC-37/tasks.md`

</details>
