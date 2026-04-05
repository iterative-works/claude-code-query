---
generated_from: ae6aaae2e6eeb05aa1d46cc56da86d9214c111ec
generated_at: 2026-04-05T17:05:46Z
branch: CC-15-phase-06
issue_id: CC-15
phase: complete (6 of 6)
files_analyzed:
  - core/src/works/iterative/claude/core/CLIError.scala
  - core/src/works/iterative/claude/core/cli/CLIArgumentBuilder.scala
  - core/src/works/iterative/claude/core/model/Message.scala
  - core/src/works/iterative/claude/core/model/SDKUserMessage.scala
  - core/src/works/iterative/claude/core/model/SessionOptions.scala
  - core/src/works/iterative/claude/core/parsing/JsonParser.scala
  - direct/src/works/iterative/claude/direct/ClaudeCode.scala
  - direct/src/works/iterative/claude/direct/Session.scala
  - direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala
  - direct/src/works/iterative/claude/direct/package.scala
  - effectful/src/works/iterative/claude/effectful/ClaudeCode.scala
  - effectful/src/works/iterative/claude/effectful/Session.scala
  - effectful/src/works/iterative/claude/effectful/internal/cli/SessionProcess.scala
  - effectful/src/works/iterative/claude/effectful/package.scala
---

# Review Packet: CC-15 - Persistent Two-Way Conversations

## Goals

This feature adds persistent two-way conversation support to both the direct (Ox) and effectful (cats-effect/fs2) APIs by keeping a single Claude Code CLI process alive across multiple turns using `--input-format stream-json`.

Key objectives:

- Enable a single long-lived CLI subprocess to handle multiple conversational turns without restart overhead, eliminating per-turn process startup cost.
- Implement the `--input-format stream-json` protocol: write `SDKUserMessage` JSON to stdin per turn, delimit responses via `ResultMessage` on stdout.
- Expose `Session` as a first-class API type in both the direct API (`Session.send/stream/close`) and the effectful API (`Resource[IO, Session]` with `send: IO[Unit]` and `stream: Stream[IO, Message]`).
- Surface typed errors (`SessionProcessDied`, `SessionClosedError`) when the underlying process crashes or the session is used after closure.
- Add `SessionOptions` mirroring `QueryOptions` (minus `prompt`) to configure session startup: model, system prompt, tools, permission mode, cwd, and resume/continue options.

The implementation is purely additive. All existing query APIs are unchanged.

## Scenarios

- [ ] A session can be opened with `ClaudeCode.session(options)`, a message sent, and a streaming response received
- [ ] The underlying CLI process starts once and stays alive across multiple `send` calls
- [ ] Process is terminated cleanly when the session is closed (direct: `close()`; effectful: Resource finalizer)
- [ ] Multiple sequential sends within one session each return isolated message streams for their turn
- [ ] Context is maintained across turns (the CLI handles this internally; the SDK does not need to track it)
- [ ] `SessionOptions` supports all relevant configuration fields (model, systemPrompt, allowedTools, permissionMode, cwd, resume, continueConversation, etc.) except `prompt`
- [ ] `CLIArgumentBuilder.buildSessionArgs` prepends the three required streaming flags (`--print --input-format stream-json --output-format stream-json`)
- [ ] `SDKUserMessage` is encoded to the exact JSON shape the CLI protocol expects (`type: "user"`, nested `message.role`, `session_id`, `parent_tool_use_id`)
- [ ] `session_id` is extracted from the CLI's init `SystemMessage` on session start and kept up to date from each `ResultMessage`
- [ ] `KeepAlive` and `StreamEvent` messages pass through to callers without interrupting the stream
- [ ] When the CLI process crashes mid-turn, the response stream raises `SessionProcessDied`
- [ ] When the CLI process crashes between turns, the next `send` raises `SessionProcessDied`
- [ ] Malformed JSON lines are logged and skipped; the session remains functional for subsequent turns
- [ ] Calling `send` on a closed direct session raises `SessionClosedError`
- [ ] Effectful `Resource` cleanup terminates the process even when an exception is thrown inside `use`

## Entry Points

