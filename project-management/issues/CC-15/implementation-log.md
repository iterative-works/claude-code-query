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
