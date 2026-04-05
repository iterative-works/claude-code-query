---
generated_from: 6a4f4725166cf9b629b144aad531c87e38815cb9
generated_at: 2026-04-05T13:24:39Z
branch: CC-15-phase-05
issue_id: CC-15
phase: 5
files_analyzed:
  - effectful/src/works/iterative/claude/effectful/Session.scala
  - effectful/src/works/iterative/claude/effectful/internal/cli/SessionProcess.scala
  - effectful/src/works/iterative/claude/effectful/ClaudeCode.scala
  - effectful/src/works/iterative/claude/effectful/package.scala
  - effectful/test/src/works/iterative/claude/effectful/SessionTest.scala
  - effectful/test/src/works/iterative/claude/effectful/SessionIntegrationTest.scala
  - effectful/test/src/works/iterative/claude/effectful/SessionE2ETest.scala
  - effectful/test/src/works/iterative/claude/effectful/EffectfulPackageReexportTest.scala
---

# Review Packet: Phase 5 - Effectful API - Session lifecycle with Resource

## Goals

This phase delivers the cats-effect session API, enabling persistent multi-turn conversations with a single long-lived Claude Code CLI process through a `Resource[IO, Session]` lifecycle.

Key objectives:

- Expose a `Session` trait in the `effectful` package with CQS-split methods: `send(prompt): IO[Unit]` (writes stdin) and `stream: Stream[IO, Message]` (reads stdout until `ResultMessage`)
- Implement `SessionProcess`, a `Resource`-based process manager that spawns the CLI once and keeps it alive across turns, using background fibers for stdin writing, stdout reading, and stderr capture
- Add `ClaudeCode.session(SessionOptions): Resource[IO, Session]` factory to the effectful API entry point
- Re-export `SessionOptions` in the effectful package object for single-import usage
- Apply the CQS refactoring (R1) to the direct `Session` trait and tests to match this phase's design

This phase is the effectful counterpart to the direct API session work delivered in Phases 3-4, adapted for cats-effect idioms with `Resource`, `fs2.Stream`, `IO`, `Ref`, `Queue`, and background fibers.

## Scenarios

- [ ] A session can be opened, a prompt sent, and a streaming response received within `Resource.use`
- [ ] The CLI process starts once on Resource acquire and stays alive across multiple `send` / `stream` cycles
- [ ] The process is terminated cleanly when the `Resource.use` block exits normally
- [ ] The process is terminated cleanly when an exception is thrown inside `Resource.use`
- [ ] `sessionId` returns `"pending"` before the CLI emits an init message
- [ ] `sessionId` is updated from the init `SystemMessage` during Resource acquire
- [ ] `sessionId` is updated from each turn's `ResultMessage` after `stream` completes
- [ ] Each turn's `stream` emits exactly its own messages and terminates after `ResultMessage`
- [ ] Messages from one turn do not bleed into the next turn's stream
- [ ] Multiple sequential `send` / `stream` pairs work correctly within one session
- [ ] The second `send` carries the session ID from the first turn's `ResultMessage` in its `SDKUserMessage` JSON
- [ ] `KeepAliveMessage` and `StreamEventMessage` pass through the stream
- [ ] `SessionOptions` is accessible via a single `effectful.*` import
- [ ] All existing effectful query tests and direct session tests continue to pass

## Entry Points

| File | Method / Class | Why Start Here |
|------|----------------|----------------|
| `effectful/src/works/iterative/claude/effectful/Session.scala` | `Session` trait | Public API contract: defines `send`, `stream`, `sessionId` |
| `effectful/src/works/iterative/claude/effectful/ClaudeCode.scala` | `ClaudeCode.session()` | Factory method: the user-facing entry point that returns `Resource[IO, Session]` |
| `effectful/src/works/iterative/claude/effectful/internal/cli/SessionProcess.scala` | `SessionProcess.start()` | Core implementation: process spawn, fiber management, queue wiring |
| `effectful/src/works/iterative/claude/effectful/internal/cli/SessionProcess.scala` | `SessionImpl` | Session implementation: `send` writes to stdinQueue, `stream` reads from messageQueue |
| `effectful/src/works/iterative/claude/effectful/package.scala` | package object | Re-export: confirms `SessionOptions` is accessible from a single import |

## Diagrams

