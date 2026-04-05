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

## Phase 3: Direct API - Basic session lifecycle (2026-04-05)

**What was built:**
- `works/iterative/claude/direct/Session.scala` — `Session` trait with `send(prompt: String): Flow[Message]`, `close(): Unit`, and `sessionId: String`
- `works/iterative/claude/direct/internal/cli/SessionProcess.scala` — Internal implementation managing a long-lived CLI process with stdin writer, stdout reader, and background stderr capture
- `works/iterative/claude/direct/ClaudeCode.scala` — Added `session(SessionOptions): Session` factory method on both the `ClaudeCode` class (instance, uses existing Ox scope) and companion object (static, requires `using Logger, Ox`)
- `works/iterative/claude/direct/package.scala` — Added `SessionOptions` re-export to direct package
- `works/iterative/claude/direct/internal/testing/SessionMockCliScript.scala` — Mock CLI script generator that reads stdin JSON and writes stdout JSON responses, simulating the stream-json protocol
- `works/iterative/claude/direct/internal/testing/MockLogger.scala` — Shared mock logger extracted from test files during code review

**Decisions made:**
- `--verbose` flag injected at the process boundary in `SessionProcess.configureSessionProcess` rather than in `CLIArgumentBuilder`, matching how the query path adds it in `buildCliArguments` — keeps CLI arg building focused on user-visible options
- `readInitMessages` uses a polling loop with `reader.ready()` and deadline to distinguish long-lived session processes (which emit init before stdin) from quick-exit mock scripts — avoids blocking on `readLine()` indefinitely
- `send()` writes the `SDKUserMessage` JSON to stdin eagerly (before returning the Flow), ensuring the CLI starts processing immediately; the Flow then reads stdout until `ResultMessage`
- `captureStderr` destroys the process on `InterruptedException` to unblock the pipe and prevent scope cancellation from hanging
- `SDKUserMessage` not re-exported in package.scala — it's an internal protocol detail, not a user-facing type
- `configureSessionProcess` duplicates a small amount of `ProcessBuilder` configuration from `ProcessManager.configureProcess` rather than extracting a shared helper — both methods are small and the `QueryOptions` vs `SessionOptions` types differ; extraction deferred until a third call site appears

**Patterns applied:**
- Trait + internal implementation: Public `Session` trait with package-private `SessionProcess` implementation, matching the existing `ClaudeCode` / `ProcessManager` split
- Factory method: `SessionProcess.start` companion object factory that constructs the process, reads init messages, and returns a ready-to-use `Session`
- Mock CLI scripts: Extended `MockCliScript` pattern for session protocol — scripts read stdin JSON and respond with stdout JSON, supporting multi-turn simulation
- Shared test utilities: `MockLogger` extracted to `internal/testing` package for reuse across test suites

**Testing:**
- Unit tests: 8 tests in `SessionTest.scala` (SDKUserMessage encoding, session ID extraction/defaults/updates, ResultMessage flow termination, close behavior, factory method)
- Integration tests: 5 tests in `SessionIntegrationTest.scala` (full lifecycle, stdin JSON verification, session ID from init, KeepAlive/StreamEvent passthrough, ClaudeCode.session factory)
- E2E tests: 2 tests in `SessionE2ETest.scala` (real CLI single turn, session ID validation — gated on CLI availability)

**Code review:**
- Iterations: 1
- Review file: review-phase-03-20260405-101513.md
- Skills applied: architecture, scala3, composition, testing, style
- Critical findings: 3 (vacuous T1 test, vacuous T7 assertion, MockLogger duplication) — all fixed
- Major warnings addressed: dead stderrBuffer removed, readInitMessages encapsulated to return Option[String], shared resolveExecutablePath helper extracted, SDKUserMessage re-export removed, Thread.sleep removed from integration test
- Deferred: `sessionId` as `Option[String]` vs magic string (follow-up), `package object` to top-level definitions (pre-existing, separate refactoring)

**For next phases:**
- `Session` trait and `ClaudeCode.session` factory are ready for multi-turn conversation support (Phase 4)
- `SessionProcess.send` already supports multiple sequential calls — Phase 4 adds turn sequencing tests and context verification
- `SessionMockCliScript` supports multi-turn simulation via `turnResponses: Map[String, TurnResponse]` — Phase 4 can use this directly
- The effectful API (Phase 5) can reference `SessionProcess` patterns for process lifecycle management with fs2
- Error handling (Phase 6) can add process liveness checks and typed errors to the existing `send`/`close` methods

**Files changed:**
```
M  direct/src/works/iterative/claude/direct/ClaudeCode.scala
A  direct/src/works/iterative/claude/direct/Session.scala
A  direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala
M  direct/src/works/iterative/claude/direct/package.scala
A  direct/test/src/works/iterative/claude/direct/SessionE2ETest.scala
A  direct/test/src/works/iterative/claude/direct/SessionIntegrationTest.scala
A  direct/test/src/works/iterative/claude/direct/SessionTest.scala
A  direct/test/src/works/iterative/claude/direct/internal/testing/MockLogger.scala
A  direct/test/src/works/iterative/claude/direct/internal/testing/SessionMockCliScript.scala
```

