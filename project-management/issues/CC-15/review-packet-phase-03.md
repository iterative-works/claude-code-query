---
generated_from: 2ab28937c7df08e34b412c5f58fe2203261d23c9
generated_at: 2026-04-05T08:13:25Z
branch: CC-15-phase-03
issue_id: CC-15
phase: 3
files_analyzed:
  - direct/src/works/iterative/claude/direct/Session.scala
  - direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala
  - direct/src/works/iterative/claude/direct/ClaudeCode.scala
  - direct/src/works/iterative/claude/direct/package.scala
  - direct/test/src/works/iterative/claude/direct/SessionTest.scala
  - direct/test/src/works/iterative/claude/direct/SessionIntegrationTest.scala
  - direct/test/src/works/iterative/claude/direct/SessionE2ETest.scala
  - direct/test/src/works/iterative/claude/direct/internal/testing/SessionMockCliScript.scala
---

# Review Packet: Phase 3 - Direct API Basic Session Lifecycle

## Goals

This phase delivers the core session API for the direct module, enabling callers to open a persistent Claude Code CLI process, exchange one or more messages over its stdin/stdout, and close it cleanly when done. Prior to this phase every interaction spawned a fresh process; now the process lives for the duration of the session, eliminating per-turn startup cost.

Key objectives:

- Define the `Session` trait (`send`, `close`, `sessionId`) as the public contract for an active conversational session
- Implement `SessionProcess` — the internal class that manages the long-lived CLI process, writes `SDKUserMessage` JSON to stdin, reads response lines from stdout, and terminates the process on `close`
- Add `ClaudeCode.session(SessionOptions)` factory methods on both the class and companion object
- Extract the `session_id` from the CLI's initial `SystemMessage(subtype="init")` and keep it current via `ResultMessage` updates
- Re-export `Session` and `SessionOptions` in the `direct` package object so callers need only one import
- Provide a `SessionMockCliScript` utility that generates executable shell scripts simulating the stream-json protocol for integration tests

## Scenarios

- [ ] A session can be opened, a single message sent, a streaming `Flow[Message]` received, and the session closed
- [ ] The underlying CLI process starts once at session creation and remains alive after `send` completes
- [ ] `send` writes a correctly-formatted `SDKUserMessage` JSON line to process stdin
- [ ] `send` returns a `Flow[Message]` that emits all messages from stdout and completes after the `ResultMessage`
- [ ] `KeepAliveMessage` and `StreamEventMessage` are passed through to the caller's Flow unchanged
- [ ] Session ID is extracted from the CLI's initial `SystemMessage(subtype="init")`
- [ ] Session ID defaults to `"pending"` when no init message arrives before the first `send`
- [ ] Session ID is updated to the value carried in the `ResultMessage` after each `send` completes
- [ ] `close()` terminates the CLI process (stdin closed, forcibly destroyed if needed)
- [ ] `Session` and `SessionOptions` are accessible via a single `import works.iterative.claude.direct.*`

## Entry Points

| File | Method / Class | Why Start Here |
|------|----------------|----------------|
| `direct/src/works/iterative/claude/direct/Session.scala` | `Session` trait | Public API contract — defines `send`, `close`, and `sessionId` |
| `direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala` | `SessionProcess.start` | Factory that creates the process, captures stderr, reads init message, returns a ready `Session` |
| `direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala` | `SessionProcess.send` | Core send logic: stdin write, stdout read loop, Flow emission, ResultMessage boundary detection |
| `direct/src/works/iterative/claude/direct/ClaudeCode.scala` | `ClaudeCode.session` | Public factory on both class and companion object; validates cwd and resolves executable path |
| `direct/src/works/iterative/claude/direct/package.scala` | package object | Confirm `Session` and `SessionOptions` re-exports are present |

## Diagrams

### Component Relationships

```
works.iterative.claude.direct
│
├── Session (trait)                          ← public contract
│     send(prompt): Flow[Message]
│     close(): Unit
│     sessionId: String
│
├── ClaudeCode (class / companion)
│     session(SessionOptions): Session       ← factory; delegates to createSession
│
└── internal.cli
      └── SessionProcess                     ← implements Session
            - process: java.lang.Process
            - stdinWriter: BufferedWriter
            - stdoutReader: BufferedReader
            - currentSessionId: AtomicReference[String]
            start(executablePath, options)   ← factory on companion object
```