### Component Architecture

```
effectful package
┌─────────────────────────────────────────────────────────┐
│  ClaudeCode.session(SessionOptions)                      │
│      │                                                   │
│      └─► SessionProcess.start(executablePath, options)   │
│              │                                           │
│              └─► Resource[IO, Session]                   │
│                      acquire: spawn process + fibers     │
│                      release: cancel fibers + close stdin│
└─────────────────────────────────────────────────────────┘

SessionProcess internals
┌──────────────────────────────────────────────────────────────────┐
│  ProcessBuilder.spawn[IO]  ──► fs2.io.process.Process[IO]        │
│                                                                   │
│  Queue[IO, Option[Chunk[Byte]]]  stdinQueue                      │
│      ▲                  │                                        │
│  SessionImpl.send()     └──► fiber: stdinQueue → process.stdin  │
│                                                                   │
│  Queue[IO, Option[Message]]  messageQueue                        │
│      │                  ▲                                        │
│  SessionImpl.stream()   └──── fiber: process.stdout → parser     │
│                                         → sessionIdRef update    │
│                                         → messageQueue           │
│                                                                   │
│  Ref[IO, String]  sessionIdRef  ◄── init message + ResultMessage │
│                                                                   │
│  fiber: process.stderr → logger.debug                            │
└──────────────────────────────────────────────────────────────────┘
```

### Session Lifecycle Flow

```
Resource.use { session =>
    session.send("prompt")          // IO[Unit]
    │  └─ encode SDKUserMessage JSON
    │  └─ stdinQueue.offer(Some(chunk))
    │
    session.stream.compile.toList   // Stream[IO, Message]
       └─ messageQueue.take (loop until ResultMessage)
       └─ emits all messages including ResultMessage
       └─ sessionIdRef updated by background stdout reader on ResultMessage
}
│
│  acquire:
│    spawn process
│    start stdinWriter fiber (drains stdinQueue → process.stdin)
│    start stdoutReader fiber (process.stdout → messageQueue, updates sessionIdRef)
│    start stderrCapture fiber (process.stderr → logger.debug)
│    readInitMessage (peek messageQueue, extract session_id if SystemMessage("init"))
│
│  release:
│    cancel all fibers
│    stdinQueue.offer(None) → closes process.stdin → process exits
```

### Turn Sequence (Multi-turn)

```
Turn 1:
  send("Q1")   →  stdinQueue ← SDKUserMessage(session_id="pending")
  stream        →  [AssistantMessage, ResultMessage(session_id="s1")]
                   sessionIdRef updated to "s1"

Turn 2:
  send("Q2")   →  stdinQueue ← SDKUserMessage(session_id="s1")
  stream        →  [KeepAliveMessage, AssistantMessage, ResultMessage(session_id="s2")]
                   sessionIdRef updated to "s2"
```

## Test Summary

### Unit Tests (`SessionTest.scala`) — 9 tests

| # | Test | Type | Notes |
|---|------|------|-------|
| T1 | SDKUserMessage is correctly encoded for stdin | Unit | Verifies JSON fields: `type`, `message.role`, `message.content`, `session_id` |
| T2 | Session ID defaults to `"pending"` before any send | Unit | Uses minimal mock script with no init message |
| T3 | Session ID extracted from init SystemMessage during Resource acquire | Unit | Mock emits `SystemMessage("init", {"session_id": "..."})` before any send |
| T4 | Session ID updated from ResultMessage after stream completes | Unit | Checks `sessionId` after `stream.compile.drain` |
| T5 | `stream` completes after emitting ResultMessage | Unit | Verifies exactly 1 AssistantMessage + 1 ResultMessage, then stream ends |
| T6 | KeepAlive and StreamEvent messages pass through | Unit | All four message types present in stream |
| T7 | Two sequential sends return isolated streams | Unit | Turn 1 messages not in turn 2, and vice versa |
| T8 | Session ID propagates from first turn to second send | Unit | Reads stdin capture file; second JSON line has `session_id` from turn 1 ResultMessage |
| T9 | Three sequential send/stream cycles work correctly | Unit | Each turn has correct messages and session ID |

All unit tests use `SessionMockCliScript` from the direct module (reused across modules).

### Integration Tests (`SessionIntegrationTest.scala`) — 9 tests