| File | Method / Class | Why Start Here |
|------|----------------|----------------|
| `direct/src/.../direct/Session.scala` | `Session` trait | Public contract for the direct API: `send`, `stream`, `close`, `sessionId` |
| `effectful/src/.../effectful/Session.scala` | `Session` trait | Public contract for the effectful API: `send: IO[Unit]`, `stream: Stream[IO, Message]`, `sessionId: IO[String]` |
| `direct/src/.../direct/ClaudeCode.scala` | `ClaudeCode.session` | Factory method (both instance and companion) that creates a direct `Session` |
| `effectful/src/.../effectful/ClaudeCode.scala` | `ClaudeCode.session` | Factory method returning `Resource[IO, Session]` |
| `direct/src/.../internal/cli/SessionProcess.scala` | `SessionProcess.start` | Launches the process, reads the init message, and wires stdin/stdout; contains `send`, `stream`, `close`, and liveness logic |
| `effectful/src/.../internal/cli/SessionProcess.scala` | `SessionProcess.start` | Resource-based launch with three background fibers (stdin writer, stdout reader, stderr capture) and two-ref error propagation |
| `core/src/.../core/model/SDKUserMessage.scala` | `SDKUserMessage` | Protocol type written to CLI stdin each turn; the circe `Encoder` here defines the wire format |
| `core/src/.../core/CLIError.scala` | `SessionProcessDied`, `SessionClosedError` | New error types added to the sealed `CLIError` hierarchy |
| `core/src/.../core/model/SessionOptions.scala` | `SessionOptions` | Session startup configuration; mirrors `QueryOptions` minus `prompt` |
| `core/src/.../core/cli/CLIArgumentBuilder.scala` | `buildSessionArgs` | Translates `SessionOptions` to CLI flags, always prepending the three required streaming flags |

## Diagrams

### Component Overview

```
                 ┌─────────────────────────────────────────────────────┐
                 │                    Public API                        │
                 │                                                      │
                 │  direct.ClaudeCode.session(options)                  │
                 │  effectful.ClaudeCode.session(options)               │
                 └────────────────────────┬────────────────────────────┘
                                          │ creates
                          ┌───────────────┴───────────────┐
                          │                               │
              ┌───────────▼────────┐         ┌────────────▼──────────┐
              │  direct.Session    │         │ effectful.Session      │
              │  (trait)           │         │ (trait)                │
              │                    │         │                        │
              │  send(str): Unit   │         │ send(str): IO[Unit]    │
              │  stream(): Flow[M] │         │ stream: Stream[IO,M]   │
              │  close(): Unit     │         │ sessionId: IO[String]  │
              │  sessionId: String │         │                        │
              └─────────┬──────────┘         └────────────┬──────────┘
                        │                                 │
          ┌─────────────▼──────────┐       ┌─────────────▼────────────┐
          │  direct.SessionProcess │       │ effectful.SessionProcess  │
          │  (impl)                │       │ (impl)                   │
          │                        │       │                          │
          │  AtomicBoolean alive   │       │  Ref[IO, Boolean] alive  │
          │  AtomicRef sessionId   │       │  Ref[IO, Option[Err]]    │
          │  BufferedWriter stdin  │       │  Queue stdin             │
          │  BufferedReader stdout │       │  Queue messages          │
          │  fork: captureStderr   │       │  3 background fibers     │
          └──────────┬─────────────┘       └─────────────┬────────────┘
                     │                                    │
                     └────────────────┬───────────────────┘
                                      │ spawns
                         ┌────────────▼────────────────┐
                         │   Claude Code CLI process    │
                         │                             │
                         │  stdin ← SDKUserMessage JSON │
                         │  stdout → stream-json msgs   │
                         │  stderr → logger.debug       │
                         └─────────────────────────────┘
```

### Session Turn Flow (Direct API)

```
Caller                  SessionProcess                  CLI Process
  │                           │                              │
  │  send("prompt")           │                              │
  ├──────────────────────────►│                              │
  │                           │  write SDKUserMessage JSON   │
  │                           ├─────────────────────────────►│
  │                           │  flush stdin                 │
  │  stream(): Flow[Message]  │                              │
  ├──────────────────────────►│                              │
  │                           │◄────────── AssistantMessage ─┤
  │◄── emit AssistantMessage ─┤                              │
  │                           │◄──────── StreamEventMessage ─┤
  │◄─ emit StreamEventMessage ┤                              │
  │                           │◄────────── KeepAliveMessage ─┤
  │◄── emit KeepAliveMessage ─┤                              │
  │                           │◄─────────── ResultMessage ───┤
  │◄─── emit ResultMessage ───┤  update sessionId            │
  │     (Flow completes)      │                              │
  │                           │                              │
  │  (next turn starts here)  │                              │
```

### Effectful Error Propagation