### Session Lifecycle Flow

```
Caller                  ClaudeCode            SessionProcess          CLI Process
  │                         │                       │                      │
  │  session(opts)          │                       │                      │
  │────────────────────────>│                       │                      │
  │                         │  start(path, opts)    │                      │
  │                         │──────────────────────>│                      │
  │                         │                       │  ProcessBuilder.start()
  │                         │                       │─────────────────────>│
  │                         │                       │  fork: captureStderr │
  │                         │                       │  readInitMessages()  │
  │                         │                       │<──── SystemMessage(init, session_id)
  │                         │                       │  currentSessionId.set(id)
  │<────────────────────────────────────────────────│  return SessionProcess
  │                         │                       │                      │
  │  send("prompt")         │                       │                      │
  │─────────────────────────────────────────────────>                      │
  │  stdinWriter.write(SDKUserMessage JSON + newline)│                      │
  │                         │                       │─────────────────────>│
  │                         │   Flow.usingEmit reads stdout line by line   │
  │<─── AssistantMessage ───────────────────────────│<─────────────────────│
  │<─── KeepAliveMessage ───────────────────────────│<─────────────────────│
  │<─── StreamEventMessage ─────────────────────────│<─────────────────────│
  │<─── ResultMessage ──────────────────────────────│<─────────────────────│
  │     (Flow completes; sessionId updated)          │                      │
  │                         │                       │                      │
  │  close()                │                       │                      │
  │─────────────────────────────────────────────────>                      │
  │                         │  stdinWriter.close()  │                      │
  │                         │  process.waitFor(5s)  │                      │
  │                         │  destroyForcibly if needed                   │
  │                         │                       │─────────────────────>│ (exit)
```

### Session ID State Machine

```
  [created]
      │
      │ init message arrives during readInitMessages()
      ├──────────────────────────────────> "extracted-id"
      │
      │ no init message before first send
      └──────────────────────────────────> "pending"
                                               │
                                               │ ResultMessage received during send
                                               └──────────────────────> "result-session-id"
```

### stdin/stdout Protocol (stream-json)

```
stdin  (Scala → CLI):   {"type":"user","message":{"role":"user","content":"..."},"session_id":"...","parent_tool_use_id":null}\n

stdout (CLI → Scala):   {"type":"system","subtype":"init","session_id":"...","tools":[],...}\n   ← startup only
                        {"type":"keep_alive"}\n                                                    ← periodic
                        {"type":"stream_event","data":{...}}\n                                     ← streaming deltas
                        {"type":"assistant","message":{...}}\n                                     ← full assistant turn
                        {"type":"result","subtype":"conversation_result","session_id":"...","is_error":false,...}\n  ← end-of-turn
```

## Test Summary

### Unit Tests — `SessionTest.scala` (9 tests)

| Test | Type | Description |
|------|------|-------------|
| Session trait compiles with expected method signatures | Unit | Compile-time verification that `send`, `close`, `sessionId` have correct types |
| SDKUserMessage is correctly encoded for stdin | Unit | Verifies JSON shape: `type=user`, nested `message.role=user`, `message.content`, `session_id` |
| SDKUserMessage with pending session ID uses 'pending' literal | Unit | Edge case: first-send encoding before session ID is known |
| Session ID is extracted from SystemMessage with subtype init | Unit | Pattern-matches `SystemMessage("init", data)` and reads `session_id` key |
| Non-init SystemMessage does not provide session ID | Unit | Confirms only `"init"` subtype triggers extraction |
| Session ID defaults to 'pending' when no init message received | Unit | Uses a `MockCliScript` that skips the init message; asserts `sessionId == "pending"` |
| Flow completes after emitting ResultMessage | Unit | Drives a `SessionMockCliScript` with one turn; asserts exactly 2 messages (assistant + result) |
| Session ID is updated from ResultMessage after send completes | Unit | After `runToList()`, asserts `session.sessionId == "updated-session-id-456"` |
| close() terminates the underlying process | Unit | Calls `close()` on a session backed by a simple script; passes if no hang |

