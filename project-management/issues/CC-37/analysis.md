# Technical Analysis: Add sub-agent discovery: parse agentId, discover subagents/ directory, read .meta.json

**Issue:** CC-37
**Created:** 2026-04-09
**Status:** Draft

## Problem Statement

`claude-code-query` currently models each conversation session as a flat, independent entity. When Claude Code spawns sub-agents via its `Agent` tool, the sub-agent transcripts live in a `subagents/` subdirectory alongside the parent session file, but the SDK has no way to discover or enumerate them. This blocks downstream tools (like `transcript-analyze` in kanon) from reconstructing the full development timeline -- including implementation agents, code reviewers, and other delegated sub-agents.

The user impact is that any analysis built on `claude-code-query` sees only the top-level session and misses potentially significant portions of the conversation.

## Proposed Solution

### High-Level Approach

Add an `agentId` field to `ConversationLogEntry` so the parser captures the self-identification present in sub-agent JSONL lines. Introduce a new `SubAgentMetadata` model in core that represents the information found in `.meta.json` files. Then extend `ConversationLogIndex` (or add a companion method on the concrete implementations) with a `listSubAgents(projectPath, sessionId)` operation that discovers `<sessionId>/subagents/agent-*.jsonl` files and reads their corresponding `.meta.json` sidecar files.

The existing `ConversationLogReader` and `ConversationLogParser` can already read sub-agent JSONL files without modification (they are the same format as parent sessions). The only parser change is adding `agentId` to the envelope extraction.

### Why This Approach

This is the minimal extension path. The on-disk structure is well-defined and stable. Adding `agentId` to the envelope is a backward-compatible case class change (new `Option[String]` field with default `None`). The discovery logic mirrors the existing `listSessions` pattern but scoped to a subdirectory, keeping the code consistent. Placing `SubAgentMetadata` in core and the discovery logic in the concrete index implementations follows the established module boundary pattern exactly.

## Architecture Design

### Domain Layer (core model + parsing)

**Components:**
- `ConversationLogEntry` -- add `agentId: Option[String]` field
- `SubAgentMetadata` -- new case class with `agentId: String`, `agentType: Option[String]`, `description: Option[String]`, `path: os.Path` (to the JSONL file)
- `ConversationLogParser` -- extract `agentId` from envelope JSON
- `SubAgentMetadataParser` -- pure function to parse `.meta.json` content into `SubAgentMetadata`

**Responsibilities:**
- `agentId` is extracted from the JSONL envelope alongside existing fields like `isSidechain`
- `SubAgentMetadata` represents the on-disk `.meta.json` content plus the path to the sub-agent transcript
- Parser handles missing/malformed `.meta.json` gracefully (fields are optional)

**Estimated Effort:** 2-3 hours
**Complexity:** Straightforward

---

### Application Layer (ConversationLogIndex trait)

**Components:**
- `ConversationLogIndex[F[_]]` -- add `listSubAgents(projectPath: os.Path, sessionId: String): F[Seq[SubAgentMetadata]]`

**Responsibilities:**
- Defines the abstract contract for sub-agent discovery
- Callers can discover sub-agents for any session without knowing the filesystem layout

**Estimated Effort:** 0.5-1 hour
**Complexity:** Straightforward

---

### Infrastructure Layer (direct + effectful implementations)

**Components:**
- `DirectConversationLogIndex` -- implement `listSubAgents` using `os.list` on `<projectPath>/<sessionId>/subagents/`
- `EffectfulConversationLogIndex` -- implement `listSubAgents` using `Files[IO].list` on the same path
- Both implementations: read `.meta.json` sidecar files alongside discovered `.jsonl` files

**Responsibilities:**
- Filesystem traversal: enumerate `agent-*.jsonl` files in the `subagents/` directory
- Parse `.meta.json` sidecars using `SubAgentMetadataParser` from core
- Handle missing directories gracefully (return empty sequence)
- Handle missing or malformed `.meta.json` (populate with `None` fields)

**Estimated Effort:** 2-4 hours
**Complexity:** Moderate (two parallel implementations, graceful error handling for missing meta files)

---

### Presentation Layer

Not applicable. This is a library-only change with no API endpoints or UI.

---

## Technical Decisions

### Patterns

- Follow the existing `ConversationLogIndex` trait + `Direct`/`Effectful` implementation pattern exactly
- Pure parsing in core, I/O in module implementations
- `SubAgentMetadataParser` as a pure `Json => Option[SubAgentMetadata]` function, consistent with `ConversationLogParser`

### Technology Choices

- **JSON parsing**: circe (already in core dependencies)
- **Filesystem**: os-lib in direct module, fs2 Files in effectful module (existing pattern)
- **No new dependencies required**

### Integration Points

- `ConversationLogParser` envelope extraction gains `agentId` -- this is backward compatible since `EnvelopeKeys` set is internal
- `ConversationLogIndex` trait gains a new method -- this is a breaking change for any external implementors of the trait (but none exist outside this project)
- Downstream consumer (kanon) will use `listSubAgents` + existing `ConversationLogReader.readAll` to read sub-agent transcripts

## Technical Risks & Uncertainties

### RESOLVED: Trait evolution strategy for ConversationLogIndex

