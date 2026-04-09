# Phase 2 Tasks: Trait extension and implementations

Issue: CC-37

## Setup

- [ ] [setup] Verify all existing tests pass with `./mill __.test` before making changes
- [ ] [setup] Verify `./mill __.compile` produces no warnings

## Tests

### DirectConversationLogIndex tests (`direct/test/src/works/iterative/claude/direct/log/DirectConversationLogIndexTest.scala`)

- [ ] [test] Add test: `listSubAgents returns empty Seq when subagents directory does not exist` — create project dir with session `.jsonl` but no `<sessionId>/subagents/` subdirectory
- [ ] [test] Add test: `listSubAgents returns empty Seq when subagents directory is empty` — create `<sessionId>/subagents/` with no files
- [ ] [test] Add test: `listSubAgents discovers sub-agent with valid .meta.json` — create `agent-abc.jsonl` and `agent-abc.meta.json` with valid JSON, verify single result with correct `agentId`
- [ ] [test] Add test: `listSubAgents populates all metadata fields from .meta.json` — verify `agentId`, `agentType`, `description`, and `transcriptPath` are all correctly populated
- [ ] [test] Add test: `listSubAgents sets transcriptPath to the .jsonl file path` — verify `transcriptPath` points to the actual `agent-*.jsonl` file
- [ ] [test] Add test: `listSubAgents skips sub-agent when .meta.json is missing` — create `agent-abc.jsonl` without sidecar, verify empty result
- [ ] [test] Add test: `listSubAgents skips sub-agent when .meta.json is malformed` — write invalid JSON to `.meta.json`, verify graceful handling (empty result)
- [ ] [test] Add test: `listSubAgents discovers multiple sub-agents` — create several `agent-*.jsonl` + `.meta.json` pairs, verify all found
- [ ] [test] Add test: `listSubAgents ignores non-agent files in subagents directory` — put a `.txt` file alongside valid agent files, verify only agents returned
- [ ] [test] Confirm all new Direct tests fail (no implementation yet)

### EffectfulConversationLogIndex tests (`effectful/test/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndexTest.scala`)

- [ ] [test] Mirror all 9 Direct test scenarios above as IO-based assertions using `CatsEffectSuite` pattern
- [ ] [test] Confirm all new Effectful tests fail (no implementation yet)

## Implementation

### Core trait (`core/src/works/iterative/claude/core/log/ConversationLogIndex.scala`)

- [ ] [impl] Add `import works.iterative.claude.core.log.model.SubAgentMetadata`
- [ ] [impl] Add abstract method `def listSubAgents(projectPath: os.Path, sessionId: String): F[Seq[SubAgentMetadata]]` to `ConversationLogIndex[F[_]]`

### DirectConversationLogIndex (`direct/src/works/iterative/claude/direct/log/DirectConversationLogIndex.scala`)

- [ ] [impl] Add imports for `SubAgentMetadata`, `SubAgentMetadataParser`, `io.circe.parser`
- [ ] [impl] Implement `listSubAgents(projectPath: os.Path, sessionId: String): Seq[SubAgentMetadata]` — compute `subagentsDir = projectPath / sessionId / "subagents"`, return `Seq.empty` if missing, list `agent-*.jsonl` files, read corresponding `.meta.json` sidecars, parse with `SubAgentMetadataParser.parse`, skip entries with missing/malformed sidecars
- [ ] [impl] Add convenience method `listSubAgentsFor(cwd: os.Path, sessionId: String): Seq[SubAgentMetadata]` — resolve project dir via `ClaudeProjects.projectDirFor` then delegate to `listSubAgents` (matches `listSessionsFor`/`forSessionAt` pattern)
- [ ] [impl] Verify Direct tests pass with `./mill direct.test`

### EffectfulConversationLogIndex (`effectful/src/works/iterative/claude/effectful/log/EffectfulConversationLogIndex.scala`)

- [ ] [impl] Add imports for `SubAgentMetadata`, `SubAgentMetadataParser`, `io.circe.parser`
- [ ] [impl] Implement `listSubAgents(projectPath: os.Path, sessionId: String): IO[Seq[SubAgentMetadata]]` — mirror Direct logic wrapped in `IO`, use `Files[IO].list` for directory enumeration, `IO(os.read(...))` for `.meta.json` content, `IO(io.circe.parser.parse(...))` for JSON parsing
- [ ] [impl] Add convenience method `listSubAgentsFor(cwd: os.Path, sessionId: String): IO[Seq[SubAgentMetadata]]` — resolve project dir via `ClaudeProjects.projectDirFor` then delegate to `listSubAgents`
- [ ] [impl] Verify Effectful tests pass with `./mill effectful.test`

## Integration

- [ ] [integration] Run `./mill __.compile` and verify no warnings
- [ ] [integration] Run `./mill __.test` and verify all tests pass (existing + new)
- [ ] [integration] Verify existing `DirectConversationLogIndexCwdTest` still passes unchanged
- [ ] [integration] Verify existing `EffectfulConversationLogIndexCwdTest` still passes unchanged
- [ ] [integration] Commit with descriptive message