| # | Test | Notes |
|---|------|-------|
| IT1 | Full single-turn lifecycle with mock CLI | Verifies AssistantMessage content and ResultMessage fields |
| IT2 | Resource cleanup on normal exit | Verifies `Resource.use` completes without hanging |
| IT3 | Resource cleanup on error | `IO.raiseError` inside `use`; checks exception propagates and process is cleaned up |
| IT4 | Stdin carries correct SDKUserMessage JSON | Reads captured stdin file; checks `type` and `message.content` |
| IT5 | Session ID from init message during acquire | Checks `sessionId` before any `send` |
| IT6 | Two-turn lifecycle | Verifies init ID, turn 1 response + ID, turn 2 response + ID |
| IT7 | Stdin shows correct session ID progression | First send uses init ID; second send uses turn 1 ResultMessage ID |
| IT8 | Variable message counts per turn | Turn 1: 2 messages; turn 2: 4 messages (keepalive, stream_event, assistant, result) |
| IT9 | `ClaudeCode.session` factory produces working session | End-to-end factory method test with mock script |

### E2E Tests (`SessionE2ETest.scala`) — 3 tests

| # | Test | Gate |
|---|------|------|
| E1 | Single-turn session with real CLI | `assume` on `claude --version` exit code = 0 and API key / credentials file |
| E2 | Two-turn context dependency (`"Remember 42"` → `"What number?"`) | Same gate |
| E3 | Session ID is non-empty and not `"pending"` after first turn | Same gate |

E2E tests skip gracefully when the CLI is not installed or credentials are absent.

### Regression Test

| File | Purpose |
|------|---------|
| `EffectfulPackageReexportTest.scala` | Confirms `SessionOptions` is accessible as `effectful.SessionOptions` (new assertion added alongside existing re-export tests) |

## Files Changed

| File | Change |
|------|--------|
| `effectful/src/works/iterative/claude/effectful/Session.scala` | **New** — `Session` trait with `send`, `stream`, `sessionId` |
| `effectful/src/works/iterative/claude/effectful/internal/cli/SessionProcess.scala` | **New** — `SessionProcess.start` (Resource factory) and `SessionImpl` (queue-based implementation) |
| `effectful/src/works/iterative/claude/effectful/ClaudeCode.scala` | **Modified** — added `session(SessionOptions)` factory and `discoverExecutableForSession` helper |
| `effectful/src/works/iterative/claude/effectful/package.scala` | **Modified** — added `SessionOptions` type and value re-exports |
| `effectful/test/src/works/iterative/claude/effectful/SessionTest.scala` | **New** — 9 unit tests |
| `effectful/test/src/works/iterative/claude/effectful/SessionIntegrationTest.scala` | **New** — 9 integration tests |
| `effectful/test/src/works/iterative/claude/effectful/SessionE2ETest.scala` | **New** — 3 E2E tests |
| `effectful/test/src/works/iterative/claude/effectful/EffectfulPackageReexportTest.scala` | **Modified** — added `SessionOptions` re-export assertion |

<details>
<summary>Key design notes for reviewers</summary>

**Shared stdout reader approach:** The stdout fiber continuously reads lines from the process into `messageQueue`. Each `stream` call pulls from that same queue until it sees a `ResultMessage`. This works without contention because `send` / `stream` calls are sequential — the design does not enforce this but documents that concurrent sends are delegated to the CLI's own queueing (per the resolved architectural decision).

**Init message peeling:** `readInitMessage` races a `messageQueue.take` against a 1-second timeout. If the first message is a `SystemMessage("init", ...)`, the session ID is extracted and the message is consumed (not re-enqueued). If it is any other message type, it is put back into the queue so it appears in the user's first `stream`. If the timeout fires first, `sessionId` stays `"pending"`.

**Stdin queue closure:** On Resource release, the fibers are cancelled and `None` is offered to `stdinQueue`. The stdin writer fiber, which uses `Stream.fromQueueNoneTerminated`, sees the `None` sentinel, terminates, and the process stdin pipe closes — causing the CLI process to exit cleanly.

**Mock script reuse:** `SessionMockCliScript` lives in the `direct` test module and is reused directly by the effectful tests. This avoids duplicating test infrastructure. If the modules are published separately, this dependency will need to be revisited.

</details>
