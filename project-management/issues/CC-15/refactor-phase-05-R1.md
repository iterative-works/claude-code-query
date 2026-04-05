# Refactoring R1: Split Session.send into send + stream (CQS)

**Phase:** 5
**Created:** 2026-04-05
**Status:** Planned

## Decision Summary

Our `Session.send(prompt)` combines a command (write prompt to stdin) and a query (return response stream) in one method, violating command-query separation. The V2 Claude Agent SDK separates these as `send()` (void) and `stream()` (async generator). Splitting them makes the API cleaner, aligns with the official SDK direction, and makes the turn lifecycle explicit.

## Current State

Direct `Session` trait (`direct/src/works/iterative/claude/direct/Session.scala`):

```scala
trait Session:
  def send(prompt: String): Flow[Message]  // combined command+query
  def close(): Unit
  def sessionId: String
```

`SessionProcess.send()` eagerly writes to stdin, then returns a `Flow` that reads stdout until `ResultMessage`.

All tests use `session.send("...").forEach(...)` or `session.send("...").toList()`.

## Target State

Direct `Session` trait:

```scala
trait Session:
  def send(prompt: String): Unit      // command: write SDKUserMessage to stdin
  def stream(): Flow[Message]         // query: read stdout until ResultMessage
  def close(): Unit
  def sessionId: String
```

Test usage becomes `session.send("..."); session.stream().forEach(...)`.

## Constraints

- PRESERVE: All existing test assertions and coverage — only call sites change, not what's being tested
- PRESERVE: `SessionProcess` internal mechanics (stdin writing, stdout reading, session ID update, stderr capture)
- PRESERVE: `ClaudeCode.session()` factory methods — no signature changes
- DO NOT TOUCH: Core model types (Message, SDKUserMessage, SessionOptions)
- DO NOT TOUCH: CLIArgumentBuilder
- DO NOT TOUCH: Effectful module (it will be built with the new pattern from scratch)
- DO NOT TOUCH: `SessionMockCliScript` or `MockLogger`

## Tasks

- [ ] [impl] [Analysis] Review all usages of `Session.send` in tests to confirm scope
- [ ] [impl] [Refactor] Split `Session` trait: `send(prompt: String): Unit` + `stream(): Flow[Message]`
- [ ] [impl] [Refactor] Split `SessionProcess.send` into `send` (stdin write) and `stream` (stdout read + emit)
- [ ] [impl] [Test] Update `SessionTest.scala` call sites: `send` then `stream`
- [ ] [impl] [Test] Update `SessionIntegrationTest.scala` call sites
- [ ] [impl] [Test] Update `SessionE2ETest.scala` call sites
- [ ] [impl] [Verify] Run all tests (`./mill __.test`), ensure nothing broke
- [ ] [impl] [Cleanup] Remove any dead code from the split

## Verification

- [ ] All existing tests pass
- [ ] `Session` trait has separate `send` and `stream` methods
- [ ] `send` returns `Unit`, `stream` returns `Flow[Message]`
- [ ] No regressions in functionality
- [ ] Direct API ClaudeCode.session factory still works