---

## Phase 4: Direct API - Multi-turn conversation (2026-04-05)

**What was built:**
- Multi-turn conversation test coverage across all three test levels (unit, integration, E2E)
- No production code changes were needed — `SessionProcess` from Phase 3 already handled multi-turn correctly

**Decisions made:**
- Phase is purely test-focused: the mechanical infrastructure for multi-turn already existed in Phase 3, so Phase 4 validates it works correctly
- Removed IT9 (duplicate of T10 in SessionTest) during code review to avoid test duplication across unit/integration layers
- Added message ordering assertions (not just type membership) to verify protocol ordering

**Patterns applied:**
- Dedicated `createdFiles` cleanup list in SessionTest (matching SessionIntegrationTest pattern) for non-script temp files
- Extracted `assumeClaudeAvailable()` helper in E2E tests to reduce repeated assume blocks
- Used `.getOrElse(fail(...))` instead of `.isDefined`/`.get` for idiomatic Scala 3 Option handling in test assertions

**Testing:**
- Unit tests: 4 tests added (T8-T11) — message isolation, session ID propagation, session ID updates, three-turn cycling
- Integration tests: 3 tests added (IT6-IT8) — two-turn lifecycle, stdin capture with ID progression, variable message counts per turn
- E2E tests: 2 tests added — context-dependent follow-up ("remember 42"), session ID stability across turns

**Code review:**
- Iterations: 1
- Skills applied: testing, style, scala3
- Critical findings: 1 (captureFile cleanup list) — fixed
- Warnings addressed: duplicate IT9 removed, session ID stability assertion added, message ordering assertions added, test ordering fixed, redundant import removed, fully-qualified imports simplified
- Suggestions addressed: `.getOrElse(fail(...))` pattern, `assumeClaudeAvailable()` helper

**For next phases:**
- Multi-turn conversation is fully validated in the direct API — Phase 5 (effectful API) can reference the same test patterns
- `SessionMockCliScript` multi-turn support confirmed working — no changes were needed to the mock infrastructure
- Error handling (Phase 6) can build on these tests to add failure scenarios during multi-turn sessions

**Files changed:**
```
M  direct/test/src/works/iterative/claude/direct/SessionTest.scala
M  direct/test/src/works/iterative/claude/direct/SessionIntegrationTest.scala
M  direct/test/src/works/iterative/claude/direct/SessionE2ETest.scala
```

---

### Refactoring R1: Split Session.send into send + stream (CQS) (2026-04-05)

**Trigger:** Comparison with the V2 Claude Agent SDK revealed our `send()` combines a command (write prompt to stdin) and a query (read response stream) in one method, violating command-query separation. The V2 SDK separates these as `send()` (void) and `stream()` (async generator).

**What changed:**
- `direct/src/.../Session.scala` — trait split: `send(prompt: String): Flow[Message]` → `send(prompt: String): Unit` + `stream(): Flow[Message]`
- `direct/src/.../internal/cli/SessionProcess.scala` — implementation split: stdin write separated from stdout reading loop
- `direct/test/src/.../SessionTest.scala` — all call sites updated from `session.send(x).runToList()` to `session.send(x); session.stream().runToList()`
- `direct/test/src/.../SessionIntegrationTest.scala` — same call site pattern update
- `direct/test/src/.../SessionE2ETest.scala` — same call site pattern update

**Before → After:**
- `session.send("prompt").forEach(...)` → `session.send("prompt"); session.stream().forEach(...)`
- `send` no longer returns a value; `stream` is a separate query method
- Internal mechanics unchanged: stdin writing, stdout reading, session ID updates, stderr capture all preserved

**Patterns applied:**
- Command-Query Separation (CQS): `send` is a command (side effect, returns Unit), `stream` is a query (returns data, no side effect beyond reading)

**Testing:**
- Tests updated: 15 call sites across 3 test files
- All 193 direct tests passing, no regressions
- No new tests added (mechanical refactoring — same assertions, different call patterns)

**Code review:**
- Iterations: 1
- Review file: review-refactor-05-R1-20260405-144725.md
- Skills applied: style, testing, scala3, composition
- Composition reviewer raised design concern about CQS split breaking composability — triaged as deliberate decision aligning with V2 SDK direction
- Warnings addressed: duplicate Scaladoc removed, dead `caught != null` assertion removed, PURPOSE comment updated
- Deferred to Phase 6: `stream()` without `send()` guard, `close()` without consuming stream test

**Files changed:**
```
M  direct/src/works/iterative/claude/direct/Session.scala
M  direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala
M  direct/test/src/works/iterative/claude/direct/SessionTest.scala
M  direct/test/src/works/iterative/claude/direct/SessionIntegrationTest.scala
M  direct/test/src/works/iterative/claude/direct/SessionE2ETest.scala
```

---