```
startStdoutReader fiber
  │  reads stdout lines
  │  parses messages → messageQueue.offer(Some(msg))
  │  on malformed JSON: log + skip (IO.unit)
  │  on stdout EOF:
  │    aliveRef.set(false)
  │    if exitCode != 0: pendingErrorRef.set(Some(SessionProcessDied(...)))
  └──► messageQueue.offer(None)  ← signals EOF to stream consumers

SessionImpl.stream
  │  Stream.fromQueueNoneTerminated(messageQueue)
  │  .takeThrough(!_.isInstanceOf[ResultMessage])
  │  .onFinalize:
  │      if !resultSeen:
  │          pendingErrorRef.get → raise error if present
  └──► raises SessionProcessDied to caller when process died mid-turn
```

## Test Summary

### Core Module

| Type | File | Tests |
|------|------|-------|
| Unit | `SDKUserMessageTest` | Encoder produces correct JSON structure; `parentToolUseId` as null/string; `pending` session ID; no embedded newlines |
| Property | `SDKUserMessageRoundTripTest` | Round-trip property tests including `KeepAliveMessage` and `StreamEventMessage` generators |
| E2E | `SDKUserMessageE2ETest` | Real CLI accepts the stdin format (gated on CLI availability) |
| Unit | `SessionOptionsTest` | All fields default to None; `defaults` equals `SessionOptions()`; each `with*` builder sets only its own field |
| Unit | `SessionOptionsArgsTest` | Required flags appear first; no trailing prompt; each of 16 option fields maps to the correct CLI flag; None values produce no extra args |
| Unit | `JsonParserTest` | Parses `keep_alive` and `stream_event` message types; realistic `ResultMessage` as end-of-turn delimiter |

### Direct Module

| Type | File | Tests |
|------|------|-------|
| Unit | `SessionTest` | `SDKUserMessage` encoding; session ID extraction from `SystemMessage(init)`; non-init does not set ID; defaults to `"pending"`; `Flow` completes after `ResultMessage`; session ID updated from `ResultMessage`; two sequential sends return isolated streams; session ID propagates across turns; three-turn cycling; `close()` terminates process |
| Unit | `SessionErrorTest` | `send` after `close()` raises `SessionClosedError`; `close()` is idempotent; `send` to dead process raises `SessionProcessDied`; crash mid-turn stream raises `SessionProcessDied`; malformed JSON lines are skipped (session remains functional) |
| Integration | `SessionIntegrationTest` | Full single-turn lifecycle with mock CLI; stdin receives correct `SDKUserMessage` JSON; session ID extracted from init message; `KeepAlive`/`StreamEvent` pass through; `ClaudeCode.session` factory; full two-turn lifecycle; stdin shows correct session ID progression; turns with different message counts |
| Integration | `SessionErrorIntegrationTest` | Crash mid-turn; crash between turns; malformed JSON recovery; multiple malformed lines |
| E2E | `SessionE2ETest` | Real CLI single-turn; two-turn context preservation; session ID is valid after first turn; SDKUserMessage E2E (stdin format validated) |

### Effectful Module

| Type | File | Tests |
|------|------|-------|
| Unit | `SessionTest` | `SDKUserMessage` encoding; session ID defaults to `"pending"`; session ID extracted from init; session ID updated from `ResultMessage`; stream completes after `ResultMessage`; `KeepAlive`/`StreamEvent` pass through; two sequential sends isolated; session ID propagates to second send; three-turn cycling |
| Unit | `SessionErrorTest` | `send` to dead process raises `SessionProcessDied` in IO; crash mid-turn stream raises error; malformed JSON is skipped; `send` after death with no pending error raises generic `SessionProcessDied` |
| Integration | `SessionIntegrationTest` | Full single-turn lifecycle; `Resource` cleanup on normal exit; `Resource` cleanup when exception thrown inside `use`; stdin carries correct JSON; session ID from init message; two-turn lifecycle; stdin session ID progression; variable message counts; `ClaudeCode.session` factory |
| Integration | `SessionErrorIntegrationTest` | Crash mid-turn; crash between turns; malformed JSON; Resource cleanup after crash does not hang |
| E2E | `SessionE2ETest` | Real CLI single-turn; two-turn context preservation; session ID valid after first turn |
| Unit | `EffectfulPackageReexportTest` | `SessionOptions` re-exported from effectful package |

## Files Changed

### New Files