### Integration Tests — `SessionIntegrationTest.scala` (5 tests)

| Test | Type | Description |
|------|------|-------------|
| full single-turn session lifecycle with mock CLI | Integration | Full open/send/close using `SessionMockCliScript`; verifies assistant text and ResultMessage fields |
| mock CLI receives correct SDKUserMessage JSON on stdin | Integration | `captureStdinFile` option captures what the process received; parses JSON and checks `type` and `content` |
| session extracts session ID from init SystemMessage | Integration | Script emits init with `session_id`; asserts `session.sessionId` matches before any send |
| KeepAlive and StreamEvent messages are emitted in the Flow | Integration | Script emits `keep_alive` + `stream_event` interleaved; asserts all four message types present |
| ClaudeCode.session factory creates a working session via instance API | Integration | Uses `ClaudeCode.concurrent` instance instead of companion object; verifies same behaviour |

### E2E Tests — `SessionE2ETest.scala` (2 tests)

| Test | Type | Description |
|------|------|-------------|
| E2E: real CLI session completes a single turn | E2E | Gated on CLI availability and credentials; sends "What is 1+1?", asserts `AssistantMessage` and `ResultMessage` present |
| E2E: session ID is a valid non-pending value after first turn | E2E | Same gate; asserts `session.sessionId` is non-empty and not `"pending"` after a completed turn |

E2E tests skip automatically when `claude` binary is absent, Node.js is unavailable, or no API credentials are found (`ANTHROPIC_API_KEY` or `~/.claude/.credentials.json`).

## Files Changed

| File | Status | Description |
|------|--------|-------------|
| `direct/src/works/iterative/claude/direct/Session.scala` | New | Public `Session` trait |
| `direct/src/works/iterative/claude/direct/internal/cli/SessionProcess.scala` | New | `SessionProcess` class + `start` factory; full process lifecycle management |
| `direct/src/works/iterative/claude/direct/ClaudeCode.scala` | Modified | Added `session(SessionOptions)` on class and companion; added `createSession` and `resolveSessionExecutablePath` helpers |
| `direct/src/works/iterative/claude/direct/package.scala` | Modified | Added `Session`, `SessionOptions`, `SDKUserMessage` re-exports (note: `SessionOptions` was already present from Phase 2; `SDKUserMessage` added here) |
| `direct/test/src/works/iterative/claude/direct/SessionTest.scala` | New | 9 unit tests |
| `direct/test/src/works/iterative/claude/direct/SessionIntegrationTest.scala` | New | 5 integration tests |
| `direct/test/src/works/iterative/claude/direct/SessionE2ETest.scala` | New | 2 E2E tests |
| `direct/test/src/works/iterative/claude/direct/internal/testing/SessionMockCliScript.scala` | New | Shell script generator for stream-json session protocol simulation |
| `project-management/issues/CC-15/phase-03-tasks.md` | Modified | Task tracking updates |
| `project-management/issues/CC-15/review-state.json` | Modified | Review state bookkeeping |

<details>
<summary>Notable implementation details worth reviewing</summary>

**Init message reading (`readInitMessages`):** Uses a 500 ms deadline with 10 ms sleep-poll between `reader.ready()` checks. This avoids blocking indefinitely if the init message is delayed, but also avoids consuming the first user-turn message from the real CLI. Review whether the deadline and poll interval are appropriate for slow CI environments.

**`--verbose` flag injection:** `SessionProcess.start` prepends `--verbose` to the CLI args rather than having it in `CLIArgumentBuilder.buildSessionArgs`. The comment explains this mirrors how query mode adds `--verbose` in `ClaudeCode.buildCliArguments`. Worth verifying the two places stay in sync as the API evolves.

**`configureSessionProcess` duplication:** The process configuration logic (cwd, environment) is duplicated from `ProcessManager.configureProcess` because `configureProcess` takes `QueryOptions`. The comment in the approach doc notes this trade-off. If `ProcessManager` grows, consider extracting a shared helper.

**Turn sequencing:** Per the analysis decision, no client-side guard prevents calling `send` before the previous `Flow` is fully consumed. The CLI queues messages internally. This is intentional and documented in the phase context.

</details>
