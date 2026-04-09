# Phase 2: Trait extension and implementations

## Goals

Extend `ConversationLogIndex[F[_]]` with a `listSubAgents` method and implement it in both `DirectConversationLogIndex` and `EffectfulConversationLogIndex`. After this phase, callers can discover all sub-agent transcripts for a given session, including their `.meta.json` metadata, through the existing index API.

## Scope

### In Scope

- Add `listSubAgents(projectPath: os.Path, sessionId: String): F[Seq[SubAgentMetadata]]` to `ConversationLogIndex[F[_]]`
- Implement `listSubAgents` in `DirectConversationLogIndex` using `os.list` / `os.read`
- Implement `listSubAgents` in `EffectfulConversationLogIndex` using `Files[IO]` / `IO`
- Add convenience `listSubAgentsFor(cwd, sessionId)` methods that resolve project dir (matching existing `listSessionsFor` / `forSessionAt` pattern)
- Filesystem traversal: enumerate `agent-*.jsonl` files in `<projectPath>/<sessionId>/subagents/`
- Read and parse `.meta.json` sidecar files using `SubAgentMetadataParser`
- Graceful handling: missing `subagents/` directory returns empty sequence
- Graceful handling: missing or malformed `.meta.json` skips that sub-agent (or returns `SubAgentMetadata` with `None` optional fields, depending on whether `agentId` can be inferred from the filename)
- Unit/integration tests for both implementations

### Out of Scope

- Changes to `ConversationLogReader` or `ConversationLogParser` (Phase 1 complete)
- Reading sub-agent transcript content (callers use existing `ConversationLogReader.readAll` for that)
- Parent-side `toolUseResult` extraction (separate concern per analysis)
- Any UI or presentation layer changes

## Dependencies

- **Phase 1 artifacts (complete):**
  - `SubAgentMetadata` case class at `core/src/works/iterative/claude/core/log/model/SubAgentMetadata.scala`
  - `SubAgentMetadataParser.parse(json: Json, transcriptPath: os.Path): Option[SubAgentMetadata]` at `core/src/works/iterative/claude/core/log/parsing/SubAgentMetadataParser.scala`
  - `ConversationLogEntry.agentId: Option[String]` field

## Approach

### 1. ConversationLogIndex trait — add listSubAgents

Add the new abstract method to the trait. This is a breaking change for any implementors, but the trait is internal to this project (no external implementors exist).

**File:** `core/src/works/iterative/claude/core/log/ConversationLogIndex.scala`

**Signature to add:**
```scala
def listSubAgents(projectPath: os.Path, sessionId: String): F[Seq[SubAgentMetadata]]
```

**Import to add:**
```scala
import works.iterative.claude.core.log.model.SubAgentMetadata
```

### 2. DirectConversationLogIndex — implement listSubAgents

Implement the discovery logic using os-lib synchronous operations. The filesystem layout is:
```
<projectPath>/
  <sessionId>.jsonl          # parent session (already handled)
  <sessionId>/
    subagents/
      agent-<id>.jsonl       # sub-agent transcript
      agent-<id>.meta.json   # sub-agent metadata sidecar
```

**File:** `direct/src/works/iterative/claude/direct/log/DirectConversationLogIndex.scala`

**Implementation approach:**
1. Compute `subagentsDir = projectPath / sessionId / "subagents"`
2. If `subagentsDir` does not exist or is not a directory, return `Seq.empty`
3. List files matching `agent-*.jsonl` pattern
4. For each `.jsonl` file, look for a corresponding `.meta.json` sidecar (same base name, different extension)
5. If sidecar exists, parse it with `io.circe.parser.parse` then `SubAgentMetadataParser.parse`
6. If sidecar is missing or malformed, skip that sub-agent (since `agentId` is required and cannot be reliably extracted from filename alone)
7. Return the collected `Seq[SubAgentMetadata]`

**Also add convenience method:**
```scala
def listSubAgentsFor(cwd: os.Path, sessionId: String): Seq[SubAgentMetadata]
```
Following the pattern of `listSessionsFor` and `forSessionAt`.

### 3. EffectfulConversationLogIndex — implement listSubAgents

Mirror the direct implementation but wrapped in `IO` and using `Files[IO]` for directory listing.

**File:** `effectful/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndex.scala`

**Implementation approach:**
1. Same filesystem layout and logic as direct, but all operations wrapped in `IO`
2. Use `Files[IO].list` for directory enumeration
3. Use `IO(os.read(...))` or `Files[IO].readAll` for reading `.meta.json` content
4. Use `IO(io.circe.parser.parse(...))` for JSON parsing
5. Collect results via fs2 stream or `traverse`

**Also add convenience method:**
```scala
def listSubAgentsFor(cwd: os.Path, sessionId: String): IO[Seq[SubAgentMetadata]]
```

