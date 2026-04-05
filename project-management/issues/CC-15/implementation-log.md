# Implementation Log: Support persistent two-way conversations with a single Claude Code session

Issue: CC-15

This log tracks the evolution of implementation across phases.

---

## Phase 1: Stdin message format and response delimiting (2026-04-04)

**What was built:**
- `works/iterative/claude/core/model/SDKUserMessage.scala` - Case class representing JSON messages written to CLI stdin in stream-json mode, with circe Encoder producing the exact protocol JSON shape
- `works/iterative/claude/core/model/Message.scala` - Extended with `KeepAliveMessage` (case object) and `StreamEventMessage(data: Map[String, Any])` for stream-json protocol message types
- `works/iterative/claude/core/parsing/JsonParser.scala` - Added parsing branches for `keep_alive` and `stream_event` message types
- `works/iterative/claude/direct/internal/parsing/JsonParser.scala` - Added exhaustive match cases for new message types

**Decisions made:**
- Named the stdin message type `SDKUserMessage` (matching TypeScript Agent SDK naming) rather than `StdinMessage` (from analysis) to align with the protocol terminology
- Added `KeepAliveMessage` and `StreamEventMessage` types now rather than deferring, so downstream phases can pattern-match on them
- `StreamEventMessage` uses `Map[String, Any]` (same pattern as existing `SystemMessage`) rather than typed fields, since the full content delta structure will be refined when consumers exist
- Encoder produces `type` and `message.role` as constants, not stored as case class fields

**Patterns applied:**
- Sealed trait extension: Added new subtypes to existing Message hierarchy
- Companion object given: Circe Encoder instance in SDKUserMessage companion object (first Encoder in the project)
- Mock CLI scripts: Integration tests use temporary bash scripts simulating CLI stdin/stdout protocol

**Testing:**
- Unit tests: 4 tests for SDKUserMessage Encoder (shape, parentToolUseId, pending session, no newlines)
- Unit tests: 3 tests for new parser branches (keep_alive, stream_event with nested data, realistic ResultMessage end-of-turn)
- Property tests: Added KeepAliveMessage and StreamEventMessage generators to round-trip property tests
- Integration tests: 2 mock CLI round-trip tests (echo response, session protocol with init message)
- E2E test: 1 test against real Claude Code CLI (validates stdin format accepted)

**Code review:**
- Iterations: 1
- Major findings: Missing property test generators for new types, E2E test was permanently ignored instead of assumption-gated, weak E2E assertion
- All findings addressed

**For next phases:**
- `SDKUserMessage` and its Encoder are ready for use in session stdin writing (Phase 3/5)
- `KeepAliveMessage` and `StreamEventMessage` can be pattern-matched by session response readers
- `ResultMessage` confirmed as end-of-turn delimiter in the stream-json protocol
- The mock CLI script pattern in round-trip tests can be extended for session protocol testing

**Files changed:**
```
A  works/iterative/claude/core/model/SDKUserMessage.scala
M  works/iterative/claude/core/model/Message.scala
M  works/iterative/claude/core/parsing/JsonParser.scala
M  works/iterative/claude/direct/internal/parsing/JsonParser.scala
A  test/works/iterative/claude/core/model/SDKUserMessageTest.scala
A  test/works/iterative/claude/core/model/SDKUserMessageRoundTripTest.scala
A  test/works/iterative/claude/core/model/SDKUserMessageE2ETest.scala
M  test/works/iterative/claude/core/parsing/JsonParserTest.scala
M  test/works/iterative/claude/direct/internal/parsing/JsonParserTest.scala
```

---

## Phase 2: SessionOptions configuration (2026-04-04)

**What was built:**
- `core/src/works/iterative/claude/core/model/SessionOptions.scala` - Case class with all 18 fields from `QueryOptions` except `prompt`, fluent `with*` builder methods, and `SessionOptions.defaults` companion factory
- `core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala` - Added `buildSessionArgs(options: SessionOptions): List[String]` method that prepends required streaming flags (`--print --input-format stream-json --output-format stream-json`) and maps all option fields to CLI flags

**Decisions made:**
- Duplicated field-by-field flag mapping in `buildSessionArgs` rather than extracting shared helper with `buildArgs` — both methods are small and mechanical; extraction deferred until a third call site appears (per phase context guidance)
- `SessionOptions` is a flat case class parallel to `QueryOptions` rather than composing a shared `CommonOptions` — matches existing API ergonomics; refactoring deferred

**Patterns applied:**
- Fluent builder: Same `with*` method pattern as `QueryOptions` for API consistency
- Companion object factory: `SessionOptions.defaults` mirrors `QueryOptions.simple(prompt)` idiom

**Testing:**
- Unit tests: 3 tests for SessionOptions construction, defaults equality, and all 18 builder methods (using structural equality via `copy`)
- Unit tests: 16 tests for buildSessionArgs covering required flags, flag ordering, each field mapping, None handling, and multi-field combinations

**Code review:**
- Iterations: 1
- Review skills: style, testing, scala3, composition
- Findings addressed: removed redundant self-import, replaced magic number `5` with `requiredFlags.length`, improved builder isolation tests to use full structural equality via `assertEquals(built, base.copy(...))`
- Deferred: shared arg-building helper extraction, `optArg`/`flagArg` private helpers (deliberate per phase context)

**For next phases:**
- `SessionOptions` is ready for use as session startup configuration in Phase 3 (direct API) and Phase 5 (effectful API)
- `buildSessionArgs` provides the CLI arguments needed to launch a streaming session subprocess
- Process-level fields (`timeout`, `inheritEnvironment`, `environmentVariables`, `executable`, `executableArgs`, `pathToClaudeCodeExecutable`) are carried in `SessionOptions` but not mapped to CLI flags — consumed by the session runner

**Files changed:**
```
A  core/src/works/iterative/claude/core/model/SessionOptions.scala
M  core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala
A  core/test/src/works/iterative/claude/core/model/SessionOptionsTest.scala
A  core/test/src/works/iterative/claude/core/cli/SessionOptionsArgsTest.scala
```

---