**Decision:** Add `listSubAgents` directly to `ConversationLogIndex[F[_]]`. The library is pre-1.0 (0.3.0-SNAPSHOT), the trait is internal to this project, and there are no known external implementors. A separate trait would be over-engineering at this stage.

---

### RESOLVED: SubAgentMetadata content scope

**Decision:** `SubAgentMetadata` contains only `.meta.json` fields (`agentType`, `description`) + the transcript path. Parent-side data from `toolUseResult` (duration, tokens, prompt) is not duplicated — consumers already have the parent transcript loaded and can access that data directly. Parsing `toolUseResult` is deferred as a separate concern.

---

## Total Estimates

**Per-Layer Breakdown:**
- Domain Layer (core model + parsing): 2-3 hours
- Application Layer (trait extension): 0.5-1 hour
- Infrastructure Layer (direct + effectful impls): 2-4 hours
- Presentation Layer: 0 hours

**Total Range:** 4.5 - 8 hours

**Confidence:** High

**Reasoning:**
- The on-disk format is well-documented in the issue
- The existing codebase has clear patterns to follow for every layer
- No new dependencies or architectural changes needed
- The two parallel implementations (direct/effectful) are mechanical but double the infrastructure work

## Testing Strategy

### Per-Layer Testing

**Domain Layer:**
- Unit: `ConversationLogParser` extracts `agentId` from JSONL lines that contain it
- Unit: `ConversationLogParser` returns `None` for `agentId` when absent (backward compat)
- Unit: `SubAgentMetadataParser` parses valid `.meta.json` content
- Unit: `SubAgentMetadataParser` handles missing/partial fields gracefully

**Application Layer:**
- Unit: Verify trait compiles with new method signature (covered by implementation tests)

**Infrastructure Layer:**
- Unit: `DirectConversationLogIndex.listSubAgents` discovers sub-agent files in temp directory
- Unit: `DirectConversationLogIndex.listSubAgents` returns empty for missing `subagents/` directory
- Unit: `DirectConversationLogIndex.listSubAgents` reads `.meta.json` sidecar
- Unit: `DirectConversationLogIndex.listSubAgents` handles missing `.meta.json`
- Unit: Same test suite mirrored for `EffectfulConversationLogIndex`

**Test Data Strategy:**
- Temp directories with synthetic `.jsonl` and `.meta.json` files, following the pattern already established in `DirectConversationLogIndexTest`
- JSON string literals for parser unit tests, following the pattern in `ConversationLogParserTest`

**Regression Coverage:**
- All existing parser tests must continue passing (agentId is additive)
- All existing index tests must continue passing (new method, no changes to existing methods)

## Deployment Considerations

### Database Changes
None. This is a pure library change with no persistence layer.

### Configuration Changes
None.

### Rollout Strategy
Publish a new version of all three artifacts (core, direct, effectful). Kanon updates its dependency version.

### Rollback Plan
Kanon pins back to the previous version. The new fields/methods are additive and don't affect existing functionality.

## Dependencies

### Prerequisites
- None. The existing codebase has everything needed.

### Layer Dependencies
- Domain layer (core model + parsing) must be completed first -- both infrastructure implementations depend on the new types
- Application layer (trait change) and domain layer can be done together since they are both in core
- Infrastructure layer (direct + effectful) can be parallelized once core is done

### External Blockers
- None.

## Risks & Mitigations

### Risk 1: .meta.json format is not stable
**Likelihood:** Low
**Impact:** Low
**Mitigation:** All fields in `SubAgentMetadata` are `Option` types. The parser treats the entire `.meta.json` as optional. Unknown fields are ignored.

### Risk 2: Sub-agent directory naming convention changes
**Likelihood:** Low
**Impact:** Medium
**Mitigation:** The `subagents/` path and `agent-<id>.jsonl` naming are observed conventions from the Claude Code CLI. If they change, only the discovery logic in the two index implementations needs updating.

---

## Implementation Sequence

**Recommended Layer Order:**

1. **Domain Layer (core)** - Add `agentId` to `ConversationLogEntry`, create `SubAgentMetadata`, update parser, add `SubAgentMetadataParser`. Pure logic, no dependencies, foundation for discovery.
2. **Application Layer (core trait)** - Add `listSubAgents` to `ConversationLogIndex`. Minimal change, enables implementations.
3. **Infrastructure Layer (direct)** - Implement `listSubAgents` in `DirectConversationLogIndex`. Easier to test synchronously, validates the approach.
4. **Infrastructure Layer (effectful)** - Mirror the direct implementation in `EffectfulConversationLogIndex`. Mechanical translation to IO.

**Ordering Rationale:**
- Core changes are prerequisites for everything else
- Direct implementation is simpler to debug and validate before the effectful mirror
- Steps 3 and 4 could be parallelized by two developers but are sequential for a single implementor

## Documentation Requirements

- [ ] Code documentation (PURPOSE comments on new files, scaladoc on new public methods)
- [ ] Architecture decision record -- update ARCHITECTURE.md with sub-agent discovery section
- [ ] User-facing documentation -- update README if sub-agent discovery is a headline feature
- [ ] Migration guide -- not needed, additive changes only

---

**Analysis Status:** Ready for Review