## Files to Modify

| File | Change |
|------|--------|
| `core/src/works/iterative/claude/core/log/ConversationLogIndex.scala` | Add `listSubAgents` method signature and `SubAgentMetadata` import |
| `direct/src/works/iterative/claude/direct/log/DirectConversationLogIndex.scala` | Implement `listSubAgents` and `listSubAgentsFor`; add imports for `SubAgentMetadata`, `SubAgentMetadataParser`, `io.circe.parser` |
| `effectful/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndex.scala` | Implement `listSubAgents` and `listSubAgentsFor`; add imports for `SubAgentMetadata`, `SubAgentMetadataParser`, `io.circe.parser` |

## Files to Create (Tests)

| File | Purpose |
|------|---------|
| (none -- tests are added to existing test files) | |

## Existing Tests to Verify

| File | Why |
|------|-----|
| `direct/test/src/works/iterative/claude/direct/log/DirectConversationLogIndexTest.scala` | Existing `listSessions`/`forSession` tests must still pass; new `listSubAgents` tests added here |
| `direct/test/src/works/iterative/claude/direct/log/DirectConversationLogIndexCwdTest.scala` | Existing cwd-resolution tests must still pass |
| `effectful/test/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndexTest.scala` | Existing tests must still pass; new `listSubAgents` tests added here |
| `effectful/test/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndexCwdTest.scala` | Existing cwd-resolution tests must still pass |
| `core/test/src/works/iterative/claude/core/log/parsing/SubAgentMetadataParserTest.scala` | Phase 1 parser tests must still pass |

## Testing Strategy

### New Tests

**DirectConversationLogIndexTest — add tests for listSubAgents:**

1. `listSubAgents returns empty Seq when subagents directory does not exist` — create a project dir with a session `.jsonl` but no `<sessionId>/subagents/` subdirectory
2. `listSubAgents returns empty Seq when subagents directory is empty` — create the directory structure but put no files in it
3. `listSubAgents discovers sub-agent with valid .meta.json` — create `agent-abc.jsonl` and `agent-abc.meta.json` with valid JSON containing `agentId`
4. `listSubAgents populates all metadata fields from .meta.json` — verify `agentId`, `agentType`, `description`, and `transcriptPath` are correctly populated
5. `listSubAgents skips sub-agent when .meta.json is missing` — create `agent-abc.jsonl` without a sidecar; verify it is not in results (since `agentId` is required)
6. `listSubAgents skips sub-agent when .meta.json is malformed` — create sidecar with invalid JSON; verify graceful handling
7. `listSubAgents discovers multiple sub-agents` — create several `agent-*.jsonl` + `.meta.json` pairs
8. `listSubAgents ignores non-agent files in subagents directory` — create a random `.txt` file alongside valid agent files
9. `listSubAgents sets transcriptPath to the .jsonl file path` — verify the `transcriptPath` field points to the actual `.jsonl` file

**EffectfulConversationLogIndexTest — mirror all above tests wrapped in IO assertions.**

Test data setup follows the existing pattern: create temp directories with synthetic `.jsonl` and `.meta.json` files using `os.temp.dir()`, `os.makeDir.all`, and `os.write`.

### Regression

- All existing tests in `DirectConversationLogIndexTest` pass unchanged
- All existing tests in `DirectConversationLogIndexCwdTest` pass unchanged
- All existing tests in `EffectfulConversationLogIndexTest` pass unchanged
- All existing tests in `EffectfulConversationLogIndexCwdTest` pass unchanged
- `./mill __.test` passes

## Acceptance Criteria

- [ ] `ConversationLogIndex[F[_]]` declares `listSubAgents(projectPath: os.Path, sessionId: String): F[Seq[SubAgentMetadata]]`
- [ ] `DirectConversationLogIndex.listSubAgents` discovers `agent-*.jsonl` files under `<projectPath>/<sessionId>/subagents/`
- [ ] `DirectConversationLogIndex.listSubAgents` parses `.meta.json` sidecars via `SubAgentMetadataParser`
- [ ] `DirectConversationLogIndex.listSubAgents` returns empty `Seq` for missing `subagents/` directory
- [ ] `DirectConversationLogIndex.listSubAgents` gracefully handles missing or malformed `.meta.json`
- [ ] `DirectConversationLogIndex.listSubAgentsFor` resolves project dir from cwd (matches `listSessionsFor` pattern)
- [ ] `EffectfulConversationLogIndex.listSubAgents` mirrors direct implementation in `IO`
- [ ] `EffectfulConversationLogIndex.listSubAgentsFor` resolves project dir from cwd
- [ ] All new code has comprehensive tests (both direct and effectful)
- [ ] All existing tests pass without modification
- [ ] `./mill __.test` passes
- [ ] `./mill __.compile` produces no warnings