| File | Purpose |
|------|---------|
| `core/src/.../core/model/SDKUserMessage.scala` | Protocol type for stdin messages with circe Encoder |
| `core/src/.../core/model/SessionOptions.scala` | Session startup configuration (18 fields, fluent builder) |
| `direct/src/.../direct/Session.scala` | Public Session trait for direct API |
| `direct/src/.../internal/cli/SessionProcess.scala` | Direct API session implementation (AtomicBoolean liveness, stdin/stdout lifecycle) |
| `effectful/src/.../effectful/Session.scala` | Public Session trait for effectful API |
| `effectful/src/.../internal/cli/SessionProcess.scala` | Effectful API session implementation (Resource, 3 fibers, two-ref error propagation) |
| `direct/test/.../testing/SessionMockCliScript.scala` | Mock CLI script builder for integration tests (normal, crash, malformed JSON scenarios) |
| `direct/test/.../testing/MockLogger.scala` | Shared mock logger for direct test suites |
| `core/test/.../model/SDKUserMessageTest.scala` | Unit tests for `SDKUserMessage` Encoder |
| `core/test/.../model/SDKUserMessageRoundTripTest.scala` | Property tests including new message type generators |
| `core/test/.../model/SDKUserMessageE2ETest.scala` | E2E validation of stdin format against real CLI |
| `core/test/.../model/SessionOptionsTest.scala` | Unit tests for `SessionOptions` construction and builders |
| `core/test/.../cli/SessionOptionsArgsTest.scala` | Unit tests for `CLIArgumentBuilder.buildSessionArgs` |
| `direct/test/.../SessionTest.scala` | Unit tests for direct Session behavior |
| `direct/test/.../SessionIntegrationTest.scala` | Integration tests for direct Session with mock CLI |
| `direct/test/.../SessionE2ETest.scala` | E2E tests for direct Session against real CLI |
| `direct/test/.../SessionErrorTest.scala` | Unit tests for direct Session error handling |
| `direct/test/.../SessionErrorIntegrationTest.scala` | Integration tests for direct Session error scenarios |
| `effectful/test/.../SessionTest.scala` | Unit tests for effectful Session behavior |
| `effectful/test/.../SessionIntegrationTest.scala` | Integration tests for effectful Session with mock CLI |
| `effectful/test/.../SessionE2ETest.scala` | E2E tests for effectful Session against real CLI |
| `effectful/test/.../SessionErrorTest.scala` | Unit tests for effectful Session error handling |
| `effectful/test/.../SessionErrorIntegrationTest.scala` | Integration tests for effectful Session error scenarios |

### Modified Files

| File | What Changed |
|------|-------------|
| `core/src/.../core/CLIError.scala` | Added `SessionProcessDied(exitCode, stderr)` and `SessionClosedError(sessionId)` to the sealed hierarchy |
| `core/src/.../core/model/Message.scala` | Added `KeepAliveMessage` (case object) and `StreamEventMessage(data: Map[String, Any])` |
| `core/src/.../core/parsing/JsonParser.scala` | Added parsing branches for `keep_alive` and `stream_event` message types |
| `core/src/.../core/cli/CLIArgumentBuilder.scala` | Added `buildSessionArgs(options: SessionOptions): List[String]` |
| `direct/src/.../direct/ClaudeCode.scala` | Added `session(SessionOptions): Session` to both class and companion; extracted `resolveExecutablePath` helper |
| `direct/src/.../direct/package.scala` | Re-exported `SessionOptions` |
| `effectful/src/.../effectful/ClaudeCode.scala` | Added `session(SessionOptions): Resource[IO, Session]`; extracted shared `resolveExecutable` helper |
| `effectful/src/.../effectful/package.scala` | Re-exported `SessionOptions` |
| `direct/internal/parsing/JsonParser.scala` | Added exhaustive match cases for `KeepAliveMessage` and `StreamEventMessage` |
| `effectful/test/.../EffectfulPackageReexportTest.scala` | Added `SessionOptions` re-export assertion |

<details>
<summary>Refactoring: Session.send CQS split (between Phase 4 and 5)</summary>

After Phase 4, the direct `Session.send(prompt): Flow[Message]` was split into:
- `send(prompt: String): Unit` — command (writes to stdin, returns nothing)
- `stream(): Flow[Message]` — query (reads stdout until ResultMessage)

This follows Command-Query Separation and aligns with the V2 Claude Agent SDK. The effectful API was designed with CQS from the start (`send: IO[Unit]`, `stream: Stream[IO, Message]`). All 15 call sites in direct tests were updated; no production behavior changed.

</details>
